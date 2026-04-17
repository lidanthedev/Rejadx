package dev.rejadx.server.uri;

import java.net.URI;

import dev.rejadx.server.model.SourceType;

public final class JadxUriParser {

    public static final String SCHEME = "jadx";

    private JadxUriParser() {}

    public record ParsedUri(String rawClassName, SourceType sourceType) {}

    /**
     * Parses both URI forms:
     *   Canonical: jadx:///classes/com/app/Main?type=java (slashes → dots)
     *   Legacy:    jadx://class/com.app.Main              (already dots, no query)
     * Also accepts authority-based variants emitted by older builds:
     *   jadx://classes/com/app/Main?type=java
     */
    public static ParsedUri parse(String uriString) {
        URI uri = URI.create(uriString);
        String path = uri.getPath();  // leading slash included
        String authority = uri.getAuthority();

        String rawClassName;
        if ("classes".equals(authority)) {
            // Authority-based canonical form: jadx://classes/com/app/Main?type=java
            rawClassName = trimLeadingSlash(path).replace('/', '.');
        } else if ("class".equals(authority)) {
            // Authority-based legacy form: jadx://class/com.app.Main
            rawClassName = trimLeadingSlash(path);
        } else if (path.startsWith("/classes/")) {
            // Canonical form — path uses slashes, inner classes use $
            rawClassName = path.substring("/classes/".length()).replace('/', '.');
        } else if (path.startsWith("/class/")) {
            // Legacy form emitted by ClassTreeProvider.ts — already dot-separated
            rawClassName = path.substring("/class/".length());
        } else {
            throw new IllegalArgumentException("Unrecognized jadx URI path: " + uriString);
        }

        SourceType type = SourceType.JAVA;
        String query = uri.getQuery();
        if (query != null && query.contains("type=smali")) {
            type = SourceType.SMALI;
        }

        return new ParsedUri(rawClassName, type);
    }

    /** Builds the canonical URI form. Inner class dollar signs are preserved in the path. */
    public static String build(String rawClassName, SourceType type) {
        // Replace dots with slashes for the path component; $ is kept as-is
        String path = rawClassName.replace('.', '/');
        String typeParam = (type == SourceType.SMALI) ? "smali" : "java";
        return SCHEME + ":///classes/" + path + "?type=" + typeParam;
    }

    private static String trimLeadingSlash(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.charAt(0) == '/' ? value.substring(1) : value;
    }
}
