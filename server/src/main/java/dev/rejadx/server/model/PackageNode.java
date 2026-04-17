package dev.rejadx.server.model;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import jadx.api.JavaClass;
import jadx.api.JavaPackage;

import dev.rejadx.server.uri.JadxUriParser;

public class PackageNode {
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
        for (JavaClass cls : pkg.getClasses()) {
            children.add(fromJadxClass(cls));
        }
        return new PackageNode(pkg.getName(), pkg.getFullName(), null, true, children);
    }

    private static PackageNode fromJadxClass(JavaClass cls) {
        List<PackageNode> innerChildren = cls.getInnerClasses().stream()
                .map(PackageNode::fromJadxClass)
                .collect(Collectors.toList());
        String classUri = JadxUriParser.build(cls.getRawName(), SourceType.JAVA);
        return new PackageNode(cls.getName(), cls.getFullName(), classUri, false, innerChildren);
    }

    public String getName() { return name; }
    public String getFullName() { return fullName; }
    public String getUri() { return uri; }
    public boolean isPackage() { return isPackage; }
    public List<PackageNode> getChildren() { return children; }
}
