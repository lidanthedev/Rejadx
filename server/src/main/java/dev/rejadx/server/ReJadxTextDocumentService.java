package dev.rejadx.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.lang.model.SourceVersion;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.rejadx.server.client.ReJadxClient;
import dev.rejadx.server.decompiler.IDecompilerEngine;
import dev.rejadx.server.manager.DecompilerManager;
import dev.rejadx.server.model.RenameTarget;
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

            String langId = switch (parsed.sourceType()) {
                case SMALI -> "smali";
                case RESOURCE -> resourceLangId(uri);
                default -> "java";
            };
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
                if (parsed.sourceType() != SourceType.JAVA) {
                    return Collections.emptyList();
                }
                String source = engine.getSource(parsed.rawClassName(), SourceType.JAVA);
                int charOffset = lineCharToOffset(source, pos.getLine(), pos.getCharacter());

                boolean includeDecl = params.getContext() != null && params.getContext().isIncludeDeclaration();
                List<XrefLocation> xrefs = engine.getReferences(parsed.rawClassName(), charOffset, includeDecl);

                List<Location> locations = new ArrayList<>();
                for (XrefLocation xref : xrefs) {
                    Position start = new Position(xref.getLine(), xref.getCharacter());
                    Position end   = new Position(xref.getLine(), xref.getCharacter() + xref.getLength());
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

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        String uri = params.getTextDocument().getUri();
        Position pos = params.getPosition();

        return CompletableFuture.supplyAsync(() -> {
            ReentrantReadWriteLock.ReadLock rl = manager.getLock().readLock();
            rl.lock();
            try {
                IDecompilerEngine engine = manager.getEngine();
                if (engine == null) return Either.forLeft(Collections.emptyList());

                JadxUriParser.ParsedUri parsed = JadxUriParser.parse(uri);
                if (parsed.sourceType() == SourceType.RESOURCE) {
                    return definitionFromManifestResource(uri, pos, engine);
                }
                if (parsed.sourceType() != SourceType.JAVA) {
                    return Either.forLeft(Collections.emptyList());
                }
                String source = engine.getSource(parsed.rawClassName(), SourceType.JAVA);
                int charOffset = lineCharToOffset(source, pos.getLine(), pos.getCharacter());
                XrefLocation def = engine.getDefinition(parsed.rawClassName(), charOffset);
                if (def == null) return Either.forLeft(Collections.emptyList());

                Position start = new Position(def.getLine(), def.getCharacter());
                Position end = new Position(def.getLine(), def.getCharacter() + def.getLength());
                return Either.forLeft(List.of(new Location(def.getUri(), new Range(start, end))));
            } catch (Exception e) {
                log.warn("definition failed for {}: {}", uri, e.getMessage());
                return Either.forLeft(Collections.emptyList());
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
                if (parsed.sourceType() != SourceType.JAVA) {
                    return new WorkspaceEdit();
                }
                String oldSource = engine.getSource(parsed.rawClassName(), SourceType.JAVA);
                int charOffset = lineCharToOffset(oldSource, pos.getLine(), pos.getCharacter());

                RenameTarget target = engine.resolveRenameTargetAt(parsed.rawClassName(), charOffset);
                if (target == null) {
                    throw new IllegalArgumentException("No renameable symbol at position " + pos);
                }

                if (!newName.isEmpty() && !isValidIdentifier(newName)) {
                    throw new IllegalArgumentException("Invalid identifier: " + newName);
                }

                // Capture old source range for the WorkspaceEdit before reload
                String[] oldLines = oldSource.split("\n", -1);
                int lastLine = oldLines.length - 1;
                int lastChar = oldLines[lastLine].length();
                Range fullDocRange = new Range(new Position(0, 0), new Position(lastLine, lastChar));

                // Apply rename (reloads affected classes internally)
                String newSource = engine.applyRename(target.nodeRef(), target.codeRef(), newName);

                // Persist rename immediately to sidecar state file.
                manager.saveCurrentProjectStateUnsafe();

                // Build WorkspaceEdit replacing the full virtual document content
                WorkspaceEdit edit = new WorkspaceEdit();
                edit.setChanges(Map.of(uri, List.of(new TextEdit(fullDocRange, newSource))));

                // Also push sourceReady so the client's TextDocumentContentProvider cache updates
                pushSource(uri, newSource, "java");

                return edit;
            } catch (Exception e) {
                log.warn("rename failed for {} (line={}, char={}, newName='{}'): {}",
                        uri, pos.getLine(), pos.getCharacter(), newName, e.getMessage());
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

    private static boolean isValidIdentifier(String name) {
        return name != null
                && SourceVersion.isIdentifier(name)
                && !SourceVersion.isKeyword(name);
    }

    private static String resourceLangId(String uri) {
        String lower = uri.toLowerCase();
        if (lower.endsWith(".xml")) {
            return "xml";
        }
        if (lower.endsWith(".json")) {
            return "json";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "html";
        }
        return "plaintext";
    }

    private Either<List<? extends Location>, List<? extends LocationLink>> definitionFromManifestResource(
            String uri,
            Position pos,
            IDecompilerEngine engine
    ) {
        try {
            String source = engine.getSource(JadxUriParser.parse(uri).rawClassName(), SourceType.RESOURCE);
            int charOffset = lineCharToOffset(source, pos.getLine(), pos.getCharacter());
            String token = extractXmlClassTokenAt(source, charOffset);
            if (token == null || token.isEmpty()) {
                return Either.forLeft(Collections.emptyList());
            }

            String normalized = normalizeManifestClassName(token, source);
            if (normalized == null || normalized.isEmpty()) {
                return Either.forLeft(Collections.emptyList());
            }

            XrefLocation def = engine.getClassDefinitionByName(normalized);
            if (def == null) {
                return Either.forLeft(Collections.emptyList());
            }
            Position start = new Position(def.getLine(), def.getCharacter());
            Position end = new Position(def.getLine(), def.getCharacter() + def.getLength());
            return Either.forLeft(List.of(new Location(def.getUri(), new Range(start, end))));
        } catch (Exception e) {
            return Either.forLeft(Collections.emptyList());
        }
    }

    private static String extractXmlClassTokenAt(String source, int offset) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        int len = source.length();
        int pos = Math.max(0, Math.min(offset, len - 1));
        if (!isXmlTokenChar(source.charAt(pos)) && pos > 0 && isXmlTokenChar(source.charAt(pos - 1))) {
            pos -= 1;
        }
        if (!isXmlTokenChar(source.charAt(pos))) {
            return null;
        }

        int start = pos;
        while (start > 0 && isXmlTokenChar(source.charAt(start - 1))) {
            start--;
        }
        int end = pos + 1;
        while (end < len && isXmlTokenChar(source.charAt(end))) {
            end++;
        }

        String token = source.substring(start, end);
        if (!token.contains(".") && !token.startsWith(".")) {
            return null;
        }
        return token;
    }

    private static boolean isXmlTokenChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '$';
    }

    private static String normalizeManifestClassName(String token, String source) {
        if (token.startsWith(".")) {
            String pkg = extractManifestPackage(source);
            if (pkg == null || pkg.isEmpty()) {
                return null;
            }
            return pkg + token;
        }
        return token;
    }

    private static String extractManifestPackage(String source) {
        String marker = "package=\"";
        int idx = source.indexOf(marker);
        if (idx < 0) {
            marker = "package='";
            idx = source.indexOf(marker);
            if (idx < 0) return null;
        }
        int start = idx + marker.length();
        char quote = marker.endsWith("\"") ? '"' : '\'';
        int end = source.indexOf(quote, start);
        if (end < 0) {
            return null;
        }
        return source.substring(start, end);
    }
}
