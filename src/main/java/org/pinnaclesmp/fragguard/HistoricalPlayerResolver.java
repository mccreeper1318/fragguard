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
 * canonical historical UUID: subsequent queries match direct and causal UUIDs.
 *
 * All SQL runs on FragGuard's database worker and uses indexes built by the
 * schema-v5/v6/v7 migrations; it never queries online players or an external API.
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

        // Resolve by UUID, not by the supplied old username. Each indexed
        // branch returns only its newest row, so the resolver never sorts or
        // materializes every block mutation by a prolific player.
        String uuid = player.toString();
        NameCandidate direct = latestName(connection, uuid, statements, """
                SELECT actor_name AS recorded_name, happened_at, id
                FROM block_changes
                WHERE actor_uuid = ? AND %s
                ORDER BY happened_at DESC, id DESC
                LIMIT 1
                """.formatted(HistoricalActorIndexPlan.PLAYER_ACTOR_PREDICATE));
        NameCandidate causal = latestName(connection, uuid, statements, """
                SELECT causal_player_name AS recorded_name, happened_at, id
                FROM block_changes
                WHERE causal_player_uuid = ? AND causal_player_name IS NOT NULL
                ORDER BY happened_at DESC, id DESC
                LIMIT 1
                """);
        if (direct == null && causal == null) {
            throw new IdentityException("No retained FragGuard history for player " + value + ".");
        }
        NameCandidate newest = causal == null || direct != null && (
                direct.happenedAt() > causal.happenedAt()
                        || direct.happenedAt() == causal.happenedAt()
                        && direct.id() >= causal.id()) ? direct : causal;
        String display = newest.name();
        return new HistoricalPlayerIdentity(player,
                display == null || display.isBlank() ? uuid : display);
    }

    private static NameCandidate latestName(Connection connection, String uuid,
                                           StatementFactory statements, String sql) throws SQLException {
        try (PreparedStatement statement = statements.prepare(connection, sql)) {
            statement.setString(1, uuid);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new NameCandidate(
                        result.getString("recorded_name"), result.getLong("happened_at"),
                        result.getLong("id")) : null;
            }
        }
    }

    private record NameCandidate(String name, long happenedAt, long id) {
    }

    private static UUID resolveHistoricalName(Connection connection, String name,
                                             StatementFactory statements) throws SQLException {
        UUID candidate = null;
        // Both name lookups are indexed (schema v7), even when a player has
        // only indirect events and no directly authored block changes.
        try (PreparedStatement statement = statements.prepare(connection, """
                SELECT actor_uuid AS player_uuid FROM block_changes
                WHERE actor_name = ? COLLATE NOCASE AND %s
                UNION
                SELECT causal_player_uuid AS player_uuid FROM block_changes
                WHERE causal_player_name = ? COLLATE NOCASE
                  AND causal_player_uuid IS NOT NULL
                """.formatted(HistoricalActorIndexPlan.PLAYER_ACTOR_PREDICATE))) {
            statement.setString(1, name);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID found = parseUuid(rows.getString("player_uuid"));
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
