package dev.rejadx.server.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import jadx.api.JavaClass;
import jadx.api.JavaPackage;

import dev.rejadx.server.uri.JadxUriParser;

public class PackageNode {
    private static final String DEFAULT_PACKAGE_LABEL = "(default)";

    private final String name;
    private final String fullName;
    private final String uri;    // null for packages, non-null for class leaves
    private final boolean isPackage;
    private final List<PackageNode> children;

    private PackageNode(String name, String fullName, String uri, boolean isPackage, List<PackageNode> children) {
        this.name = name;
        this.fullName = fullName;
        this.uri = uri;
        this.isPackage = isPackage;
        this.children = children;
    }

    public static PackageNode fromJadxPackage(JavaPackage pkg) {
        List<PackageNode> children = new ArrayList<>();
        for (JavaPackage subPkg : pkg.getSubPackages()) {
            children.add(fromJadxPackage(subPkg));
        }
        for (JavaClass cls : pkg.getClassesNoDup()) {
            if (cls.getDeclaringClass() != null) {
                continue;
            }
            children.add(fromJadxClass(cls));
        }
        sortChildren(children);

        String name = pkg.isDefault() ? DEFAULT_PACKAGE_LABEL : pkg.getName();
        String fullName = pkg.getFullName().isEmpty() ? DEFAULT_PACKAGE_LABEL : pkg.getFullName();
        return new PackageNode(name, fullName, null, true, children);
    }

    public static List<PackageNode> fromJadxPackages(List<JavaPackage> packages) {
        List<PackageNode> roots = new ArrayList<>();
        for (JavaPackage pkg : packages) {
            if (!pkg.isRoot()) {
                continue;
            }

            // Match JADX GUI hierarchy behavior: keep every root package node,
            // even when it only contains subpackages and no direct classes.
            roots.add(fromJadxPackage(pkg));
        }

        if (roots.isEmpty()) {
            // Fallback for API/layout variations: keep only top-level packages.
            for (JavaPackage pkg : packages) {
                if (pkg.getFullName().isEmpty() || pkg.getName().contains(".")) {
                    continue;
                }
                roots.add(fromJadxPackage(pkg));
            }
        }

        sortChildren(roots);
        return roots;
    }

    private static PackageNode fromJadxClass(JavaClass cls) {
        List<PackageNode> innerChildren = cls.getInnerClasses().stream()
                .map(PackageNode::fromJadxClass)
                .collect(Collectors.toList());
        String classUri = JadxUriParser.build(cls.getRawName(), SourceType.JAVA);
        return new PackageNode(cls.getName(), cls.getFullName(), classUri, false, innerChildren);
    }

    private static void sortChildren(List<PackageNode> nodes) {
        nodes.sort(
                Comparator.comparing(PackageNode::isPackage).reversed()
                        .thenComparing(PackageNode::getName, String.CASE_INSENSITIVE_ORDER));
    }

    public String getName() { return name; }
    public String getFullName() { return fullName; }
    public String getUri() { return uri; }
    public boolean isPackage() { return isPackage; }
    public List<PackageNode> getChildren() { return children; }
}
