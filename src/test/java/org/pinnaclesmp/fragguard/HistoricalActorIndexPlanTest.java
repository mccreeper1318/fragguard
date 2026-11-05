package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HistoricalActorIndexPlanTest {
    @TempDir Path directory;

    private static final String UUID_A = "11111111-2222-3333-4444-555555555555";
    private static final String UUID_B = "11111111-2222-3333-4444-666666666666";

    @Test
    void partialIndexesExcludeSystemHistoryAndRemainUsableForPlayerQueries() throws Exception {
        Path file = directory.resolve("partial.db");
        try (Connection db = open(file)) {
            fixture(db, 8_000, 120);
            createAllIndexes(db);
            assertEquals(4, scalar(db, "PRAGMA user_version"));

            // All three selective index definitions must remain partial.
            try (Statement statement = db.createStatement();
                 ResultSet indexes = statement.executeQuery("""
                         SELECT name, sql FROM sqlite_master
                         WHERE type = 'index' AND name LIKE 'idx_fg_actor_%'
                         ORDER BY name
                         """)) {
                int found = 0;
                while (indexes.next()) {
                    found++;
                    assertTrue(indexes.getString("sql").contains(HistoricalActorIndexPlan.PLAYER_ACTOR_PREDICATE));
                }
                assertEquals(3, found);
            }

            assertTrue(plan(db, """
                    SELECT id FROM block_changes INDEXED BY idx_fg_actor_time
                    WHERE actor_uuid = ? AND length(actor_uuid) IN (32, 36)
                    ORDER BY happened_at DESC, id DESC LIMIT 1
                    """, UUID_A).contains("idx_fg_actor_time"));
            assertTrue(plan(db, """
                    SELECT id FROM block_changes INDEXED BY idx_fg_actor_world_time
                    WHERE actor_uuid = ? AND length(actor_uuid) IN (32, 36)
                      AND world_uuid = 'world-1'
                    ORDER BY happened_at DESC, id DESC LIMIT 1
                    """, UUID_A).contains("idx_fg_actor_world_time"));
            assertTrue(plan(db, """
                    SELECT actor_uuid FROM block_changes INDEXED BY idx_fg_actor_name_uuid
                    WHERE actor_name = ? COLLATE NOCASE AND length(actor_uuid) IN (32, 36)
                    """, "builder").contains("idx_fg_actor_name_uuid"));

            var resolved = HistoricalPlayerResolver.resolve(db, "builder", Connection::prepareStatement);
            assertEquals(UUID.fromString(UUID_A), resolved.uuid());
            assertEquals("Builder", resolved.latestName());
            assertEquals(120, scalar(db, """
                    SELECT COUNT(*) FROM block_changes
                    WHERE actor_uuid = '11111111-2222-3333-4444-555555555555'
                      AND length(actor_uuid) IN (32, 36)
                    """));
            assertEquals(8_120, scalar(db, "SELECT COUNT(*) FROM block_changes"));
        }
    }

    @Test
    void partialIndexesUseFewerPagesThanFullIndexesWithSystemHeavyHistory() throws Exception {
        Path optimized = directory.resolve("optimized.db");
        Path full = directory.resolve("full.db");
        long partialPages;
        long fullPages;
        try (Connection db = open(optimized)) {
            fixture(db, 22_000, 400);
            long before = scalar(db, "PRAGMA page_count");
            createAllIndexes(db);
            partialPages = scalar(db, "PRAGMA page_count") - before;
        }
        try (Connection db = open(full);
             Statement statement = db.createStatement()) {
            fixture(db, 22_000, 400);
            long before = scalar(db, "PRAGMA page_count");
            statement.execute("CREATE INDEX idx_fg_actor_time ON block_changes(actor_uuid, happened_at DESC, id DESC)");
            statement.execute("CREATE INDEX idx_fg_actor_world_time ON block_changes(actor_uuid, world_uuid, happened_at DESC, id DESC)");
            statement.execute("CREATE INDEX idx_fg_actor_name_uuid ON block_changes(actor_name COLLATE NOCASE, actor_uuid)");
            fullPages = scalar(db, "PRAGMA page_count") - before;
        }
        assertTrue(partialPages > 0);
        assertTrue(fullPages > partialPages * 2,
                "selective indexes should materially reduce pages on SYSTEM-heavy history: partial="
                        + partialPages + ", full=" + fullPages);
    }

    @Test
    void individuallyCommittedIndexesResumeAfterAFailedLaterStage() throws Exception {
        Path file = directory.resolve("retry.db");
        try (Connection db = open(file)) {
            fixture(db, 3_000, 300);
            try (Statement stmt = db.createStatement()) {
                stmt.execute(HistoricalActorIndexPlan.ACTOR_TIME_SQL);
            }
            HistoricalActorIndexPlan.checkpointBetweenIndexes(db);
            assertEquals(4, scalar(db, "PRAGMA user_version"));
            assertThrows(SQLException.class, () -> {
                try (Statement bad = db.createStatement()) {
                    bad.execute("CREATE INDEX idx_fg_actor_world_time ON missing_table(actor_uuid)");
                }
            });
            assertEquals(4, scalar(db, "PRAGMA user_version"));
        }
        try (Connection db = open(file)) {
            // Restart on v4: CREATE INDEX IF NOT EXISTS skips the committed first
            // index. The missing indexes are created and user_version advances
            // only after each index successfully completes.
            createAllIndexes(db);
            try (Statement stmt = db.createStatement()) {
                stmt.execute("PRAGMA user_version=5");
            }
        }
        try (Connection db = open(file)) {
            assertEquals(5, scalar(db, "PRAGMA user_version"));
            assertEquals(3_300, scalar(db, "SELECT COUNT(*) FROM block_changes"));
            try (Statement stmt = db.createStatement();
                 ResultSet result = stmt.executeQuery("""
                         SELECT name FROM sqlite_master
                         WHERE type='index' AND name LIKE 'idx_fg_actor_%'
                         """)) {
                int count = 0;
                while (result.next()) count++;
                assertEquals(3, count);
            }
        }
    }

    private static Connection open(Path path) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
        }
        return connection;
    }

    private static void fixture(Connection db, int systemRows, int playerRows) throws SQLException {
        try (Statement stmt = db.createStatement()) {
            stmt.execute("""
                    CREATE TABLE block_changes(
                      id INTEGER PRIMARY KEY AUTOINCREMENT, happened_at INTEGER NOT NULL,
                      actor_uuid TEXT NOT NULL, actor_name TEXT NOT NULL,
                      world_uuid TEXT NOT NULL, causal_player_uuid TEXT,
                      rollback_pending INTEGER NOT NULL DEFAULT 0)
                    """);
            stmt.execute("PRAGMA user_version=4");
        }
        db.setAutoCommit(false);
        try (PreparedStatement insert = db.prepareStatement("""
                INSERT INTO block_changes(happened_at, actor_uuid, actor_name, world_uuid, causal_player_uuid)
                VALUES(?, ?, ?, ?, ?)
                """)) {
            for (int i = 0; i < systemRows + playerRows; i++) {
                boolean player = i >= systemRows;
                insert.setLong(1, i);
                insert.setString(2, player ? UUID_A : "SYSTEM");
                insert.setString(3, player ? "Builder" : "System");
                insert.setString(4, i % 2 == 0 ? "world-1" : "world-2");
                // A causal player on a SYSTEM row must not be excluded from
                // causal ownership queries (handled by a separate index).
                insert.setString(5, i == 0 ? UUID_A : null);
                insert.addBatch();
            }
            insert.executeBatch();
        }
        db.commit();
        db.setAutoCommit(true);
    }

    private static void createAllIndexes(Connection db) throws SQLException {
        for (String sql : List.of(HistoricalActorIndexPlan.ACTOR_TIME_SQL,
                HistoricalActorIndexPlan.ACTOR_WORLD_TIME_SQL,
                HistoricalActorIndexPlan.ACTOR_NAME_SQL)) {
            try (Statement stmt = db.createStatement()) {
                stmt.execute(sql);
            }
            HistoricalActorIndexPlan.checkpointBetweenIndexes(db);
        }
    }

    private static long scalar(Connection db, String sql) throws SQLException {
        try (Statement stmt = db.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private static String plan(Connection db, String sql, String argument) throws SQLException {
        try (PreparedStatement stmt = db.prepareStatement("EXPLAIN QUERY PLAN " + sql)) {
            stmt.setString(1, argument);
            try (ResultSet rows = stmt.executeQuery()) {
                StringBuilder result = new StringBuilder();
                while (rows.next()) result.append(rows.getString("detail")).append('\n');
                return result.toString();
            }
        }
    }
}
