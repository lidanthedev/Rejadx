package dev.rejadx.server.decompiler.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jadx.api.ICodeCache;
import jadx.api.ICodeInfo;

/**
 * Hybrid cache used by ReJadx server:
 * - keeps full code metadata in the backing in-memory cache
 * - persists plain code strings to disk for warm reads
 */
public class HybridCodeCache implements ICodeCache {

    private final ICodeCache backCache;
    private final Path baseDir;
    private final Map<String, String> strCache = new ConcurrentHashMap<>();

    public HybridCodeCache(ICodeCache backCache, Path baseDir) {
        this.backCache = backCache;
        this.baseDir = baseDir;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            // Ignore; cache will continue in memory-only mode.
        }
    }

    @Override
    public void add(String clsFullName, ICodeInfo codeInfo) {
        backCache.add(clsFullName, codeInfo);
        String code = codeInfo.getCodeStr();
        strCache.put(clsFullName, code);
        try {
            Files.createDirectories(baseDir);
            Files.writeString(pathFor(clsFullName), code, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // Best effort disk layer.
        }
    }

    @Override
    public void remove(String clsFullName) {
        backCache.remove(clsFullName);
        strCache.remove(clsFullName);
        try {
            Files.deleteIfExists(pathFor(clsFullName));
        } catch (Exception ignored) {
            // Best effort disk layer.
        }
    }

    @Override
    public ICodeInfo get(String clsFullName) {
        // Keep metadata-safe reads delegated to back cache.
        return backCache.get(clsFullName);
    }

    @Override
    public String getCode(String clsFullName) {
        String code = strCache.get(clsFullName);
        if (code != null) {
            return code;
        }

        code = backCache.getCode(clsFullName);
        if (code != null) {
            strCache.put(clsFullName, code);
            return code;
        }

        try {
            Path p = pathFor(clsFullName);
            if (Files.exists(p)) {
                code = Files.readString(p, StandardCharsets.UTF_8);
                strCache.put(clsFullName, code);
                return code;
            }
        } catch (Exception ignored) {
            // Best effort disk layer.
        }
        return null;
    }

    @Override
    public boolean contains(String clsFullName) {
        // Metadata availability must follow back cache semantics.
        return backCache.contains(clsFullName);
    }

    @Override
    public void close() throws IOException {
        try {
            backCache.close();
        } finally {
            strCache.clear();
        }
    }

    private Path pathFor(String clsFullName) {
        String key = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(clsFullName.getBytes(StandardCharsets.UTF_8));
        return baseDir.resolve(key + ".java");
    }
}
