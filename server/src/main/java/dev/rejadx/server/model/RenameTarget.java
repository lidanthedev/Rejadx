package dev.rejadx.server.model;

import jadx.api.data.IJavaCodeRef;
import jadx.api.data.IJavaNodeRef;

public class RenameTarget {
    private final IJavaNodeRef nodeRef;
    private final IJavaCodeRef codeRef;
    private final String name;
    private final String kind;

    public RenameTarget(IJavaNodeRef nodeRef, IJavaCodeRef codeRef, String name, String kind) {
        this.nodeRef = nodeRef;
        this.codeRef = codeRef;
        this.name = name;
        this.kind = kind;
    }

    public IJavaNodeRef getNodeRef() {
        return nodeRef;
    }

    public IJavaCodeRef getCodeRef() {
        return codeRef;
    }

    public String getName() {
        return name;
    }

    public String getKind() {
        return kind;
    }
}
