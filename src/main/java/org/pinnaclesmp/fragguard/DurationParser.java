package org.pinnaclesmp.fragguard;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DurationParser {
    private static final Pattern TOKEN = Pattern.compile("^(\\d+)([dhms])$", Pattern.CASE_INSENSITIVE);

    private DurationParser() {
    }

    static long parseMillis(List<String> tokens) {
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Missing time. Example: t:2d 7h 15m");
        }

        long total = 0L;
        for (String rawToken : tokens) {
            String token = rawToken.trim().toLowerCase(Locale.ROOT);
            if (token.isBlank()) {
                continue;
            }

            Matcher matcher = TOKEN.matcher(token);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Invalid time token '" + rawToken + "'. Use d, h, m, or s. Example: 2d 7h 15m");
            }

            long amount = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            total += switch (unit) {
                case "d" -> amount * 24L * 60L * 60L * 1000L;
                case "h" -> amount * 60L * 60L * 1000L;
                case "m" -> amount * 60L * 1000L;
                case "s" -> amount * 1000L;
                default -> throw new IllegalStateException("Unexpected duration unit: " + unit);
            };
        }

        if (total <= 0) {
            throw new IllegalArgumentException("Time must be greater than zero.");
        }
        return total;
    }

    static String compactAge(long millis) {
        if (millis < 0) {
            millis = 0;
        }

        long seconds = millis / 1000L;
        long days = seconds / 86_400L;
        seconds %= 86_400L;
        long hours = seconds / 3_600L;
        seconds %= 3_600L;
        long minutes = seconds / 60L;
        seconds %= 60L;

        if (days > 0) {
            return days + "d " + hours + "h ago";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m ago";
        }
        if (minutes > 0) {
            return minutes + "m ago";
        }
        return seconds + "s ago";
    }
}
