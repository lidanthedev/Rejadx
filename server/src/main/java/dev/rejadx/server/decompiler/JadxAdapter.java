package dev.rejadx.server.decompiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.ICodeInfo;
import jadx.api.data.ICodeComment;
import jadx.api.data.ICodeRename;
import jadx.api.data.IJavaNodeRef;
import jadx.api.data.impl.JadxCodeComment;
import jadx.api.data.impl.JadxCodeData;
import jadx.api.data.impl.JadxCodeRename;
import jadx.api.data.impl.JadxNodeRef;

import dev.rejadx.server.model.PackageNode;
import dev.rejadx.server.model.ResolvedNode;
import dev.rejadx.server.model.SourceType;
import dev.rejadx.server.model.XrefLocation;
import dev.rejadx.server.uri.JadxUriParser;

public class JadxAdapter implements IDecompilerEngine {

    private static final Logger log = LoggerFactory.getLogger(JadxAdapter.class);
    private static final int XREF_LIMIT = 200;

    private JadxDecompiler jadx;
    private JadxCodeData liveCodeData;

    @Override
    public void load(Path inputFile, Path cacheDir, JadxCodeData existingData) throws Exception {
        Files.createDirectories(cacheDir.resolve("src"));
        Files.createDirectories(cacheDir.resolve("res"));

        liveCodeData = (existingData != null) ? existingData : new JadxCodeData();

        JadxArgs args = new JadxArgs();
        args.addInputFile(inputFile.toFile());
        args.setOutDirSrc(cacheDir.resolve("src").toFile());
        args.setOutDirRes(cacheDir.resolve("res").toFile());
        args.setCodeData(liveCodeData);

        jadx = new JadxDecompiler(args);
        jadx.load();

        log.info("APK loaded: {} classes", jadx.getClassesWithInners().size());

        // Eagerly decompile all classes: populates in-memory cache and writes
        // .java files to cacheDir/src for persistent storage between sessions.
        jadx.saveSources();

        log.info("Eager decompilation complete");
    }

    // --- Navigation ---

    @Override
    public List<PackageNode> getPackageTree() {
        return PackageNode.fromJadxPackages(jadx.getPackages());
    }

    @Override
    public int getClassCount() {
        return jadx.getClassesWithInners().size();
    }

    // --- Source ---

    @Override
    public String getSource(String rawClassName, SourceType type) throws ClassNotFoundException {
        JavaClass cls = findClass(rawClassName);
        // getCode() reads from the in-memory cache populated by saveSources() — fast path
        return (type == SourceType.SMALI) ? cls.getSmali() : cls.getCode();
    }

    // --- Xrefs ---

    @Override
    public List<XrefLocation> getReferences(String rawClassName, int charOffset) throws ClassNotFoundException {
        JavaClass cls = findClass(rawClassName);
        ICodeInfo codeInfo = cls.getCodeInfo();
        JavaNode target = jadx.getJavaNodeAtPosition(codeInfo, charOffset);
        if (target == null) {
            return Collections.emptyList();
        }

        List<XrefLocation> results = new ArrayList<>();
        for (JavaNode usage : target.getUseIn()) {
            if (results.size() >= XREF_LIMIT) break;
            JavaClass userCls = topClass(usage);
            if (userCls == null) continue;

            try {
                ICodeInfo userCodeInfo = userCls.getCodeInfo();
                String userSource = userCodeInfo.getCodeStr();
                List<Integer> positions = userCls.getUsePlacesFor(userCodeInfo, target);
                String uri = JadxUriParser.build(userCls.getRawName(), SourceType.JAVA);
                for (int pos : positions) {
                    if (results.size() >= XREF_LIMIT) break;
                    int[] lc = charOffsetToLineChar(userSource, pos);
                    results.add(new XrefLocation(uri, lc[0], lc[1]));
                }
            } catch (Exception e) {
                log.warn("Skipping xref scan for class {}: {}", userCls.getFullName(), e.getMessage());
            }
        }
        return results;
    }

    // --- Node resolution ---

