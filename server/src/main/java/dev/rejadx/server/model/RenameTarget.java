package dev.rejadx.server.model;

import jadx.api.data.IJavaCodeRef;
import jadx.api.data.IJavaNodeRef;

public record RenameTarget(IJavaNodeRef nodeRef, IJavaCodeRef codeRef, String name, String kind) {
}
