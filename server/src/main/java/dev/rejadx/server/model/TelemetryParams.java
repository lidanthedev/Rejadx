package dev.rejadx.server.model;

public class TelemetryParams {
    private final long heapUsedBytes;
    private final long heapMaxBytes;
    private final int classCount;
    private final long timestampMs;
    private final String status;

    public TelemetryParams(long heapUsedBytes, long heapMaxBytes, int classCount, long timestampMs, String status) {
        this.heapUsedBytes = heapUsedBytes;
        this.heapMaxBytes = heapMaxBytes;
        this.classCount = classCount;
        this.timestampMs = timestampMs;
        this.status = status;
    }

    public long getHeapUsedBytes() { return heapUsedBytes; }
    public long getHeapMaxBytes() { return heapMaxBytes; }
    public int getClassCount() { return classCount; }
    public long getTimestampMs() { return timestampMs; }
    public String getStatus() { return status; }
}
