package org.pinnaclesmp.fragguard;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DurationParser {
    private static final Pattern TOKEN = Pattern.compile("^(\\d+)([dhms])$", Pattern.CASE_INSENSITIVE);
    private static final long SECOND_MILLIS = 1_000L;
    private static final long MINUTE_MILLIS = 60L * SECOND_MILLIS;
    private static final long HOUR_MILLIS = 60L * MINUTE_MILLIS;
    private static final long DAY_MILLIS = 24L * HOUR_MILLIS;

    private DurationParser() {
    }

    static long parseMillis(List<String> tokens) {
        return parseMillis(tokens, Long.MAX_VALUE);
    }

    static long parseMillis(List<String> tokens, long maximumMillis) {
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Missing time. Example: t:2d 7h 15m");
        }
        if (maximumMillis <= 0L) {
            throw new IllegalArgumentException("Maximum time must be greater than zero.");
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

            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            long multiplier = switch (unit) {
                case "d" -> DAY_MILLIS;
                case "h" -> HOUR_MILLIS;
                case "m" -> MINUTE_MILLIS;
                case "s" -> SECOND_MILLIS;
                default -> throw new IllegalStateException("Unexpected duration unit: " + unit);
            };

            try {
                long amount = Long.parseLong(matcher.group(1));
                long tokenMillis = Math.multiplyExact(amount, multiplier);
                total = Math.addExact(total, tokenMillis);
            } catch (NumberFormatException | ArithmeticException exception) {
                throw new IllegalArgumentException("Time value is too large.", exception);
            }

            if (total > maximumMillis) {
                throw new DurationLimitExceededException(maximumMillis);
            }
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

    static final class DurationLimitExceededException extends IllegalArgumentException {
        private final long maximumMillis;

        private DurationLimitExceededException(long maximumMillis) {
            super("Time exceeds the configured history retention limit.");
            this.maximumMillis = maximumMillis;
        }

        long maximumMillis() {
            return maximumMillis;
        }
    }
}
