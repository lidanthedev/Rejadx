package dev.rejadx.server.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import jadx.api.data.ICodeRename;
import jadx.api.data.IJavaNodeRef;
import jadx.api.data.IJavaNodeRef.RefType;
import jadx.api.data.impl.JadxCodeData;
import jadx.core.codegen.TypeGen;
import jadx.core.dex.instructions.args.ArgType;

import dev.rejadx.server.decompiler.IDecompilerEngine;
import dev.rejadx.server.manager.DecompilerManager;

public class ExportMappingsCommand {

    private final DecompilerManager manager;

    public ExportMappingsCommand(DecompilerManager manager) {
        this.manager = manager;
    }

    public CompletableFuture<Object> execute(List<Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            ReentrantReadWriteLock.ReadLock rl = manager.getLock().readLock();
            rl.lock();
            try {
                IDecompilerEngine engine = manager.getEngine();
                if (engine == null) {
                    throw new IllegalStateException("No project loaded");
                }

                JadxCodeData data = engine.getCodeData();
                List<ICodeRename> renames = new ArrayList<>(data.getRenames());

                StringBuilder out = new StringBuilder();
                Set<String> emittedClassHeaders = new HashSet<>();

                // Classes first, then members. Keep output deterministic.
                renames.sort(Comparator
                        .comparing((ICodeRename r) -> rank(r.getNodeRef()))
                        .thenComparing(r -> safe(r.getNodeRef().getDeclaringClass()))
                        .thenComparing(r -> safe(r.getNodeRef().getShortId()))
                        .thenComparing(ICodeRename::getNewName));

                for (ICodeRename rename : renames) {
                    IJavaNodeRef ref = rename.getNodeRef();
                    if (ref == null) {
                        continue;
                    }
                    if (rename.getNewName() == null || rename.getNewName().isEmpty()) {
                        continue;
                    }

                    switch (ref.getType()) {
                        case CLASS -> appendClassRename(out, emittedClassHeaders, ref, rename.getNewName());
                        case FIELD -> appendFieldRename(out, emittedClassHeaders, ref, rename.getNewName());
                        case METHOD -> {
                            if (rename.getCodeRef() == null) {
                                appendMethodRename(out, emittedClassHeaders, ref, rename.getNewName());
                            }
                        }
                        default -> {
                            // Skip PKG/VAR code-ref renames for ProGuard class/member mapping output.
                        }
                    }
                }

                return Collections.singletonMap("mapping", out.toString());
            } finally {
                rl.unlock();
            }
        });
    }

    private static int rank(IJavaNodeRef ref) {
        if (ref == null) return 99;
        return switch (ref.getType()) {
            case CLASS -> 0;
            case FIELD, METHOD -> 1;
            default -> 2;
        };
    }

    private static void appendClassRename(StringBuilder out, Set<String> emittedClassHeaders, IJavaNodeRef ref, String newName) {
        String cls = safe(ref.getDeclaringClass());
        if (cls.isEmpty()) {
            return;
        }
        String mapped = toJavaFqcn(newName);
        out.append(cls).append(" -> ").append(mapped).append(":\n");
        emittedClassHeaders.add(cls);
    }

    private static void appendFieldRename(StringBuilder out, Set<String> emittedClassHeaders, IJavaNodeRef ref, String newName) {
        String cls = safe(ref.getDeclaringClass());
        String shortId = safe(ref.getShortId());
        int colon = shortId.indexOf(':');
        if (cls.isEmpty() || colon <= 0) {
            return;
        }

        String fieldName = shortId.substring(0, colon);
        String typeSig = shortId.substring(colon + 1);
        String javaType;
        try {
            javaType = toJavaType(typeSig);
        } catch (Exception e) {
            return;
        }

        ensureClassHeader(out, emittedClassHeaders, cls);
        out.append("    ")
                .append(javaType)
                .append(' ')
                .append(fieldName)
                .append(" -> ")
                .append(newName)
                .append('\n');
    }

    private static void appendMethodRename(StringBuilder out, Set<String> emittedClassHeaders, IJavaNodeRef ref, String newName) {
        String cls = safe(ref.getDeclaringClass());
        String shortId = safe(ref.getShortId());
        int lparen = shortId.indexOf('(');
        int rparen = shortId.indexOf(')', lparen + 1);
        if (cls.isEmpty() || lparen <= 0 || rparen <= lparen) {
            return;
        }

        String methodName = shortId.substring(0, lparen);
        String argsSig = shortId.substring(lparen + 1, rparen);
        String retSig = shortId.substring(rparen + 1);

        String retType;
        try {
            retType = toJavaType(retSig);
        } catch (Exception e) {
            return;
        }

        List<String> args = parseMethodArgs(argsSig);

        ensureClassHeader(out, emittedClassHeaders, cls);
        out.append("    ")
                .append(retType)
                .append(' ')
                .append(methodName)
                .append('(')
                .append(String.join(",", args))
                .append(") -> ")
                .append(newName)
                .append('\n');
    }

    private static void ensureClassHeader(StringBuilder out, Set<String> emittedClassHeaders, String cls) {
        if (emittedClassHeaders.add(cls)) {
            out.append(cls).append(" -> ").append(cls).append(":\n");
        }
    }

    private static List<String> parseMethodArgs(String argsSig) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < argsSig.length()) {
            int start = i;
            while (i < argsSig.length() && argsSig.charAt(i) == '[') {
                i++;
            }
            if (i >= argsSig.length()) {
                break;
            }

            char c = argsSig.charAt(i);
            if (c == 'L') {
                int end = argsSig.indexOf(';', i);
                if (end == -1) {
                    break;
                }
                i = end + 1;
            } else {
                i++;
            }
            String one = argsSig.substring(start, i);
            out.add(toJavaType(one));
        }
        return out;
    }

    private static String toJavaType(String sig) {
        return TypeGen.signature(ArgType.parse(sig));
    }

    private static String toJavaFqcn(String internalOrJava) {
        String n = internalOrJava;
        if (n.startsWith("L") && n.endsWith(";")) {
            n = n.substring(1, n.length() - 1);
        }
        return n.replace('/', '.');
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
