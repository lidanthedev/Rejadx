package dev.rejadx.server.model;

public class SourceReadyParams {
    private final String uri;
    private final String content;
    private final String languageId;

    public SourceReadyParams(String uri, String content, String languageId) {
        this.uri = uri;
        this.content = content;
        this.languageId = languageId;
    }

    public String getUri() { return uri; }
    public String getContent() { return content; }
    public String getLanguageId() { return languageId; }
}
