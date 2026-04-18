package dev.rejadx.server.manager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.data.impl.JadxCodeData;

import dev.rejadx.server.client.ReJadxClient;
import dev.rejadx.server.decompiler.IDecompilerEngine;
import dev.rejadx.server.decompiler.JadxAdapter;
import dev.rejadx.server.model.TelemetryParams;
import dev.rejadx.server.persistence.ProjectStateStore;

/**
 * Central stateful object shared by all LSP service classes.
 * Owns the read-write lock, the decompiler engine lifecycle, and the telemetry heartbeat.
 */
public class DecompilerManager {

    private static final Logger log = LoggerFactory.getLogger(DecompilerManager.class);

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ExecutorService workPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "rejadx-work");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService telemetryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rejadx-telemetry");
        t.setDaemon(true);
        return t;
    });

    private volatile IDecompilerEngine engine;
    private volatile ReJadxClient client;
    private volatile Path currentInputFile;
    private volatile Path currentCacheDir;
    private volatile String status = "idle";
    private ScheduledFuture<?> telemetryFuture;

    public ReentrantReadWriteLock getLock() {
        return lock;
    }

    public IDecompilerEngine getEngine() {
        return engine;
    }

    public ReJadxClient getClient() {
        return client;
    }

    public Path getCurrentInputFile() {
        return currentInputFile;
    }

    public void connect(ReJadxClient client) {
        this.client = client;
        telemetryFuture = telemetryScheduler.scheduleAtFixedRate(
                this::pushTelemetry, 0, 2, TimeUnit.SECONDS);
    }

    /**
     * Loads a project asynchronously. Runs the slow jadx.load() + saveSources() outside
     * the write lock, then atomically swaps the engine under a brief write lock.
     */
    public CompletableFuture<Object> loadProject(Path inputFile) {
        return CompletableFuture.supplyAsync(() -> {
            Path cacheDir = inputFile.getParent()
                    .resolve(".rejadx")
                    .resolve(inputFile.getFileName().toString() + ".cache");

            try {
                Files.createDirectories(cacheDir);
            } catch (Exception e) {
                throw new RuntimeException("Cannot create cache dir: " + e.getMessage(), e);
            }

            Path stateFile = ProjectStateStore.defaultStateFile(inputFile);
            JadxCodeData existingData = ProjectStateStore.load(stateFile);

            // Phase 1: build new engine outside any lock (this is the slow path)
            setStatus("loading");
            JadxAdapter newEngine = new JadxAdapter();
            try {
                setStatus("decompiling");
                newEngine.load(inputFile, cacheDir, existingData);
            } catch (Exception e) {
                setStatus("error");
                try { newEngine.close(); } catch (Exception ex) { /* ignore */ }
                throw new RuntimeException("Load failed: " + e.getMessage(), e);
            }

            // Phase 2: atomic swap under write lock (brief — just pointer swaps)
            lock.writeLock().lock();
            try {
                IDecompilerEngine old = engine;
                engine = newEngine;
                currentInputFile = inputFile;
                currentCacheDir = cacheDir;
                setStatus("ready");
                if (old != null) {
                    workPool.submit(() -> { try { old.close(); } catch (Exception e) { /* ignore */ } });
                }
                return (Object) Map.of("classCount", engine.getClassCount(), "loaded", true);
            } finally {
                lock.writeLock().unlock();
            }
        }, workPool);
    }

    /** Saves the current project state to disk. Call under read lock. */
    public void saveProject(Path stateFile) throws Exception {
        lock.readLock().lock();
        try {
            if (engine == null) throw new IllegalStateException("No project loaded");
            ProjectStateStore.save(stateFile, currentInputFile, engine.getCodeData());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Saves state to the default sidecar next to the current input file.
     * Caller must hold the manager lock (read or write).
     */
    public void saveCurrentProjectStateUnsafe() throws Exception {
        if (engine == null) throw new IllegalStateException("No project loaded");
        if (currentInputFile == null) throw new IllegalStateException("No input file loaded");
        Path stateFile = ProjectStateStore.defaultStateFile(currentInputFile);
        ProjectStateStore.save(stateFile, currentInputFile, engine.getCodeData());
    }

    /** Closes current project and releases decompiler resources. */
    public void closeProject() {
        lock.writeLock().lock();
        try {
            if (engine != null) {
                try {
                    engine.close();
                } catch (Exception e) {
                    log.warn("Failed to close engine: {}", e.getMessage());
                }
                engine = null;
            }
            currentInputFile = null;
            currentCacheDir = null;
            setStatus("idle");
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void shutdown() {
        if (telemetryFuture != null) telemetryFuture.cancel(false);
        telemetryScheduler.shutdown();
        workPool.shutdown();
        lock.writeLock().lock();
        try {
            if (engine != null) {
                try { engine.close(); } catch (Exception e) { /* ignore */ }
                engine = null;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void setStatus(String s) {
        status = s;
    }

    private void pushTelemetry() {
        ReJadxClient c = client;
        if (c == null) return;

        // Use tryLock so the telemetry heartbeat never blocks behind a heavy write
        if (!lock.readLock().tryLock()) return;
        int classCount;
        try {
            classCount = (engine != null) ? engine.getClassCount() : 0;
        } finally {
            lock.readLock().unlock();
        }

        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max  = rt.maxMemory();
        try {
            c.telemetry(new TelemetryParams(used, max, classCount, System.currentTimeMillis(), status));
        } catch (Exception e) {
            log.warn("Failed to push telemetry: {}", e.getMessage());
        }
    }
}
