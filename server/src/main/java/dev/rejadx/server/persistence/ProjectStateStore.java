package dev.rejadx.server.persistence;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import jadx.api.data.ICodeComment;
import jadx.api.data.ICodeRename;
import jadx.api.data.IJavaCodeRef;
import jadx.api.data.IJavaNodeRef;
import jadx.api.data.impl.JadxCodeComment;
import jadx.api.data.impl.JadxCodeData;
import jadx.api.data.impl.JadxCodeRename;
import jadx.api.data.impl.JadxNodeRef;
import jadx.core.utils.GsonUtils;

import dev.rejadx.server.model.ProjectSnapshot;

import static jadx.core.utils.GsonUtils.interfaceReplace;

/**
 * Persists project state (input file path + code data renames/comments) to a JSON sidecar file.
 * Uses the exact same Gson adapter configuration as jadx-gui's JadxProject so the format
 * is interoperable with jadx-gui project files.
 */
public final class ProjectStateStore {

    private static final Logger log = LoggerFactory.getLogger(ProjectStateStore.class);

    private static final Gson GSON = GsonUtils.defaultGsonBuilder()
            .registerTypeAdapter(ICodeComment.class, interfaceReplace(JadxCodeComment.class))
            .registerTypeAdapter(ICodeRename.class,  interfaceReplace(JadxCodeRename.class))
            .registerTypeAdapter(IJavaNodeRef.class,  interfaceReplace(JadxNodeRef.class))
            .registerTypeAdapter(IJavaCodeRef.class,  interfaceReplace(jadx.api.data.impl.JadxCodeRef.class))
            .create();

    private ProjectStateStore() {}

    public static Path defaultStateFile(Path inputFile) {
        return inputFile.resolveSibling(inputFile.getFileName().toString() + ".rejadx.json");
    }

    public static void save(Path stateFile, Path inputFile, JadxCodeData codeData) throws IOException {
        ProjectSnapshot snapshot = new ProjectSnapshot(inputFile.toAbsolutePath().toString(), codeData);
        Path tmp = stateFile.resolveSibling(stateFile.getFileName().toString() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(snapshot, w);
        }
        Files.move(tmp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        log.info("Project state saved to {}", stateFile);
    }

    public static JadxCodeData load(Path stateFile) {
        if (!Files.exists(stateFile)) {
            return new JadxCodeData();
        }
        try (Reader r = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
            ProjectSnapshot snapshot = GSON.fromJson(r, ProjectSnapshot.class);
            JadxCodeData data = snapshot != null ? snapshot.getCodeData() : null;
            return data != null ? data : new JadxCodeData();
        } catch (Exception e) {
            log.warn("Could not load project state from {}: {}", stateFile, e.getMessage());
            return new JadxCodeData();
        }
    }
}
