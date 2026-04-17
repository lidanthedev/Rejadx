package dev.rejadx.server.commands;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.rejadx.server.manager.DecompilerManager;
import dev.rejadx.server.persistence.ProjectStateStore;

public class SaveProjectCommand {

    private static final Logger log = LoggerFactory.getLogger(SaveProjectCommand.class);

    private final DecompilerManager manager;

    public SaveProjectCommand(DecompilerManager manager) {
        this.manager = manager;
    }

    /** args[0]: optional String — explicit state file path; defaults to sidecar next to APK. */
    public CompletableFuture<Object> execute(List<Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path currentInput = manager.getCurrentInputFile();
                if (currentInput == null) throw new IllegalStateException("No project loaded");

                String explicitPath = CommandArgs.optionalString(args, 0);
                Path stateFile = (explicitPath != null)
                        ? java.nio.file.Paths.get(explicitPath)
                        : ProjectStateStore.defaultStateFile(currentInput);

                manager.saveProject(stateFile);
                log.info("Project saved to {}", stateFile);
                return (Object) Map.of("saved", true, "path", stateFile.toString());
            } catch (Exception e) {
                throw new RuntimeException("saveProject failed: " + e.getMessage(), e);
            }
        });
    }
}
