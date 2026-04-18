package dev.rejadx.server.config;

public class ServerSettings {
    private volatile String customArgs = "";
    private volatile boolean enableExternalPlugins = false;
    private volatile boolean enableCodeCache = true;

    public String getCustomArgs() {
        return customArgs;
    }

    public void setCustomArgs(String customArgs) {
        this.customArgs = customArgs == null ? "" : customArgs;
    }

    public boolean isEnableExternalPlugins() {
        return enableExternalPlugins;
    }

    public void setEnableExternalPlugins(boolean enableExternalPlugins) {
        this.enableExternalPlugins = enableExternalPlugins;
    }

    public boolean isEnableCodeCache() {
        return enableCodeCache;
    }

    public void setEnableCodeCache(boolean enableCodeCache) {
        this.enableCodeCache = enableCodeCache;
    }
}
