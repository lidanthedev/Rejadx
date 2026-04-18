package dev.rejadx.server.commands;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.google.gson.Gson;

import jadx.api.data.CommentStyle;

import dev.rejadx.server.decompiler.IDecompilerEngine;
import dev.rejadx.server.manager.DecompilerManager;
import dev.rejadx.server.uri.JadxUriParser;

/**
 * Handles "rejadx.addComment" execute command.
 *
 * Expected args[0]: JSON object { uri: string, line: int, character: int, comment: string, style: "LINE"|"BLOCK" }
 */
public class AddCommentCommand {

    private static final Gson GSON = new Gson();

    private final DecompilerManager manager;

    public AddCommentCommand(DecompilerManager manager) {
        this.manager = manager;
    }

    public CompletableFuture<Object> execute(List<Object> args) {
        if (args == null || args.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("rejadx.addComment requires a params argument"));
        }

        Params p = GSON.fromJson(GSON.toJson(args.get(0)), Params.class);

        return CompletableFuture.supplyAsync(() -> {
            JadxUriParser.ParsedUri parsed = JadxUriParser.parse(p.uri);

            ReentrantReadWriteLock.WriteLock wl = manager.getLock().writeLock();
            wl.lock();
            try {
                IDecompilerEngine engine = manager.getEngine();
                if (engine == null) throw new IllegalStateException("No project loaded");

                CommentStyle style = "BLOCK".equalsIgnoreCase(p.style) ? CommentStyle.BLOCK : CommentStyle.LINE;
                String newSource = engine.addCommentAt(parsed.rawClassName(), p.line, p.character, p.comment, style);

                // Persist comment immediately to sidecar state file.
                manager.saveCurrentProjectStateUnsafe();

                // Push refreshed source to client (client's sourceReady handler updates the virtual doc)
                if (manager.getClient() != null) {
                    manager.getClient().sourceReady(
                            new dev.rejadx.server.model.SourceReadyParams(p.uri, newSource, "java"));
                }

                return (Object) Map.of("applied", true);
            } catch (Exception e) {
                throw new RuntimeException("addComment failed: " + e.getMessage(), e);
            } finally {
                wl.unlock();
            }
        });
    }

    private static class Params {
        String uri;
        int line;
        int character;
        String comment;
        String style;
    }
}
