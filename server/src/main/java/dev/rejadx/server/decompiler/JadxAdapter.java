package dev.rejadx.server.decompiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.api.data.CommentStyle;
import jadx.api.data.ICodeComment;
import jadx.api.data.ICodeRename;
import jadx.api.data.IJavaCodeRef;
import jadx.api.data.IJavaNodeRef;
import jadx.api.data.impl.JadxCodeRef;
import jadx.api.data.impl.JadxCodeComment;
import jadx.api.data.impl.JadxCodeData;
import jadx.api.data.impl.JadxCodeRename;
import jadx.api.data.impl.JadxNodeRef;
import jadx.api.args.IntegerFormat;
import jadx.api.metadata.ICodeAnnotation;
import jadx.api.metadata.ICodeMetadata;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.metadata.annotations.InsnCodeOffset;
import jadx.api.metadata.annotations.NodeDeclareRef;
import jadx.api.metadata.annotations.VarNode;
import jadx.api.metadata.annotations.VarRef;
import jadx.api.CommentsLevel;
import jadx.api.DecompilationMode;

import dev.rejadx.server.model.PackageNode;
import dev.rejadx.server.model.CommentInfo;
import dev.rejadx.server.model.RenameTarget;
import dev.rejadx.server.model.ResolvedNode;
import dev.rejadx.server.model.SourceType;
import dev.rejadx.server.model.XrefLocation;
import dev.rejadx.server.uri.JadxUriParser;

public class JadxAdapter implements IDecompilerEngine {

    private static final Logger log = LoggerFactory.getLogger(JadxAdapter.class);
    private static final int XREF_LIMIT = 200;

    private JadxDecompiler jadx;
    private JadxCodeData liveCodeData;
    private String customArgs = "";

    public void setCustomArgs(String customArgs) {
        this.customArgs = customArgs == null ? "" : customArgs;
    }

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

        applyCustomArgs(args);

        jadx = new JadxDecompiler(args);
        jadx.load();

        log.info("APK loaded: {} classes", jadx.getClassesWithInners().size());

        // Eagerly decompile all classes: populates in-memory cache and writes
        // .java files to cacheDir/src for persistent storage between sessions.
        jadx.saveSources();

