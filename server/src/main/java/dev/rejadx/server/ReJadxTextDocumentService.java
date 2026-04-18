package dev.rejadx.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.rejadx.server.client.ReJadxClient;
import dev.rejadx.server.decompiler.IDecompilerEngine;
import dev.rejadx.server.manager.DecompilerManager;
import dev.rejadx.server.model.ResolvedNode;
import dev.rejadx.server.model.SourceReadyParams;
import dev.rejadx.server.model.SourceType;
import dev.rejadx.server.model.XrefLocation;
import dev.rejadx.server.uri.JadxUriParser;

public class ReJadxTextDocumentService implements TextDocumentService {

    private static final Logger log = LoggerFactory.getLogger(ReJadxTextDocumentService.class);

    private final DecompilerManager manager;
    private volatile ReJadxClient client;

    public ReJadxTextDocumentService(DecompilerManager manager) {
        this.manager = manager;
    }

    public void setClient(ReJadxClient client) {
        this.client = client;
    }

    // ---------- didOpen -------------------------------------------------------

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        if (!uri.startsWith(JadxUriParser.SCHEME + "://")) return;

        // Run asynchronously — decompilation can be slow on first access
        CompletableFuture.runAsync(() -> {
            ReentrantReadWriteLock.ReadLock rl = manager.getLock().readLock();
            rl.lock();
            String source;
            JadxUriParser.ParsedUri parsed;
            try {
                IDecompilerEngine engine = manager.getEngine();
                if (engine == null) return; // no project loaded yet
                parsed = JadxUriParser.parse(uri);
                source = engine.getSource(parsed.rawClassName(), parsed.sourceType());
            } catch (Exception e) {
                log.warn("didOpen failed for {}: {}", uri, e.getMessage());
                pushSource(uri, "// Failed to load source: " + e.getMessage(), "java");
                return;
            } finally {
                rl.unlock();
            }

            String langId = (parsed.sourceType() == SourceType.SMALI) ? "smali" : "java";
            pushSource(uri, source, langId);
        });
    }

    // ---------- references ----------------------------------------------------

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        String uri = params.getTextDocument().getUri();
        Position pos = params.getPosition();

        return CompletableFuture.supplyAsync(() -> {
            ReentrantReadWriteLock.ReadLock rl = manager.getLock().readLock();
            rl.lock();
            try {
                IDecompilerEngine engine = manager.getEngine();
                if (engine == null) return Collections.emptyList();

                JadxUriParser.ParsedUri parsed = JadxUriParser.parse(uri);
                String source = engine.getSource(parsed.rawClassName(), SourceType.JAVA);
                int charOffset = lineCharToOffset(source, pos.getLine(), pos.getCharacter());

                List<XrefLocation> xrefs = engine.getReferences(parsed.rawClassName(), charOffset);

                List<Location> locations = new ArrayList<>();
                for (XrefLocation xref : xrefs) {
                    Position start = new Position(xref.getLine(), xref.getCharacter());
                    Position end   = new Position(xref.getLine(), xref.getCharacter() + 1);
                    locations.add(new Location(xref.getUri(), new Range(start, end)));
                }
                return locations;
            } catch (Exception e) {
                log.warn("references failed for {}: {}", uri, e.getMessage());
                return Collections.emptyList();
            } finally {
                rl.unlock();
            }
        });
    }

    // ---------- rename --------------------------------------------------------

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        String uri = params.getTextDocument().getUri();
        Position pos = params.getPosition();
        String newName = params.getNewName();

        return CompletableFuture.supplyAsync(() -> {
            ReentrantReadWriteLock.WriteLock wl = manager.getLock().writeLock();
            wl.lock();
            try {
                IDecompilerEngine engine = manager.getEngine();
                if (engine == null) throw new IllegalStateException("No project loaded");

                JadxUriParser.ParsedUri parsed = JadxUriParser.parse(uri);
                String oldSource = engine.getSource(parsed.rawClassName(), SourceType.JAVA);
                int charOffset = lineCharToOffset(oldSource, pos.getLine(), pos.getCharacter());

                ResolvedNode resolved = engine.resolveNodeAt(parsed.rawClassName(), charOffset);
                if (resolved == null) {
                    throw new IllegalArgumentException("No renameable symbol at position " + pos);
                }

                // Capture old source range for the WorkspaceEdit before reload
                String[] oldLines = oldSource.split("\n", -1);
                int lastLine = oldLines.length - 1;
                int lastChar = oldLines[lastLine].length();
                Range fullDocRange = new Range(new Position(0, 0), new Position(lastLine, lastChar));

                // Apply rename (reloads affected classes internally)
                String newSource = engine.applyRename(resolved.getNodeRef(), newName);

                // Persist rename immediately to sidecar state file.
                manager.saveCurrentProjectStateUnsafe();

                // Build WorkspaceEdit replacing the full virtual document content
                WorkspaceEdit edit = new WorkspaceEdit();
                edit.setChanges(Map.of(uri, List.of(new TextEdit(fullDocRange, newSource))));

                // Also push sourceReady so the client's TextDocumentContentProvider cache updates
                pushSource(uri, newSource, "java");

                return edit;
            } catch (Exception e) {
                log.warn("rename failed for {}: {}", uri, e.getMessage());
                return new WorkspaceEdit();
            } finally {
                wl.unlock();
            }
        });
    }

    // ---------- no-ops --------------------------------------------------------

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        // Virtual documents are read-only; edits from the client are ignored.
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {}

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        // Virtual documents cannot be saved to disk.
    }

    // ---------- helpers -------------------------------------------------------

    private void pushSource(String uri, String content, String langId) {
        ReJadxClient c = client;
        if (c != null) {
            try {
                c.sourceReady(new SourceReadyParams(uri, content, langId));
            } catch (Exception e) {
                log.warn("sourceReady push failed for {}: {}", uri, e.getMessage());
            }
        }
    }

    private static int lineCharToOffset(String source, int line, int character) {
        int currentLine = 0;
        for (int i = 0; i < source.length(); i++) {
            if (currentLine == line) return i + character;
            if (source.charAt(i) == '\n') currentLine++;
        }
        return source.length();
    }
}
