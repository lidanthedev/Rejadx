package dev.rejadx.server.model;

public class CommentInfo {
    private final boolean exists;
    private final String comment;
    private final String style;

    public CommentInfo(boolean exists, String comment, String style) {
        this.exists = exists;
        this.comment = comment;
        this.style = style;
    }

    public boolean isExists() {
        return exists;
    }

    public String getComment() {
        return comment;
    }

    public String getStyle() {
        return style;
    }
}
