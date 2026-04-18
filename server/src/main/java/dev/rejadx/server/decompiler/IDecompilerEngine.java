package dev.rejadx.server.decompiler;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.List;

import jadx.api.data.CommentStyle;
import jadx.api.data.ICodeComment;
import jadx.api.data.IJavaNodeRef;
import jadx.api.data.impl.JadxCodeData;

import dev.rejadx.server.model.PackageNode;
import dev.rejadx.server.model.ResolvedNode;
import dev.rejadx.server.model.SourceType;
import dev.rejadx.server.model.XrefLocation;

public interface IDecompilerEngine extends Closeable {

    /**
     * Loads the APK/DEX file, restores existing code data (renames + comments),
     * then eagerly decompiles all classes into cacheDir and into in-memory cache.
     */
    void load(Path inputFile, Path cacheDir, JadxCodeData existingData) throws Exception;

    // --- Navigation ---

    List<PackageNode> getPackageTree();

    int getClassCount();

    // --- Source retrieval ---

    String getSource(String rawClassName, SourceType type) throws ClassNotFoundException;

    // --- Xrefs ---

    List<XrefLocation> getReferences(String rawClassName, int charOffset, boolean includeDeclaration) throws ClassNotFoundException;

    XrefLocation getDefinition(String rawClassName, int charOffset) throws ClassNotFoundException;

    // --- Node resolution (for rename + comment) ---

    ResolvedNode resolveNodeAt(String rawClassName, int charOffset) throws ClassNotFoundException;

    // --- Mutations ---

    /**
     * Applies a rename to the live code data and reloads the affected class + its immediate callers.
     * Returns the new source of the directly renamed class for pushing to the client.
     */
    String applyRename(IJavaNodeRef nodeRef, String newName) throws Exception;

    /**
     * Injects a comment into the live code data and reloads the owning class.
     * Returns the new source of that class.
     */
    String applyComment(ICodeComment comment) throws Exception;

    /**
     * Creates and applies a comment at the given source position.
     * Implementation may attach the comment either to an instruction or a node declaration.
     */
    String addCommentAt(String rawClassName, int line, int character, String comment, CommentStyle style) throws Exception;

    // --- State ---

    JadxCodeData getCodeData();

    @Override
    void close();
}
