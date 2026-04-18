package dev.rejadx.server.commands;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import dev.rejadx.server.manager.DecompilerManager;

public class ResetCodeCacheCommand {

    private final DecompilerManager manager;

    public ResetCodeCacheCommand(DecompilerManager manager) {
        this.manager = manager;
    }

    public CompletableFuture<Object> execute(List<Object> args) {
        return manager.resetCodeCache().exceptionally(e -> Map.of(
                "reset", false,
                "error", e.getMessage() == null ? "Cache reset failed" : e.getMessage()));
    }
}
