package dev.rejadx.server;

import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

public class ReJadxLanguageServer implements LanguageServer, LanguageClientAware {

    private LanguageClient client;
    private final ReJadxTextDocumentService textDocumentService = new ReJadxTextDocumentService();
    private final ReJadxWorkspaceService    workspaceService    = new ReJadxWorkspaceService();

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
        textDocumentService.setClient(client);
        workspaceService.setClient(client);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities caps = new ServerCapabilities();
        caps.setTextDocumentSync(TextDocumentSyncKind.Incremental);
        // Additional capabilities (hover, go-to-def, references, rename) will be
        // added in subsequent milestones.
        return CompletableFuture.completedFuture(new InitializeResult(caps));
    }

    @Override
    public void initialized(InitializedParams params) {
        // No-op for now; could request dynamic capability registration here.
    }

    @Override
    public CompletableFuture<Object> shutdown() {
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
}
