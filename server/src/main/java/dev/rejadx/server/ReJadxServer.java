package dev.rejadx.server;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.eclipse.lsp4j.jsonrpc.Launcher;

import dev.rejadx.server.client.ReJadxClient;

/**
 * Entry point. Communication is exclusively over stdin/stdout (stdio transport).
 * System.out is redirected to stderr BEFORE the launcher is built so that any
 * stray println() from jadx internals cannot corrupt the JSON-RPC byte stream.
 */
public class ReJadxServer {

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        InputStream  in  = System.in;
        OutputStream out = System.out;

        // Must happen before Launcher construction — any print after this goes to stderr.
        System.setOut(System.err);

        ReJadxLanguageServer server = new ReJadxLanguageServer();

        // Use Launcher.Builder so LSP4J generates a ReJadxClient proxy that includes
        // our custom @JsonNotification methods (sourceReady, telemetry).
        Launcher<ReJadxClient> launcher = new Launcher.Builder<ReJadxClient>()
                .setRemoteInterface(ReJadxClient.class)
                .setLocalService(server)
                .setInput(in)
                .setOutput(out)
                .create();

        server.connect(launcher.getRemoteProxy());

        Future<?> listening = launcher.startListening();
        listening.get(); // blocks until the client disconnects
    }
}
