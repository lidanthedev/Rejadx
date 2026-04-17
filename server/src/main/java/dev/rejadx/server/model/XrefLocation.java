package dev.rejadx.server.model;

public class XrefLocation {
    private final String uri;
    private final int line;
    private final int character;

    public XrefLocation(String uri, int line, int character) {
        this.uri = uri;
        this.line = line;
        this.character = character;
    }

    public String getUri() { return uri; }
    public int getLine() { return line; }
    public int getCharacter() { return character; }
}
