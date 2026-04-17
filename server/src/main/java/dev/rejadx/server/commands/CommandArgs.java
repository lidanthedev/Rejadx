package dev.rejadx.server.commands;

import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

final class CommandArgs {

    private CommandArgs() {
    }

    static String requireString(List<Object> args, int index, String errorMessage) {
        if (args == null || args.size() <= index || args.get(index) == null) {
            throw new IllegalArgumentException(errorMessage);
        }
        return asString(args.get(index));
    }

    static String optionalString(List<Object> args, int index) {
        if (args == null || args.size() <= index || args.get(index) == null) {
            return null;
        }
        return asString(args.get(index));
    }

    private static String asString(Object value) {
        if (value instanceof String s) {
            return stripWrappingQuotes(s.trim());
        }

        String text = value.toString().trim();
        if (text.isEmpty()) {
            return text;
        }

        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed.isJsonPrimitive() && parsed.getAsJsonPrimitive().isString()) {
                return stripWrappingQuotes(parsed.getAsString().trim());
            }
        } catch (Exception ignored) {
            // Not a JSON string literal; use the raw text.
        }
        return stripWrappingQuotes(text);
    }

    private static String stripWrappingQuotes(String text) {
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return text.substring(1, text.length() - 1);
            }
        }
        return text;
    }
}
