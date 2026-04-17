package dev.rejadx.server.commands;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.rejadx.server.manager.DecompilerManager;

public class LoadProjectCommand {

    private static final Logger log = LoggerFactory.getLogger(LoadProjectCommand.class);

    private final DecompilerManager manager;

    public LoadProjectCommand(DecompilerManager manager) {
        this.manager = manager;
    }

    /** args[0]: String — absolute path to APK or DEX file */
    public CompletableFuture<Object> execute(List<Object> args) {
        if (args == null || args.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("rejadx.loadProject requires a file path argument"));
        }

        String pathStr = CommandArgs.requireString(args, 0, "rejadx.loadProject requires a file path argument");
        Path inputFile = Paths.get(pathStr);

        if (!Files.exists(inputFile)) {
            log.warn("Load rejected, file not found: {}", pathStr);
            return CompletableFuture.completedFuture(Map.of(
                    "loaded", false,
                    "error", "File not found: " + pathStr));
        }

        log.info("Loading project: {}", inputFile);
        return manager.loadProject(inputFile);
    }
}
