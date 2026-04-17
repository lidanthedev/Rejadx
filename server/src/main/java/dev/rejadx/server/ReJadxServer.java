package dev.rejadx.server;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Entry point. Communication is exclusively over stdin/stdout so that
 * vscode-languageclient can attach via stdio transport.
 *
 * System.out is redirected to stderr BEFORE the launcher is built so that any
 * stray println() from jadx internals cannot corrupt the JSON-RPC byte stream.
 */
public class ReJadxServer {

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        InputStream  in  = System.in;
        OutputStream out = System.out;

        // Redirect stdout so accidental prints do not corrupt the JSON-RPC stream.
        System.setOut(System.err);

        ReJadxLanguageServer server = new ReJadxLanguageServer();

        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, in, out);

        server.connect(launcher.getRemoteProxy());

        Future<?> listening = launcher.startListening();
        listening.get(); // blocks until the client disconnects
    }
}
