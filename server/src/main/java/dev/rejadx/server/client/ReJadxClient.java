package dev.rejadx.server.client;

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.services.LanguageClient;

import dev.rejadx.server.model.SourceReadyParams;
import dev.rejadx.server.model.TelemetryParams;

public interface ReJadxClient extends LanguageClient {

    /** Server pushes fully decompiled source for a jadx:// URI after didOpen or any mutation. */
    @JsonNotification("rejadx/sourceReady")
    void sourceReady(SourceReadyParams params);

    /** 2-second heartbeat carrying JVM heap stats and decompilation status. */
    @JsonNotification("rejadx/telemetry")
    void telemetry(TelemetryParams params);
}
