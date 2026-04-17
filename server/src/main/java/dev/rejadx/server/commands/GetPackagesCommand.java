package dev.rejadx.server.commands;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import dev.rejadx.server.decompiler.IDecompilerEngine;
import dev.rejadx.server.manager.DecompilerManager;

public class GetPackagesCommand {

    private final DecompilerManager manager;

    public GetPackagesCommand(DecompilerManager manager) {
        this.manager = manager;
    }

    /** Returns a List<PackageNode> (Gson-serializable tree of packages and classes). */
    public CompletableFuture<Object> execute(List<Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            ReentrantReadWriteLock.ReadLock rl = manager.getLock().readLock();
            rl.lock();
            try {
                IDecompilerEngine engine = manager.getEngine();
                if (engine == null) {
                    throw new IllegalStateException("No project loaded. Call rejadx.loadProject first.");
                }
                return (Object) engine.getPackageTree();
            } finally {
                rl.unlock();
            }
        });
    }
}
