package dev.rejadx.server.config;

public class ServerSettings {
    private volatile String customArgs = "";
    private volatile boolean enableExternalPlugins = false;
    private volatile boolean enableCodeCache = true;
    private volatile boolean showInconsistentCode = false;

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

    public boolean isShowInconsistentCode() {
        return showInconsistentCode;
    }

    public void setShowInconsistentCode(boolean showInconsistentCode) {
        this.showInconsistentCode = showInconsistentCode;
    }
}
