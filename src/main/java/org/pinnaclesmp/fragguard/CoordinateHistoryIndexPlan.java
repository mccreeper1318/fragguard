package org.pinnaclesmp.fragguard;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Reuse the existing world/chunk/time index for player rollback coordinate
 * history instead of creating a second index over every historical row.
 *
 * Every coordinate has a deterministic chunk (x >> 4, z >> 4).
 * The additional exact x/y/z and rollback_pending predicates are still checked
 * by SQLite, including all system, causal and later-player changes.
 */
final class CoordinateHistoryIndexPlan {
    static final String EXISTING_INDEX = "idx_fg_spatial_time";
    private static final List<String> EXPECTED_COLUMNS =
            List.of("world_uuid", "chunk_x", "chunk_z", "happened_at");

    static final String TIMELINE_SQL = """
            SELECT id, happened_at, actor_uuid, actor_name, causal_player_uuid,
                   causal_chain_id, causal_cause_type, world, before_data,
                   after_data, before_entity_data, after_entity_data
            FROM block_changes
            WHERE rollback_pending = 0 AND world_uuid = ?
              AND chunk_x = ? AND chunk_z = ?
              AND x = ? AND y = ? AND z = ?
              AND happened_at > ? AND happened_at <= ?
            ORDER BY happened_at DESC, id DESC
            LIMIT ?
            """;

    static final String STALE_CHECK_SQL = """
            SELECT 1 FROM block_changes
            WHERE rollback_pending = 0 AND world_uuid = ?
              AND chunk_x = ? AND chunk_z = ?
              AND x = ? AND y = ? AND z = ?
              AND happened_at > ?
            LIMIT 1
            """;

    private CoordinateHistoryIndexPlan() { }

    /**
     * No table/index changes are made. A v7 database must already have
     * idx_fg_spatial_time from the original SQLite schema. Fail closed rather
     * than silently lose historical changes or create a new full-history index.
     */
    static void verifyExistingHistory(Connection connection) throws SQLException {
        List<String> indexedColumns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("PRAGMA index_info(idx_fg_spatial_time)")) {
            while (columns.next()) {
                indexedColumns.add(columns.getString("name"));
            }
        }
        if (!EXPECTED_COLUMNS.equals(indexedColumns)) {
            throw new SQLException("Schema v7→v8 cannot use the existing world/chunk/time index: "
                    + "idx_fg_spatial_time is missing or has unexpected columns. "
                    + "Migration left unchanged; preserve the database and do not delete history.");
        }

        // Legacy v0→v1 migrations backfilled chunk_x/z. A read-only scan checks
        // that those persisted values really match the block coordinates.
        // Otherwise narrowing history by chunk could hide a conflict and make
        // a player rollback unsafe. Fail startup without modifying any rows.
        try (Statement statement = connection.createStatement();
             ResultSet mismatches = statement.executeQuery("""
                     SELECT 1 FROM block_changes
                     WHERE chunk_x != (x >> 4) OR chunk_z != (z >> 4)
                     LIMIT 1
                     """)) {
            if (mismatches.next()) {
                throw new SQLException("Schema v7→v8 found inconsistent historical chunk coordinates. "
                        + "Migration left unchanged so player rollback cannot miss later edits. "
                        + "Preserve the database and investigate on an isolated copy.");
            }
        }
    }
}
