package dev.rejadx.server.commands;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

import jadx.api.data.CommentStyle;
import jadx.api.data.impl.JadxCodeComment;
import jadx.api.data.impl.JadxNodeRef;

import dev.rejadx.server.decompiler.IDecompilerEngine;
import dev.rejadx.server.manager.DecompilerManager;
import dev.rejadx.server.model.ResolvedNode;
import dev.rejadx.server.model.SourceType;
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

                // Convert LSP line+character to char offset
                String existingSource = engine.getSource(parsed.rawClassName(), SourceType.JAVA);
                int charOffset = lineCharToOffset(existingSource, p.line, p.character);

                // Resolve the node at this position
                ResolvedNode resolved = engine.resolveNodeAt(parsed.rawClassName(), charOffset);
                if (resolved == null) {
                    throw new IllegalArgumentException("No renameable node at position (" + p.line + "," + p.character + ")");
                }

                CommentStyle style = "BLOCK".equalsIgnoreCase(p.style) ? CommentStyle.BLOCK : CommentStyle.LINE;
                JadxCodeComment comment = new JadxCodeComment(
                        (JadxNodeRef) resolved.getNodeRef(), p.comment, style);

                String newSource = engine.applyComment(comment);

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

    private static int lineCharToOffset(String source, int line, int character) {
        int currentLine = 0;
        for (int i = 0; i < source.length(); i++) {
            if (currentLine == line) return i + character;
            if (source.charAt(i) == '\n') currentLine++;
        }
        return source.length();
    }

    private static class Params {
        String uri;
        int line;
        int character;
        String comment;
        String style;
    }
}
