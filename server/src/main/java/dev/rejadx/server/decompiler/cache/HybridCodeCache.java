package dev.rejadx.server.decompiler.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

    private static final String HEADER_PREFIX = "// rejadx-hybrid-v1:";
    private static final MessageDigest SHA_256 = initSha256();
    private static final HexFormat HEX = HexFormat.of();

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
            Files.writeString(pathFor(clsFullName), diskPayload(clsFullName, code), StandardCharsets.UTF_8);
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
                String file = Files.readString(p, StandardCharsets.UTF_8);
                String parsed = parseDiskPayload(clsFullName, file);
                if (parsed != null) {
                    strCache.put(clsFullName, parsed);
                    return parsed;
                }
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
            clearBaseDirBestEffort();
        }
    }

    private Path pathFor(String clsFullName) {
        byte[] digest = sha256(clsFullName);
        String key = HEX.formatHex(digest);
        return baseDir.resolve(key + ".java");
    }

    private static MessageDigest initSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static byte[] sha256(String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        synchronized (SHA_256) {
            SHA_256.reset();
            return SHA_256.digest(bytes);
        }
    }

    private static String diskPayload(String clsFullName, String code) {
        String marker = clsMarker(clsFullName);
        return HEADER_PREFIX + marker + "\n" + code;
    }

    private static String parseDiskPayload(String clsFullName, String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        int nl = payload.indexOf('\n');
        if (nl <= 0) {
            return null;
        }
        String header = payload.substring(0, nl).trim();
        String expected = HEADER_PREFIX + clsMarker(clsFullName);
        if (!expected.equals(header)) {
            return null;
        }
        return payload.substring(nl + 1);
    }

    private static String clsMarker(String clsFullName) {
        byte[] marker = sha256(clsFullName);
        return HEX.formatHex(marker);
    }

    private void clearBaseDirBestEffort() {
        if (!Files.exists(baseDir)) {
            return;
        }
        try {
            Files.walkFileTree(baseDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException ignored) {
                        // ignore
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (!dir.equals(baseDir)) {
                        try {
                            Files.deleteIfExists(dir);
                        } catch (IOException ignored) {
                            // ignore
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {
            // ignore
        }
    }
}
