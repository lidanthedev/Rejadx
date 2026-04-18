package dev.rejadx.server.config;

public class ServerSettings {
    private volatile String customArgs = "";

    public String getCustomArgs() {
        return customArgs;
    }

    public void setCustomArgs(String customArgs) {
        this.customArgs = customArgs == null ? "" : customArgs;
    }
}
