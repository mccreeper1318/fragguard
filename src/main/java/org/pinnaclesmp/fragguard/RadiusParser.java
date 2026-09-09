package org.pinnaclesmp.fragguard;

import java.util.Locale;
import java.util.OptionalInt;

final class RadiusParser {
    private RadiusParser() {
    }

    static OptionalInt parse(String[] args) {
        for (String arg : args) {
            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.startsWith("r:") || lower.startsWith("radius:")) {
                String value = arg.substring(arg.indexOf(':') + 1);
                try {
                    return OptionalInt.of(Integer.parseInt(value));
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException(
                            "Invalid radius '" + value + "'. Enter a whole number.", exception);
                }
            }
        }
        return OptionalInt.empty();
    }
}
