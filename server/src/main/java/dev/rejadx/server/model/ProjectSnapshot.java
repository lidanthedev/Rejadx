package dev.rejadx.server.model;

import jadx.api.data.impl.JadxCodeData;

public class ProjectSnapshot {
    private String inputFilePath;
    private JadxCodeData codeData;

    public ProjectSnapshot() {
        // for Gson deserialization
    }

    public ProjectSnapshot(String inputFilePath, JadxCodeData codeData) {
        this.inputFilePath = inputFilePath;
        this.codeData = codeData;
    }

    public String getInputFilePath() { return inputFilePath; }
    public JadxCodeData getCodeData() { return codeData; }
    public void setInputFilePath(String v) { this.inputFilePath = v; }
    public void setCodeData(JadxCodeData v) { this.codeData = v; }
}
