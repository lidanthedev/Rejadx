package dev.rejadx.server.decompiler.plugins;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.loader.JadxPluginLoader;
import jadx.core.utils.files.FileUtils;
import jadx.plugins.tools.JadxPluginsTools;
import jadx.plugins.tools.data.JadxPluginMetadata;
import jadx.plugins.tools.utils.PluginFiles;

/**
 * External plugin loader with fault tolerance:
 * invalid plugin entries are skipped instead of aborting whole decompiler load.
 */
public class SafeExternalPluginsLoader implements JadxPluginLoader {

    private static final Logger log = LoggerFactory.getLogger(SafeExternalPluginsLoader.class);
    private static final String CL_PREFIX = "rejadx-plugin:";

    private final List<URLClassLoader> classLoaders = new ArrayList<>();

    @Override
    public List<JadxPlugin> load() {
        close();

        Map<String, JadxPlugin> map = new HashMap<>();
        loadFromClassLoader(map, SafeExternalPluginsLoader.class.getClassLoader());
        loadInstalledSafely(map);

        List<JadxPlugin> list = new ArrayList<>(map.values());
        list.sort(Comparator.comparing(p -> p.getClass().getSimpleName()));
        if (log.isInfoEnabled()) {
            log.info("Collected {} jadx plugins", list.size());
        }
        return list;
    }

    private void loadInstalledSafely(Map<String, JadxPlugin> map) {
        List<JadxPluginMetadata> installed;
        try {
            installed = JadxPluginsTools.getInstance().getInstalled();
        } catch (Exception e) {
            log.warn("Failed to enumerate installed external plugins: {}", e.getMessage());
            installed = List.of();
        }

        for (JadxPluginMetadata md : installed) {
            if (md == null || md.isDisabled()) {
                continue;
            }
            String relPath = md.getPath();
            if (relPath == null || relPath.isBlank()) {
                log.warn("Skipping external plugin '{}' because path is missing", md.getPluginId());
                continue;
            }
            Path pluginPath = PluginFiles.INSTALLED_DIR.resolve(relPath);
            loadFromPathSafe(map, pluginPath);
        }

        try {
            for (Path dropin : FileUtils.listFiles(PluginFiles.DROPINS_DIR)) {
                loadFromPathSafe(map, dropin);
            }
        } catch (Exception e) {
            log.warn("Failed to enumerate dropins directory: {}", e.getMessage());
        }
    }

    private void loadFromClassLoader(Map<String, JadxPlugin> map, ClassLoader classLoader) {
        ServiceLoader<JadxPlugin> loader = ServiceLoader.load(JadxPlugin.class, classLoader);
        Iterator<JadxPlugin> iterator = loader.iterator();
        while (true) {
            JadxPlugin plugin;
            try {
                if (!iterator.hasNext()) {
                    break;
                }
                plugin = iterator.next();
            } catch (ServiceConfigurationError e) {
                log.warn("Skipping invalid plugin provider from {}: {}", classLoader, e.getMessage());
                continue;
            } catch (Exception e) {
                log.warn("Skipping plugin provider from {}: {}", classLoader, e.getMessage());
                continue;
            }

            try {
                Class<? extends JadxPlugin> pluginClass = plugin.getClass();
                String clsName = pluginClass.getName();
                if (!map.containsKey(clsName) && pluginClass.getClassLoader() == classLoader) {
                    map.put(clsName, plugin);
                }
            } catch (Exception e) {
                log.warn("Skipping plugin from {}: {}", classLoader, e.getMessage());
            }
        }
    }

    private void loadFromPathSafe(Map<String, JadxPlugin> map, Path pluginPath) {
        try {
            URL[] urls;
            if (Files.isDirectory(pluginPath)) {
                urls = FileUtils.listFiles(pluginPath, file -> FileUtils.hasExtension(file, ".jar"))
                        .stream()
                        .map(SafeExternalPluginsLoader::toURL)
                        .toArray(URL[]::new);
                if (urls.length == 0) {
                    return;
                }
            } else if (Files.isRegularFile(pluginPath) && FileUtils.hasExtension(pluginPath, ".jar")) {
                urls = new URL[] { toURL(pluginPath) };
            } else {
                return;
            }

            URLClassLoader cl = new URLClassLoader(CL_PREFIX + pluginPath.getFileName(), urls,
                    SafeExternalPluginsLoader.class.getClassLoader());
            classLoaders.add(cl);
            int prevSize = map.size();
            loadFromClassLoader(map, cl);
            int loaded = map.size() - prevSize;
            if (loaded == 0) {
                log.warn("No plugins found in external path {}", pluginPath);
            }
        } catch (Exception e) {
            log.warn("Skipping external plugin path {}: {}", pluginPath, e.getMessage());
        }
    }

    private static URL toURL(Path p) {
        try {
            return p.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        for (URLClassLoader cl : classLoaders) {
            try {
                cl.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
        classLoaders.clear();
    }
}
