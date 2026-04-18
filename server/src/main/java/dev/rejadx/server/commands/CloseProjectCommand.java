package dev.rejadx.server.commands;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import dev.rejadx.server.manager.DecompilerManager;

public class CloseProjectCommand {

    private final DecompilerManager manager;

    public CloseProjectCommand(DecompilerManager manager) {
        this.manager = manager;
    }

    public CompletableFuture<Object> execute(List<Object> args) {
        manager.closeProject();
        return CompletableFuture.completedFuture(Map.of(
                "closed", true,
                "status", "idle"));
    }
}
