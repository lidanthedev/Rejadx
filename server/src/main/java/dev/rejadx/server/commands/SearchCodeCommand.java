package dev.rejadx.server.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.google.gson.Gson;

import dev.rejadx.server.decompiler.IDecompilerEngine;
import dev.rejadx.server.manager.DecompilerManager;
import dev.rejadx.server.model.XrefLocation;

public class SearchCodeCommand {

    private static final Gson GSON = new Gson();
    private static final int DEFAULT_LIMIT = 500;

    private final DecompilerManager manager;

    public SearchCodeCommand(DecompilerManager manager) {
        this.manager = manager;
    }

    public CompletableFuture<Object> execute(List<Object> args) {
        if (args == null || args.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("rejadx.searchCode requires params"));
        }

        Params p = GSON.fromJson(GSON.toJson(args.get(0)), Params.class);
        if (p.query == null || p.query.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        return CompletableFuture.supplyAsync(() -> {
            ReentrantReadWriteLock.ReadLock rl = manager.getLock().readLock();
            rl.lock();
            try {
                IDecompilerEngine engine = manager.getEngine();
                if (engine == null) {
                    throw new IllegalStateException("No project loaded");
                }

                int limit = p.maxResults > 0 ? p.maxResults : DEFAULT_LIMIT;
                List<XrefLocation> matches = engine.searchCode(p.query, p.caseSensitive, p.regex, limit);
                return (Object) new ArrayList<>(matches);
            } finally {
                rl.unlock();
            }
        });
    }

    private static class Params {
        String query;
        boolean caseSensitive;
        boolean regex;
        int maxResults;
    }
}