    @Override
    public ResolvedNode resolveNodeAt(String rawClassName, int charOffset) throws ClassNotFoundException {
        JavaClass cls = findClass(rawClassName);
        ICodeInfo codeInfo = cls.getCodeInfo();
        JavaNode node = jadx.getJavaNodeAtPosition(codeInfo, charOffset);
        if (node == null) return null;

        if (node instanceof JavaClass jcls) {
            return new ResolvedNode(JadxNodeRef.forCls(jcls), jcls.getName(), "class");
        } else if (node instanceof JavaMethod jmth) {
            return new ResolvedNode(JadxNodeRef.forMth(jmth), jmth.getName(), "method");
        } else if (node instanceof JavaField jfld) {
            return new ResolvedNode(JadxNodeRef.forFld(jfld), jfld.getName(), "field");
        }
        return null; // variable or other non-renameable node
    }

    // --- Mutations ---

    @Override
    public String applyRename(IJavaNodeRef nodeRef, String newName) throws Exception {
        List<ICodeRename> renames = new ArrayList<>(liveCodeData.getRenames());
        renames.removeIf(r -> r.getNodeRef().equals(nodeRef));
        renames.add(new JadxCodeRename(nodeRef, newName));
        liveCodeData.setRenames(renames);
        jadx.getArgs().setCodeData(liveCodeData);

        JavaClass affected = findAffectedClass(nodeRef);
        if (affected == null) throw new ClassNotFoundException("Could not resolve class for nodeRef");

        // Reload the directly renamed class
        affected.reload();

        // Reload immediate callers so their source reflects the new name
        for (JavaNode user : affected.getUseIn()) {
            JavaClass userCls = topClass(user);
            if (userCls != null && !userCls.equals(affected)) {
                try { userCls.reload(); } catch (Exception e) {
                    log.warn("Could not reload user class {}: {}", userCls.getFullName(), e.getMessage());
                }
            }
        }

        return affected.getCode();
    }

    @Override
    public String applyComment(ICodeComment comment) throws Exception {
        List<ICodeComment> comments = new ArrayList<>(liveCodeData.getComments());
        // Remove existing comment at the same attachment point
        comments.removeIf(c ->
                c.getNodeRef().equals(comment.getNodeRef())
                && java.util.Objects.equals(c.getCodeRef(), comment.getCodeRef()));
        comments.add(comment);
        liveCodeData.setComments(comments);
        jadx.getArgs().setCodeData(liveCodeData);

        JavaClass affected = findAffectedClass(comment.getNodeRef());
        if (affected == null) throw new ClassNotFoundException("Could not resolve class for comment nodeRef");
        affected.reload();
        return affected.getCode();
    }

    // --- State ---

    @Override
    public JadxCodeData getCodeData() {
        return liveCodeData;
    }

    @Override
    public void close() {
        if (jadx != null) {
            jadx.close();
            jadx = null;
        }
    }

    // --- Helpers ---

    private JavaClass findClass(String rawClassName) throws ClassNotFoundException {
        JavaClass cls = jadx.searchJavaClassByOrigFullName(rawClassName);
        if (cls == null) cls = jadx.searchJavaClassByAliasFullName(rawClassName);
        if (cls == null) throw new ClassNotFoundException("Class not found: " + rawClassName);
        return cls;
    }

    /**
     * Finds the JavaClass affected by a given node reference.
     * For CLASS refs: the declaring class field holds the class name.
     * For METHOD/FIELD refs: declaring class is in getDeclaringClass().
     */
    private JavaClass findAffectedClass(IJavaNodeRef nodeRef) {
        try {
            String declClass = nodeRef.getDeclaringClass();
            if (declClass == null || declClass.isEmpty()) {
                // CLASS ref — short ID not used; declaring class IS the class name stored in shortId
                return findClass(nodeRef.getShortId() != null ? nodeRef.getShortId() : "");
            }
            return findClass(declClass);
        } catch (ClassNotFoundException e) {
            log.warn("Could not find affected class for ref: {}", nodeRef);
            return null;
        }
    }

    private JavaClass topClass(JavaNode node) {
        return node.getTopParentClass();
    }

    private static int[] charOffsetToLineChar(String source, int offset) {
        int line = 0;
        int lineStart = 0;
        int len = Math.min(offset, source.length());
        for (int i = 0; i < len; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[]{line, offset - lineStart};
    }
}
