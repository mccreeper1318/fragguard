package org.pinnaclesmp.fragguard;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Player actor index migration strategy for schema v4 -> v5.
 *
 * A valid historical player UUID has 32 compact or 36 hyphenated characters.
 * Excluding short non-player actor labels (notably SYSTEM) avoids materializing
 * millions of system-only keys. Queries must explicitly include this predicate
 * to be eligible for the partial indexes.
 */
final class HistoricalActorIndexPlan {
    static final String PLAYER_ACTOR_PREDICATE = "length(actor_uuid) IN (32, 36)";

    static final String ACTOR_TIME_SQL = """
            CREATE INDEX IF NOT EXISTS idx_fg_actor_time
            ON block_changes(actor_uuid, happened_at DESC, id DESC)
            WHERE length(actor_uuid) IN (32, 36)
            """;

    static final String ACTOR_WORLD_TIME_SQL = """
            CREATE INDEX IF NOT EXISTS idx_fg_actor_world_time
            ON block_changes(actor_uuid, world_uuid, happened_at DESC, id DESC)
            WHERE length(actor_uuid) IN (32, 36)
            """;

    static final String ACTOR_NAME_SQL = """
            CREATE INDEX IF NOT EXISTS idx_fg_actor_name_uuid
            ON block_changes(actor_name COLLATE NOCASE, actor_uuid)
            WHERE length(actor_uuid) IN (32, 36)
            """;

    private HistoricalActorIndexPlan() { }

    /**
     * Each CREATE INDEX is an independent transaction. Checkpointing its WAL
     * before the next index caps the number of simultaneous uncheckpointed
     * index pages. On interruption schema user_version stays v4; completed
     * indexes are safe, and IF NOT EXISTS allows the next startup to resume.
     */
    static void checkpointBetweenIndexes(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new SQLException("Cannot checkpoint historical indexes inside an open transaction.");
        }
        try (Statement statement = connection.createStatement();
             ResultSet checkpoint = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            if (!checkpoint.next()) {
                throw new SQLException("SQLite did not report the WAL checkpoint result.");
            }
            if (checkpoint.getInt(1) != 0) {
                throw new SQLException("SQLite reported a busy WAL checkpoint between historical indexes. "
                        + "Index creation can resume safely on the next startup after readers release the WAL.");
            }
        }
    }
}
