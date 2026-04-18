package dev.rejadx.server.commands;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.Gson;

import dev.rejadx.server.manager.DecompilerManager;

public class SetSettingsCommand {

    private static final Gson GSON = new Gson();
    private final DecompilerManager manager;

    public SetSettingsCommand(DecompilerManager manager) {
        this.manager = manager;
    }

    /**
     * Apply server settings; changes take effect on next JadxAdapter.load() call.
     */
    public CompletableFuture<Object> execute(List<Object> args) {
        Params p = (args != null && !args.isEmpty() && args.get(0) != null)
                ? GSON.fromJson(GSON.toJson(args.get(0)), Params.class)
                : new Params();
        if (p == null) {
            p = new Params();
        }

        manager.getSettings().setCustomArgs(p.customArgs);
        manager.getSettings().setEnableExternalPlugins(p.enableExternalPlugins);
        manager.getSettings().setEnableCodeCache(p.enableCodeCache);
        manager.getSettings().setShowInconsistentCode(p.showInconsistentCode);
        return CompletableFuture.completedFuture(Map.of("applied", true));
    }

    private static class Params {
        String customArgs = "";
        boolean enableExternalPlugins = false;
        boolean enableCodeCache = true;
        boolean showInconsistentCode = false;
    }
}
