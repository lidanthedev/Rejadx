package dev.rejadx.server.model;

import jadx.api.data.IJavaNodeRef;

public class ResolvedNode {
    private final IJavaNodeRef nodeRef;
    private final String name;
    private final String kind; // "class", "method", "field"

    public ResolvedNode(IJavaNodeRef nodeRef, String name, String kind) {
        this.nodeRef = nodeRef;
        this.name = name;
        this.kind = kind;
    }

    public IJavaNodeRef getNodeRef() { return nodeRef; }
    public String getName() { return name; }
    public String getKind() { return kind; }
}
