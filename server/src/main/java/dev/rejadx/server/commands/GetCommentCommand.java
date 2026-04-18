package dev.rejadx.server.commands;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.google.gson.Gson;

import dev.rejadx.server.decompiler.IDecompilerEngine;
import dev.rejadx.server.manager.DecompilerManager;
import dev.rejadx.server.model.CommentInfo;
import dev.rejadx.server.uri.JadxUriParser;

public class GetCommentCommand {

    private static final Gson GSON = new Gson();
    private final DecompilerManager manager;

    public GetCommentCommand(DecompilerManager manager) {
        this.manager = manager;
    }

    public CompletableFuture<Object> execute(List<Object> args) {
        if (args == null || args.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("rejadx.getComment requires a params argument"));
        }

        return CompletableFuture.supplyAsync(() -> {
            Params p = GSON.fromJson(GSON.toJson(args.get(0)), Params.class);
            if (p == null || p.uri == null || p.uri.isBlank()) {
                throw new IllegalArgumentException("rejadx.getComment requires valid params");
            }

            ReentrantReadWriteLock.ReadLock rl = manager.getLock().readLock();
            rl.lock();
            try {
                IDecompilerEngine engine = manager.getEngine();
                if (engine == null) {
                    throw new IllegalStateException("No project loaded");
                }
                JadxUriParser.ParsedUri parsed = JadxUriParser.parse(p.uri);
                CommentInfo info = engine.findCommentAt(parsed.rawClassName(), p.line, p.character);
                return (Object) Map.of(
                        "exists", info.isExists(),
                        "comment", info.getComment(),
                        "style", info.getStyle());
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("getComment failed: " + e.getMessage(), e);
            } finally {
                rl.unlock();
            }
        });
    }

    private static class Params {
        String uri;
        int line;
        int character;
    }
}
