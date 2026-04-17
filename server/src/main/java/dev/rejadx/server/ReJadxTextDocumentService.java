package dev.rejadx.server;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

/**
 * Stub TextDocumentService.
 *
 * The ReJadx architecture never writes .java/.smali files to disk.
 * Class source is served exclusively through virtual jadx:// URIs pushed
 * back to the client via custom notifications (future milestone).
 */
public class ReJadxTextDocumentService implements TextDocumentService {

    @SuppressWarnings("unused")
    private LanguageClient client;

    public void setClient(LanguageClient client) {
        this.client = client;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        // TODO: decompile the class at params.getTextDocument().getUri() using
        // JadxDecompiler and push content back via a custom LSP notification.
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        // Virtual documents are read-only; client-side changes are ignored.
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        // No resources to release yet.
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        // Virtual documents cannot be saved to disk — silently ignore.
    }
}
