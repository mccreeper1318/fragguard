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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoordinateIndexMigrationDiagnosticsTest {
    @TempDir Path directory;

    private static final String INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_fg_coordinate_time
            ON block_changes(y, world_uuid, x, z, happened_at DESC, id DESC)
            WHERE rollback_pending = 0
            """;

    @Test
    void v7CoordinateIndexFullFailurePreservesHistoryAndRetriesWithoutSchemaReset() throws Exception {
        Path databaseFile = directory.resolve("coordinate-v7.db");
        int records = 16_000;
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             Statement setup = db.createStatement()) {
            setup.execute("PRAGMA journal_mode=DELETE");
            setup.execute("""
                    CREATE TABLE block_changes(
                        id INTEGER PRIMARY KEY, y INTEGER NOT NULL, world_uuid TEXT NOT NULL,
                        x INTEGER NOT NULL, z INTEGER NOT NULL, happened_at INTEGER NOT NULL,
                        rollback_pending INTEGER NOT NULL DEFAULT 0, actor_name TEXT NOT NULL)
                    """);
            try (PreparedStatement insert = db.prepareStatement("""
                    INSERT INTO block_changes(y, world_uuid, x, z, happened_at, actor_name)
                    VALUES(?, ?, ?, ?, ?, ?)
                    """)) {
                db.setAutoCommit(false);
                for (int i = 0; i < records; i++) {
                    insert.setInt(1, 60 + i % 10);
                    insert.setString(2, i % 2 == 0
                            ? "11111111-1111-1111-1111-111111111111"
                            : "22222222-2222-2222-2222-222222222222");
                    insert.setInt(3, i % 450);
                    insert.setInt(4, i / 450);
                    insert.setLong(5, 1_000_000L + i);
                    insert.setString(6, "SYSTEM");
                    insert.addBatch();
                }
                insert.executeBatch();
                db.commit();
                db.setAutoCommit(true);
            }
            setup.execute("PRAGMA user_version=7");
            long pages = readLong(db, "PRAGMA page_count");
            try (ResultSet limit = setup.executeQuery("PRAGMA max_page_count=" + (pages + 1))) {
                assertTrue(limit.next());
                assertEquals(pages + 1, limit.getLong(1));
            }

            List<String> info = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            db.setAutoCommit(false);
            SQLException failure = assertThrows(SQLException.class,
                    () -> HistoricalIndexMigrationDiagnostics.createIndex(db, databaseFile.toFile(),
                            "Schema v7->v8", "idx_fg_coordinate_time", INDEX_SQL,
                            info::add, warnings::add));

            assertTrue(failure.getMessage().contains("Schema v7->v8"));
            assertTrue(failure.getMessage().contains("idx_fg_coordinate_time"));
            assertTrue(failure.getMessage().contains("SQLITE_FULL"));
            assertNotNull(failure.getCause());
            assertTrue(warnings.stream().anyMatch(v -> v.contains("FAILED")));
            assertTrue(info.getFirst().contains("native-temp-candidates={"));
            assertTrue(info.getFirst().contains("max_page_count="));
            assertTrue(warnings.getFirst().contains("before:"));
            assertTrue(warnings.getFirst().contains("after:"));
            assertFalse(String.join("\n", info) .contains(directory.toString()));
            assertFalse(String.join("\n", warnings).contains(directory.toString()));
            db.rollback();
            db.setAutoCommit(true);
            assertEquals(7, readLong(db, "PRAGMA user_version"));
            assertEquals(records, readLong(db, "SELECT COUNT(*) FROM block_changes"));
            assertEquals(0, readLong(db, """
                    SELECT COUNT(*) FROM sqlite_master WHERE type='index'
                    AND name='idx_fg_coordinate_time'
                    """));
        }

        // Opening a new SQLite connection releases the per-connection
        // synthetic max_page_count limit; the source table is unchanged.
        try (Connection retry = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             Statement stmt = retry.createStatement()) {
            List<String> info = new ArrayList<>();
            HistoricalIndexMigrationDiagnostics.createIndex(retry, databaseFile.toFile(),
                    "Schema v7->v8", "idx_fg_coordinate_time", INDEX_SQL, info::add,
                    message -> fail("unexpected diagnostic warning: " + message));
            stmt.execute("PRAGMA user_version=8");
            assertEquals(records, readLong(retry, "SELECT COUNT(*) FROM block_changes"));
            assertEquals(8, readLong(retry, "PRAGMA user_version"));
            assertEquals(1, readLong(retry, """
                    SELECT COUNT(*) FROM sqlite_master WHERE type='index'
                    AND name='idx_fg_coordinate_time'
                    """));
            assertEquals("ok", readText(retry, "PRAGMA integrity_check(1)"));
            assertTrue(info.stream().anyMatch(v -> v.contains("completed")));
        }
    }

    @Test
    void nativeTempCandidateProbeNeverRevealsDirectoryOrEnvironmentValues() {
        Path databaseFile = directory.resolve("private-history.db");
        String description = SqliteTempStorageProbe.describe(databaseFile.toFile());
        assertTrue(description.contains("var-tmp:"));
        assertTrue(description.contains("usr-tmp:"));
        assertTrue(description.contains("tmp:"));
        assertTrue(description.contains("java-tmp(advisory):"));
        assertTrue(description.contains("selected-sqlite-native-temp=unverified"));
        assertTrue(description.contains("host-visible-space-does-not-prove-container-quota"));
        assertFalse(description.contains(directory.toString()),
                "Never print the full DB location in migration diagnostics");
    }

    private static long readLong(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private static String readText(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }
}