        log.info("Eager decompilation complete");
    }

    private void applyCustomArgs(JadxArgs args) {
        String raw = customArgs.trim();
        if (raw.isEmpty()) {
            return;
        }

        String[] parts = raw.split("\\s+");
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if ("--show-inconsistent-code".equals(p)) {
                args.setShowInconsistentCode(true);
            } else if ("--no-debug-info".equals(p)) {
                args.setDebugInfo(false);
            } else if ("--escape-unicode".equals(p)) {
                args.setEscapeUnicode(true);
            } else if (p.startsWith("--threads-count=")) {
                parseInt(p).ifPresent(args::setThreadsCount);
            } else if (p.startsWith("--decompilation-mode=")) {
                String v = valueOf(p);
                if (v != null) {
                    try {
                        args.setDecompilationMode(DecompilationMode.valueOf(v.toUpperCase()));
                    } catch (Exception ignored) {
                        // Ignore unsupported values.
                    }
                }
            } else if (p.startsWith("--comments-level=")) {
                String v = valueOf(p);
                if (v != null) {
                    try {
                        args.setCommentsLevel(CommentsLevel.valueOf(v.toUpperCase()));
                    } catch (Exception ignored) {
                        // Ignore unsupported values.
                    }
                }
            } else if (p.startsWith("--integer-format=")) {
                String v = valueOf(p);
                if (v != null) {
                    try {
                        args.setIntegerFormat(IntegerFormat.valueOf(v.toUpperCase()));
                    } catch (Exception ignored) {
                        // Ignore unsupported values.
                    }
                }
            }
        }
    }

    private static java.util.OptionalInt parseInt(String arg) {
        String v = valueOf(arg);
        if (v == null) {
            return java.util.OptionalInt.empty();
        }
        try {
            return java.util.OptionalInt.of(Integer.parseInt(v));
        } catch (Exception e) {
            return java.util.OptionalInt.empty();
        }
    }

    private static String valueOf(String arg) {
        int idx = arg.indexOf('=');
        if (idx < 0 || idx + 1 >= arg.length()) {
            return null;
        }
        return arg.substring(idx + 1);
    }

    // --- Navigation ---

    @Override
    public List<PackageNode> getPackageTree() {
        return PackageNode.fromJadx(jadx.getPackages(), jadx.getResources());
    }

    @Override
    public int getClassCount() {
        return jadx.getClassesWithInners().size();
    }

    // --- Source ---

    @Override
    public String getSource(String rawClassName, SourceType type) throws ClassNotFoundException {
        if (type == SourceType.RESOURCE) {
            return getResourceSource(rawClassName);
        }
        JavaClass cls = findClass(rawClassName);
        // getCode() reads from the in-memory cache populated by saveSources() — fast path
        return (type == SourceType.SMALI) ? cls.getSmali() : cls.getCode();
    }

    private String getResourceSource(String resourcePath) throws ClassNotFoundException {
        for (ResourceFile rf : jadx.getResources()) {
            if (!rf.getDeobfName().equals(resourcePath)) {
                continue;
            }
            try {
                if (rf.getType() == ResourceType.MANIFEST
                        || rf.getType() == ResourceType.XML
                        || rf.getType() == ResourceType.JSON
                        || rf.getType() == ResourceType.TEXT
                        || rf.getType() == ResourceType.ARSC) {
                    return rf.loadContent().getText().getCodeStr();
                }
                return "// Binary resource: " + rf.getDeobfName() + " (" + rf.getType() + ")";
            } catch (Exception e) {
                return "// Failed to decode resource " + rf.getDeobfName() + ": " + e.getMessage();
            }
        }
        throw new ClassNotFoundException("Resource not found: " + resourcePath);
    }

    // --- Xrefs ---

    @Override
    public List<XrefLocation> getReferences(String rawClassName, int charOffset, boolean includeDeclaration) throws ClassNotFoundException {
        JavaClass cls = findClass(rawClassName);
        ICodeInfo codeInfo = cls.getCodeInfo();
        JavaNode target = resolveBestNodeAt(codeInfo, charOffset);
        if (target == null) {
            return Collections.emptyList();
        }

        List<XrefLocation> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<JavaNode> searchNodes = buildUsageSearchNodes(target);

        if (includeDeclaration) {
            for (JavaNode searchNode : searchNodes) {
                addDeclarationLocation(searchNode, results, seen);
            }
        }

        for (JavaNode searchNode : searchNodes) {
            for (JavaNode usage : searchNode.getUseIn()) {
                if (results.size() >= XREF_LIMIT) break;
                JavaClass userCls = topClass(usage);
                if (userCls == null) continue;

                try {
                    ICodeInfo userCodeInfo = userCls.getCodeInfo();
                    String userSource = userCodeInfo.getCodeStr();
                    List<Integer> positions = userCls.getUsePlacesFor(userCodeInfo, searchNode);
                    String uri = JadxUriParser.build(userCls.getRawName(), SourceType.JAVA);
                    int len = symbolLength(searchNode);
                    for (int pos : positions) {
                        if (results.size() >= XREF_LIMIT) break;
                        int[] lc = charOffsetToLineChar(userSource, pos);
                        addXref(results, seen, uri, lc[0], lc[1], len);
                    }
                } catch (Exception e) {
                    log.warn("Skipping xref scan for class {}: {}", userCls.getFullName(), e.getMessage());
                }
            }
        }
        return results;
    }

    // --- Node resolution ---

    @Override
    public ResolvedNode resolveNodeAt(String rawClassName, int charOffset) throws ClassNotFoundException {
        JavaClass cls = findClass(rawClassName);
        ICodeInfo codeInfo = cls.getCodeInfo();
        JavaNode node = resolveBestNodeAt(codeInfo, charOffset);
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

    @Override
    public RenameTarget resolveRenameTargetAt(String rawClassName, int charOffset) throws ClassNotFoundException {
        JavaClass cls = findClass(rawClassName);
        ICodeInfo codeInfo = cls.getCodeInfo();
        String source = codeInfo.getCodeStr();
        ICodeMetadata md = codeInfo.getCodeMetadata();

        int[] tokenRange = findIdentifierRange(source, charOffset);
        String token = tokenRange == null ? "" : source.substring(tokenRange[0], tokenRange[1]);

        if (tokenRange != null) {
            // Try all positions inside identifier text first (most accurate).
            for (int p = tokenRange[0]; p < tokenRange[1]; p++) {
                RenameTarget byAnn = resolveRenameTargetFromAnnotation(codeInfo, md.getAt(p));
                if (byAnn != null) {
                    return byAnn;
                }
            }
        }

        // Variable rename target: method node + variable codeRef (GUI parity)
        JavaNode nodeAtPos = jadx.getJavaNodeAtPosition(codeInfo, charOffset);
        RenameTarget directTarget = toRenameTarget(nodeAtPos, token);
        if (directTarget != null) {
            return directTarget;
        }

        RenameTarget annTarget = resolveRenameTargetFromAnnotation(codeInfo, md.getAt(charOffset));
        if (annTarget != null) {
            return annTarget;
        }

        // If cursor is just after the symbol, try previous character too.
        if (charOffset > 0) {
            annTarget = resolveRenameTargetFromAnnotation(codeInfo, md.getAt(charOffset - 1));
            if (annTarget != null) {
                return annTarget;
            }
        }

        // Last fallback: closest/enclosing node but only when it matches identifier token.
        JavaNode best = resolveBestNodeAt(codeInfo, charOffset);
        RenameTarget bestTarget = toRenameTarget(best, token);
        if (bestTarget != null) {
            return bestTarget;
        }
        return null;
    }

    // --- Mutations ---

    @Override
    public String applyRename(IJavaNodeRef nodeRef, IJavaCodeRef codeRef, String newName) throws Exception {
        JavaNode node = resolveNodeRef(nodeRef);

        boolean resetToOriginal = newName == null || newName.isEmpty();
        if (resetToOriginal && codeRef == null && node != null) {
            // Node-level reset must remove runtime alias directly, otherwise jadx can
            // re-introduce synthetic aliases in subsequent passes.
            node.removeAlias();
        }

        List<ICodeRename> renames = new ArrayList<>(liveCodeData.getRenames());
        renames.removeIf(r -> r.getNodeRef().equals(nodeRef)
                && java.util.Objects.equals(r.getCodeRef(), codeRef));
        if (!resetToOriginal) {
            renames.add(new JadxCodeRename(nodeRef, codeRef, newName));
        }

        liveCodeData.setRenames(renames);
        jadx.getArgs().setCodeData(liveCodeData);
        jadx.reloadCodeData();

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
        jadx.reloadCodeData();

        JavaClass affected = findAffectedClass(comment.getNodeRef());
        if (affected == null) throw new ClassNotFoundException("Could not resolve class for comment nodeRef");
        affected.reload();
        return affected.getCode();
    }

    @Override
    public String addCommentAt(String rawClassName, int line, int character, String comment, CommentStyle style) throws Exception {
        JavaClass cls = findClass(rawClassName);
        ICodeInfo codeInfo = cls.getCodeInfo();
        String source = codeInfo.getCodeStr();
        int charOffset = lineCharToOffset(source, line, character);

        JadxCodeComment codeComment = resolveCommentRef(cls, codeInfo, charOffset, comment, style);
        if (codeComment == null) {
            throw new IllegalArgumentException("No comment target at position (" + line + "," + character + ")");
        }

        if (comment == null || comment.trim().isEmpty()) {
            return removeCommentAt(codeComment);
        }
        return applyComment(codeComment);
    }

    @Override
    public CommentInfo findCommentAt(String rawClassName, int line, int character) throws Exception {
        JavaClass cls = findClass(rawClassName);
        ICodeInfo codeInfo = cls.getCodeInfo();
        int charOffset = lineCharToOffset(codeInfo.getCodeStr(), line, character);

        JadxCodeComment ref = resolveCommentRef(cls, codeInfo, charOffset, "", CommentStyle.LINE);
        if (ref == null) {
            return new CommentInfo(false, "", "LINE");
        }

        for (ICodeComment c : liveCodeData.getComments()) {
            if (c.getNodeRef().equals(ref.getNodeRef())
                    && java.util.Objects.equals(c.getCodeRef(), ref.getCodeRef())) {
                return new CommentInfo(true, c.getComment(), c.getStyle().name());
            }
        }
        return new CommentInfo(false, "", "LINE");
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

    private JavaNode resolveNodeRef(IJavaNodeRef nodeRef) {
        try {
            JavaClass cls = findAffectedClass(nodeRef);
            if (cls == null) {
                return null;
            }
            return switch (nodeRef.getType()) {
                case CLASS -> cls;
                case FIELD -> cls.getFields().stream()
                        .filter(f -> f.getFieldNode().getFieldInfo().getShortId().equals(nodeRef.getShortId()))
                        .findFirst()
                        .orElse(null);
                case METHOD -> cls.getMethods().stream()
                        .filter(m -> m.getMethodNode().getMethodInfo().getShortId().equals(nodeRef.getShortId()))
                        .findFirst()
                        .orElse(null);
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    private JavaClass topClass(JavaNode node) {
        return node.getTopParentClass();
    }

    private JavaNode resolveBestNodeAt(ICodeInfo codeInfo, int charOffset) {
        JavaNode node = jadx.getJavaNodeAtPosition(codeInfo, charOffset);
        if (node != null) {
            return node;
        }
        node = jadx.getClosestJavaNode(codeInfo, charOffset);
        if (node != null) {
            return node;
        }
        return jadx.getEnclosingNode(codeInfo, charOffset);
    }

    private JadxCodeComment resolveCommentRef(JavaClass cls, ICodeInfo codeInfo, int pos, String text, CommentStyle style) {
        ICodeMetadata metadata = codeInfo.getCodeMetadata();

        int lineStartPos = lineStartOffset(codeInfo.getCodeStr(), pos);

        // Method instruction comment (same strategy as jadx-gui CommentAction)
        ICodeAnnotation offsetAnn = metadata.searchUp(pos, lineStartPos, ICodeAnnotation.AnnType.OFFSET);
        if (offsetAnn instanceof InsnCodeOffset insnOffset) {
            JavaNode node = jadx.getJavaNodeByRef(metadata.getNodeAt(pos));
            if (node instanceof JavaMethod method) {
                JadxNodeRef nodeRef = JadxNodeRef.forMth(method);
                return new JadxCodeComment(nodeRef, JadxCodeRef.forInsn(insnOffset.getOffset()), text, style);
            }
        }

        // Declaration on current line
        ICodeNodeRef nodeDef = metadata.searchUp(pos, (off, ann) -> {
            if (lineStartPos <= off && ann.getAnnType() == ICodeAnnotation.AnnType.DECLARATION) {
                ICodeNodeRef defRef = ((NodeDeclareRef) ann).getNode();
                if (defRef.getAnnType() != ICodeAnnotation.AnnType.VAR) {
                    return defRef;
                }
            }
            return null;
        });
        if (nodeDef != null) {
            JavaNode defNode = jadx.getJavaNodeByRef(nodeDef);
            JadxNodeRef nodeRef = JadxNodeRef.forJavaNode(defNode);
            if (nodeRef != null) {
                return new JadxCodeComment(nodeRef, text, style);
            }
        }

        // Comment line above declaration
        if (isCommentOnlyLine(codeInfo.getCodeStr(), lineStartPos)) {
            ICodeNodeRef belowRef = metadata.searchDown(pos, (off, ann) -> {
                if (off > pos && ann.getAnnType() == ICodeAnnotation.AnnType.DECLARATION) {
                    return ((NodeDeclareRef) ann).getNode();
                }
                return null;
            });
            if (belowRef != null) {
                JavaNode defNode = jadx.getJavaNodeByRef(belowRef);
                JadxNodeRef nodeRef = JadxNodeRef.forJavaNode(defNode);
                if (nodeRef != null) {
                    return new JadxCodeComment(nodeRef, text, style);
                }
            }
        }

        // Fallback: attach to enclosing class declaration
        JadxNodeRef clsRef = JadxNodeRef.forCls(cls);
        return new JadxCodeComment(clsRef, text, style);
    }

    private static int lineCharToOffset(String source, int line, int character) {
        int currentLine = 0;
        for (int i = 0; i < source.length(); i++) {
            if (currentLine == line) return i + character;
            if (source.charAt(i) == '\n') currentLine++;
        }
        return source.length();
    }

    private static int lineStartOffset(String source, int pos) {
        int p = Math.max(0, Math.min(pos, source.length()));
        int idx = source.lastIndexOf('\n', Math.max(0, p - 1));
        return idx == -1 ? 0 : idx + 1;
    }

    private static boolean isCommentOnlyLine(String source, int lineStartPos) {
        int end = source.indexOf('\n', lineStartPos);
        if (end == -1) {
            end = source.length();
        }
        String line = source.substring(Math.max(0, lineStartPos), end).trim();
        return line.startsWith("//") || (line.startsWith("/*") && line.endsWith("*/"));
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

    private void addDeclarationLocation(JavaNode target, List<XrefLocation> results, Set<String> seen) {
        try {
            JavaClass declTop = topClass(target);
            if (declTop == null) {
                return;
            }
            ICodeInfo declCode = declTop.getCodeInfo();
            int defPos = target.getDefPos();
            if (defPos < 0) {
                return;
            }
            int[] lc = charOffsetToLineChar(declCode.getCodeStr(), defPos);
            String uri = JadxUriParser.build(declTop.getRawName(), SourceType.JAVA);
            addXref(results, seen, uri, lc[0], lc[1], symbolLength(target));
        } catch (Exception e) {
            log.debug("Skipping declaration location for {}: {}", target, e.getMessage());
        }
    }

    private static void addXref(List<XrefLocation> results, Set<String> seen, String uri, int line, int character, int length) {
        String key = uri + "#" + line + ":" + character;
        if (seen.add(key)) {
            results.add(new XrefLocation(uri, line, character, Math.max(1, length)));
        }
    }

    public XrefLocation getDefinition(String rawClassName, int charOffset) throws ClassNotFoundException {
        JavaClass cls = findClass(rawClassName);
        ICodeInfo codeInfo = cls.getCodeInfo();
        JavaNode target = resolveBestNodeAt(codeInfo, charOffset);
        if (target == null) {
            return null;
        }

        JavaClass declTop = topClass(target);
        if (declTop == null) {
            return null;
        }
        int defPos = target.getDefPos();
        if (defPos < 0) {
            return null;
        }

        int[] lc = charOffsetToLineChar(declTop.getCodeInfo().getCodeStr(), defPos);
        String uri = JadxUriParser.build(declTop.getRawName(), SourceType.JAVA);
        return new XrefLocation(uri, lc[0], lc[1], symbolLength(target));
    }

    @Override
    public XrefLocation getClassDefinitionByName(String rawClassName) throws ClassNotFoundException {
        JavaClass cls = findClass(rawClassName);
        ICodeInfo codeInfo = cls.getCodeInfo();
        String source = codeInfo.getCodeStr();
        String className = cls.getName();
        int idx = source.indexOf(className);
        if (idx < 0) {
            idx = Math.max(0, cls.getDefPos());
        }
        int[] lc = charOffsetToLineChar(source, idx);
        return new XrefLocation(
                JadxUriParser.build(cls.getRawName(), SourceType.JAVA),
                lc[0], lc[1],
                Math.max(1, className.length()));
    }

    @Override
    public List<XrefLocation> searchCode(String query, boolean caseSensitive, boolean regex, int maxResults) {
        if (query == null || query.isEmpty()) {
            return Collections.emptyList();
        }
        int limit = maxResults > 0 ? maxResults : 500;

        List<XrefLocation> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Pattern pattern = null;
        if (regex) {
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            pattern = Pattern.compile(query, flags);
        }

        String needle = regex ? null : (caseSensitive ? query : query.toLowerCase());

        for (JavaClass cls : jadx.getClasses()) {
            if (out.size() >= limit) {
                break;
            }
            try {
                String source = cls.getCode();
                String uri = JadxUriParser.build(cls.getRawName(), SourceType.JAVA);

                if (regex) {
                    Matcher matcher = pattern.matcher(source);
                    while (matcher.find() && out.size() < limit) {
                        int idx = matcher.start();
                        int len = Math.max(1, matcher.end() - matcher.start());
                        int[] lc = charOffsetToLineChar(source, idx);
                        addXref(out, seen, uri, lc[0], lc[1], len);
                    }
                } else {
                    String hay = caseSensitive ? source : source.toLowerCase();
                    int from = 0;
                    while (from < hay.length() && out.size() < limit) {
                        int idx = hay.indexOf(needle, from);
                        if (idx < 0) {
                            break;
                        }
                        int[] lc = charOffsetToLineChar(source, idx);
                        addXref(out, seen, uri, lc[0], lc[1], query.length());
                        from = idx + Math.max(1, needle.length());
                    }
                }
            } catch (Exception e) {
                log.debug("Skipping search in class {}: {}", cls.getFullName(), e.getMessage());
            }
        }
        return out;
    }

    private static List<JavaNode> buildUsageSearchNodes(JavaNode target) {
        List<JavaNode> result = new ArrayList<>();
        result.add(target);

        if (target instanceof JavaMethod method) {
            List<JavaMethod> related = method.getOverrideRelatedMethods();
            if (!related.isEmpty()) {
                result.clear();
                result.addAll(related);
            }
            return result;
        }

        if (target instanceof JavaClass cls) {
            for (JavaMethod method : cls.getMethods()) {
                if (method.isConstructor()) {
                    result.add(method);
                }
            }
        }
        return result;
    }

    private static int symbolLength(JavaNode node) {
        String name = node.getName();
        return name == null || name.isEmpty() ? 1 : name.length();
    }

    private static String safeName(String name) {
        return name == null ? "" : name;
    }

    private String removeCommentAt(ICodeComment target) throws Exception {
        List<ICodeComment> comments = new ArrayList<>(liveCodeData.getComments());
        comments.removeIf(c ->
                c.getNodeRef().equals(target.getNodeRef())
                        && java.util.Objects.equals(c.getCodeRef(), target.getCodeRef()));
        liveCodeData.setComments(comments);
        jadx.getArgs().setCodeData(liveCodeData);
        jadx.reloadCodeData();

        JavaClass affected = findAffectedClass(target.getNodeRef());
        if (affected == null) throw new ClassNotFoundException("Could not resolve class for comment nodeRef");
        affected.reload();
        return affected.getCode();
    }

    private RenameTarget resolveRenameTargetFromAnnotation(ICodeInfo codeInfo, ICodeAnnotation annAt) {
        if (annAt instanceof NodeDeclareRef decl && decl.getNode() instanceof VarNode varNode) {
            JavaNode varJavaNode = jadx.getJavaNodeByRef(varNode);
            if (varJavaNode instanceof jadx.api.JavaVariable var) {
                return new RenameTarget(
                        JadxNodeRef.forMth(var.getMth()),
                        JadxCodeRef.forVar(varNode.getReg(), varNode.getSsa()),
                        safeName(varNode.getName()),
                        "variable");
            }
            return null;
        }
        if (annAt instanceof VarRef varRef) {
            ICodeAnnotation declAnn = codeInfo.getCodeMetadata().getAt(varRef.getRefPos());
            if (declAnn instanceof NodeDeclareRef decl && decl.getNode() instanceof VarNode varNode) {
                JavaNode varJavaNode = jadx.getJavaNodeByRef(varNode);
                if (varJavaNode instanceof jadx.api.JavaVariable var) {
                    return new RenameTarget(
                            JadxNodeRef.forMth(var.getMth()),
                            JadxCodeRef.forVar(var),
                            safeName(var.getName()),
                            "variable");
                }
            }
            return null;
        }

        JavaNode node = jadx.getJavaNodeByCodeAnnotation(codeInfo, annAt);
        return toRenameTarget(node, null);
    }

    private RenameTarget toRenameTarget(JavaNode node, String token) {
        if (node == null) {
            return null;
        }
        if (node instanceof jadx.api.JavaVariable var) {
            return new RenameTarget(
                    JadxNodeRef.forMth(var.getMth()),
                    JadxCodeRef.forVar(var),
                    safeName(var.getName()),
                    "variable");
        }

        // Avoid broad/wrong rename when node doesn't match identifier under cursor.
        if (token != null && !token.isEmpty()) {
            String nodeName = safeName(node.getName());
            boolean constructorNameMatch = node instanceof JavaMethod m && m.isConstructor()
                    && token.equals(safeName(m.getDeclaringClass().getName()));
            if (!token.equals(nodeName) && !constructorNameMatch) {
                return null;
            }
        }

        if (node instanceof JavaClass jcls) {
            return new RenameTarget(JadxNodeRef.forCls(jcls), null, safeName(jcls.getName()), "class");
        }
        if (node instanceof JavaMethod jmth) {
            return new RenameTarget(JadxNodeRef.forMth(jmth), null, safeName(jmth.getName()), "method");
        }
        if (node instanceof JavaField jfld) {
            return new RenameTarget(JadxNodeRef.forFld(jfld), null, safeName(jfld.getName()), "field");
        }
        return null;
    }

    private static int[] findIdentifierRange(String source, int offset) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        int len = source.length();
        int pos = Math.max(0, Math.min(offset, len - 1));

        if (!isIdentifierChar(source.charAt(pos))) {
            if (pos > 0 && isIdentifierChar(source.charAt(pos - 1))) {
                pos = pos - 1;
            } else {
                return null;
            }
        }

        int start = pos;
        while (start > 0 && isIdentifierChar(source.charAt(start - 1))) {
            start--;
        }
        int end = pos + 1;
        while (end < len && isIdentifierChar(source.charAt(end))) {
            end++;
        }
        return new int[]{start, end};
    }

    private static boolean isIdentifierChar(char ch) {
        return Character.isJavaIdentifierPart(ch) || ch == '$';
    }
}
