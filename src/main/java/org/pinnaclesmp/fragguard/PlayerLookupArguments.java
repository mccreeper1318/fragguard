package org.pinnaclesmp.fragguard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Strict player/global lookup syntax; area lookup keeps its existing parser. */
record PlayerLookupArguments(String playerArgument, String worldName, int page,
                             long durationMillis, String timeArgument) {
    private static final Pattern WORLD = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

    static boolean isPlayerScope(String[] args) {
        for (String arg : args) {
            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.startsWith("u:") || lower.startsWith("user:")) {
                return true;
            }
        }
        return false;
    }

    static PlayerLookupArguments parse(String[] args, int retentionDays) {
        String actor = null;
        String world = null;
        int page = 1;
        boolean sawPage = false;
        boolean sawTime = false;
        List<String> durations = new ArrayList<>();
        for (String raw : args) {
            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.startsWith("r:") || lower.startsWith("radius:")) {
                throw new IllegalArgumentException("Use either r:<radius> or u:<player>, not both.");
            }
            if (lower.startsWith("u:") || lower.startsWith("user:")) {
                if (actor != null) {
                    throw new IllegalArgumentException("Specify only one u:<player>.");
                }
                actor = raw.substring(raw.indexOf(':') + 1);
                if (actor.isBlank()) {
                    throw new IllegalArgumentException("Missing player. Example: /fg lookup u:Builder");
                }
                continue;
            }
            if (lower.startsWith("w:") || lower.startsWith("world:")) {
                if (world != null) {
                    throw new IllegalArgumentException("Specify only one w:<world>.");
                }
                world = raw.substring(raw.indexOf(':') + 1);
                if (!WORLD.matcher(world).matches()) {
                    throw new IllegalArgumentException("Invalid world name. Example: w:world_nether");
                }
                continue;
            }
            if (lower.startsWith("p:") || lower.startsWith("page:")) {
                if (sawPage) {
                    throw new IllegalArgumentException("Specify only one p:<page>.");
                }
                sawPage = true;
                String value = raw.substring(raw.indexOf(':') + 1);
                try {
                    page = Integer.parseInt(value);
                } catch (NumberFormatException error) {
                    throw new IllegalArgumentException("Invalid page. Use p:1, p:2, etc.");
                }
                if (page < 1) {
                    throw new IllegalArgumentException("Page must be at least 1.");
                }
                continue;
            }
            if (lower.startsWith("t:") || lower.startsWith("time:")) {
                if (sawTime) {
                    throw new IllegalArgumentException("Specify only one t:<duration>.");
                }
                sawTime = true;
                String value = raw.substring(raw.indexOf(':') + 1);
                if (!value.isBlank()) {
                    durations.add(value);
                }
                continue;
            }
            if (sawTime) {
                durations.add(raw);
            } else {
                throw new IllegalArgumentException("Unknown player lookup argument: " + raw);
            }
        }
        if (actor == null) {
            throw new IllegalArgumentException("Missing player. Example: /fg lookup u:Builder");
        }
        long duration = 0L;
        if (sawTime) {
            long maximum = Math.max(1L, retentionDays) * 86_400_000L;
            duration = DurationParser.parseMillis(durations, maximum);
        }
        String time = sawTime ? "t:" + String.join(" ", durations) : "";
        return new PlayerLookupArguments(actor, world, page, duration, time);
    }

    String nextPageCommand(int nextPage) {
        return "/fg lookup u:" + playerArgument
                + (worldName == null ? "" : " w:" + worldName)
                + (timeArgument.isEmpty() ? "" : " " + timeArgument)
                + " p:" + nextPage;
    }
}
