package dev.rejadx.server;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.services.WorkspaceService;

import dev.rejadx.server.client.ReJadxClient;
import dev.rejadx.server.commands.AddCommentCommand;
import dev.rejadx.server.commands.CloseProjectCommand;
import dev.rejadx.server.commands.ExportMappingsCommand;
import dev.rejadx.server.commands.GetCommentCommand;
import dev.rejadx.server.commands.GetPackagesCommand;
import dev.rejadx.server.commands.GetSourceCommand;
import dev.rejadx.server.commands.LoadProjectCommand;
import dev.rejadx.server.commands.SaveProjectCommand;
import dev.rejadx.server.commands.SearchCodeCommand;
import dev.rejadx.server.manager.DecompilerManager;

public class ReJadxWorkspaceService implements WorkspaceService {

    private final Map<String, Function<List<Object>, CompletableFuture<Object>>> commands = new HashMap<>();

    public ReJadxWorkspaceService(DecompilerManager manager) {
        commands.put("rejadx.loadProject",  new LoadProjectCommand(manager)::execute);
        commands.put("rejadx.getPackages",  new GetPackagesCommand(manager)::execute);
        commands.put("rejadx.getSource",    new GetSourceCommand(manager)::execute);
        commands.put("rejadx.searchCode",   new SearchCodeCommand(manager)::execute);
        commands.put("rejadx.getComment",   new GetCommentCommand(manager)::execute);
        commands.put("rejadx.addComment",   new AddCommentCommand(manager)::execute);
        commands.put("rejadx.exportMappings", new ExportMappingsCommand(manager)::execute);
        commands.put("rejadx.closeProject", new CloseProjectCommand(manager)::execute);
        commands.put("rejadx.saveProject",  new SaveProjectCommand(manager)::execute);
    }

    public void setClient(ReJadxClient client) {
        // Reserved for future use (e.g., sending progress notifications)
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        var handler = commands.get(params.getCommand());
        if (handler == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unknown command: " + params.getCommand()));
        }
        return handler.apply(params.getArguments());
    }

    public CompletableFuture<Object> addCommentRequest(Map<String, Object> params) {
        var handler = commands.get("rejadx.addComment");
        if (handler == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Comment command is not registered"));
        }
        if (params == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("jadx/addComment requires params"));
        }
        return handler.apply(List.of(params));
    }

    public CompletableFuture<Object> exportMappingsRequest(Map<String, Object> params) {
        var handler = commands.get("rejadx.exportMappings");
        if (handler == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Export mappings command is not registered"));
        }
        return handler.apply(List.of(params == null ? Map.of() : params));
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        // TODO: react to VS Code settings changes (e.g., jadx decompilation threads)
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        // VFS only — no file watching.
    }
}
