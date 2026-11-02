package org.pinnaclesmp.fragguard;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Shared resolver for player-scoped lookup and rollback. Names only select a
 * canonical historical UUID: subsequent queries must filter by actor_uuid.
 *
 * All SQL runs on FragGuard's database worker and uses indexes built by the
 * schema-v5 migration; it never queries online players or an external API.
 */
final class HistoricalPlayerResolver {
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern COMPACT_UUID = Pattern.compile("[0-9a-fA-F]{32}");
    private static final Pattern USERNAME = Pattern.compile("[a-zA-Z0-9_]{1,16}");

    private HistoricalPlayerResolver() {
    }

    @FunctionalInterface
    interface StatementFactory {
        PreparedStatement prepare(Connection connection, String sql) throws SQLException;
    }

    static HistoricalPlayerIdentity resolve(Connection connection, String argument,
                                            StatementFactory statements) throws SQLException {
        if (argument == null) {
            throw new IdentityException("Enter a username or player UUID (for example, u:Builder).");
        }
        String value = argument.trim();
        if (value.regionMatches(true, 0, "u:", 0, 2)) {
            value = value.substring(2).trim();
        }
        if (value.isEmpty()) {
            throw new IdentityException("Enter a username or player UUID (for example, u:Builder).");
        }

        UUID player = parseUuid(value);
        if (player == null) {
            if (!USERNAME.matcher(value).matches()) {
                throw new IdentityException("Invalid player name or UUID: " + value);
            }
            player = resolveHistoricalName(connection, value, statements);
        }

        // Resolve by UUID, never by the old supplied name: this returns the most
        // recent label even after a rename without changing historical ownership.
        String uuid = player.toString();
        try (PreparedStatement statement = statements.prepare(connection, """
                SELECT actor_name FROM block_changes
                WHERE actor_uuid = ?
                ORDER BY happened_at DESC, id DESC
                LIMIT 1
                """)) {
            statement.setString(1, uuid);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new IdentityException("No retained FragGuard history for player " + value + ".");
                }
                String display = row.getString("actor_name");
                if (display == null || display.isBlank()) {
                    display = uuid;
                }
                return new HistoricalPlayerIdentity(player, display);
            }
        }
    }

    private static UUID resolveHistoricalName(Connection connection, String name,
                                             StatementFactory statements) throws SQLException {
        UUID candidate = null;
        // actor_name COLLATE NOCASE is indexed; DISTINCT only compares UUIDs
        // for rows matching the given name, not the entire history table.
        try (PreparedStatement statement = statements.prepare(connection, """
                SELECT DISTINCT actor_uuid FROM block_changes
                WHERE actor_name = ? COLLATE NOCASE
                """)) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID found = parseUuid(rows.getString("actor_uuid"));
                    if (found == null) {
                        continue; // environmental/system labels are not player identities
                    }
                    if (candidate != null && !candidate.equals(found)) {
                        throw new IdentityException("Player name '" + name
                                + "' matches more than one historical UUID. Use a UUID to select a player.");
                    }
                    candidate = found;
                }
            }
        }
        if (candidate == null) {
            throw new IdentityException("No retained FragGuard history for player '" + name + "'.");
        }
        return candidate;
    }

    private static UUID parseUuid(String supplied) {
        if (supplied == null) {
            return null;
        }
        String normalized = supplied.trim();
        if (COMPACT_UUID.matcher(normalized).matches()) {
            normalized = normalized.substring(0, 8) + "-"
                    + normalized.substring(8, 12) + "-"
                    + normalized.substring(12, 16) + "-"
                    + normalized.substring(16, 20) + "-"
                    + normalized.substring(20);
        }
        if (!CANONICAL_UUID.matcher(normalized).matches()) {
            return null;
        }
        return UUID.fromString(normalized.toLowerCase(Locale.ROOT));
    }

    record HistoricalPlayerIdentity(UUID uuid, String latestName) {
    }

    static final class IdentityException extends IllegalArgumentException {
        IdentityException(String message) {
            super(message);
        }
    }
}
