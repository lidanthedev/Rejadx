package dev.rejadx.server;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * Stub WorkspaceService.
 *
 * Will handle workspace/executeCommand requests in future milestones:
 *   - rejadx.openApk    → load an APK/jadx project into JadxDecompiler
 *   - rejadx.getClasses → return the class hierarchy to the ClassTreeProvider
 *   - rejadx.rename     → delegate F2 rename to Jadx's node tree + project.save()
 */
public class ReJadxWorkspaceService implements WorkspaceService {

    @SuppressWarnings("unused")
    private LanguageClient client;

    public void setClient(LanguageClient client) {
        this.client = client;
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        // TODO: react to VS Code settings changes (e.g., jadx decompilation mode)
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        // Not watching real files; VFS only.
    }
}
