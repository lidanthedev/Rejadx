package dev.rejadx.server;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SetTraceParams;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import dev.rejadx.server.client.ReJadxClient;
import dev.rejadx.server.manager.DecompilerManager;

public class ReJadxLanguageServer implements LanguageServer, LanguageClientAware {

    static final List<String> COMMANDS = List.of(
            "rejadx.loadProject",
            "rejadx.getPackages",
            "rejadx.getSource",
            "rejadx.addComment",
            "rejadx.saveProject"
    );

    private final DecompilerManager manager = new DecompilerManager();
    private final ReJadxTextDocumentService textDocumentService = new ReJadxTextDocumentService(manager);
    private final ReJadxWorkspaceService    workspaceService    = new ReJadxWorkspaceService(manager);

    @Override
    public void connect(LanguageClient client) {
        ReJadxClient typedClient = (ReJadxClient) client;
        textDocumentService.setClient(typedClient);
        workspaceService.setClient(typedClient);
        manager.connect(typedClient);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities caps = new ServerCapabilities();

        // Virtual documents are read-only, but we still need open notifications
        // to trigger async decompilation and sourceReady pushes.
        caps.setTextDocumentSync(TextDocumentSyncKind.Full);

        // Standard LSP features routed through jadx-core
        caps.setReferencesProvider(true);
        caps.setDefinitionProvider(true);
        caps.setRenameProvider(true);

        // Custom commands dispatched via workspace/executeCommand
        caps.setExecuteCommandProvider(new ExecuteCommandOptions(COMMANDS));

        return CompletableFuture.completedFuture(new InitializeResult(caps));
    }

    @Override
    public void initialized(InitializedParams params) {
        // No-op; dynamic capability registration could go here.
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        manager.shutdown();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        System.exit(0);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    @JsonRequest("jadx/addComment")
    public CompletableFuture<Object> addComment(Map<String, Object> params) {
        return workspaceService.addCommentRequest(params);
    }

    @Override
    public void setTrace(SetTraceParams params) {
        // VS Code sends this notification during client lifecycle.
        // LSP4J's LanguageServer default throws UnsupportedOperationException,
        // so we explicitly accept it as a no-op for now.
    }
}
