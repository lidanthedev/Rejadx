package dev.rejadx.server.model;

public class XrefLocation {
    private final String uri;
    private final int line;
    private final int character;
    private final int length;

    public XrefLocation(String uri, int line, int character, int length) {
        this.uri = uri;
        this.line = line;
        this.character = character;
        this.length = length;
    }

    public String getUri() { return uri; }
    public int getLine() { return line; }
    public int getCharacter() { return character; }
    public int getLength() { return length; }
}
