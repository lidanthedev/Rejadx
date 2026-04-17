package dev.rejadx.server.commands;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import dev.rejadx.server.decompiler.IDecompilerEngine;
import dev.rejadx.server.manager.DecompilerManager;
import dev.rejadx.server.model.SourceType;
import dev.rejadx.server.uri.JadxUriParser;

public class GetSourceCommand {

    private final DecompilerManager manager;

    public GetSourceCommand(DecompilerManager manager) {
        this.manager = manager;
    }

    /** args[0]: String — jadx:// URI. Returns {uri, content}. */
    public CompletableFuture<Object> execute(List<Object> args) {
        if (args == null || args.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("rejadx.getSource requires a URI argument"));
        }

        String uri = CommandArgs.requireString(args, 0, "rejadx.getSource requires a URI argument");

        return CompletableFuture.supplyAsync(() -> {
            JadxUriParser.ParsedUri parsed = JadxUriParser.parse(uri);

            ReentrantReadWriteLock.ReadLock rl = manager.getLock().readLock();
            rl.lock();
            try {
                IDecompilerEngine engine = manager.getEngine();
                if (engine == null) throw new IllegalStateException("No project loaded");
                String content = engine.getSource(parsed.rawClassName(), parsed.sourceType());
                return (Object) Map.of("uri", uri, "content", content);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("Class not found: " + parsed.rawClassName(), e);
            } finally {
                rl.unlock();
            }
        });
    }
}
