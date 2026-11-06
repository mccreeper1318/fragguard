package org.pinnaclesmp.fragguard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Strict, independent rollback scope; a radius cannot be combined with a player. */
record PlayerRollbackArguments(String playerArgument, String worldName,
                               long durationMillis, boolean force) {
    static boolean isPlayerScope(String[] args) {
        for (String token : args) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.startsWith("u:") || lower.startsWith("user:")) {
                return true;
            }
        }
        return false;
    }

    static PlayerRollbackArguments parse(String[] args, int retentionDays) {
        String player = null;
        String world = null;
        boolean timeSeen = false;
        boolean force = false;
        List<String> timeTokens = new ArrayList<>();
        for (String token : args) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.startsWith("r:") || lower.startsWith("radius:")) {
                throw new IllegalArgumentException("Use r:<radius> OR u:<player>, never both.");
            }
            if (lower.startsWith("u:") || lower.startsWith("user:")) {
                if (player != null) {
                    throw new IllegalArgumentException("Specify only one u:<player>.");
                }
                player = token.substring(token.indexOf(':') + 1).trim();
                if (player.isEmpty()) {
                    throw new IllegalArgumentException("Missing player after u:.");
                }
                continue;
            }
            if (lower.startsWith("w:") || lower.startsWith("world:")) {
                if (world != null) {
                    throw new IllegalArgumentException("Specify only one w:<world>.");
                }
                world = token.substring(token.indexOf(':') + 1).trim();
                if (!world.matches("[A-Za-z0-9_.-]{1,128}")) {
                    throw new IllegalArgumentException("Invalid world name.");
                }
                continue;
            }
            if (lower.equals("force") || lower.equals("--force")) {
                force = true;
                continue;
            }
            if (lower.startsWith("t:") || lower.startsWith("time:")) {
                if (timeSeen) {
                    throw new IllegalArgumentException("Specify only one t:<duration>.");
                }
                timeSeen = true;
                String first = token.substring(token.indexOf(':') + 1);
                if (!first.isBlank()) {
                    timeTokens.add(first);
                }
                continue;
            }
            if (timeSeen) {
                if (token.contains(":")) {
                    throw new IllegalArgumentException("Unknown player rollback option: " + token);
                }
                timeTokens.add(token);
            } else {
                throw new IllegalArgumentException("Unknown player rollback argument: " + token);
            }
        }
        if (player == null) {
            throw new IllegalArgumentException("Missing u:<player>.");
        }
        if (!timeSeen || timeTokens.isEmpty()) {
            throw new IllegalArgumentException("Player rollback requires t:<duration>, such as t:1h.");
        }
        long maximum = Math.max(1, retentionDays) * 86_400_000L;
        long duration = DurationParser.parseMillis(timeTokens, maximum);
        return new PlayerRollbackArguments(player, world, duration, force);
    }
}
