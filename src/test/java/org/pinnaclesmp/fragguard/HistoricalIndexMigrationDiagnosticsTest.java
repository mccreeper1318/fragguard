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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoricalIndexMigrationDiagnosticsTest {
    @TempDir Path directory;

    @Test
    void reportsSuccessfulIndividualIndexWithoutLeakingDatabasePath() throws Exception {
        Path file = directory.resolve("fragguard.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement setup = connection.createStatement()) {
            setup.execute("CREATE TABLE block_changes (id INTEGER PRIMARY KEY, actor_uuid TEXT, "
                    + "world_uuid TEXT, happened_at INTEGER, actor_name TEXT)");
            List<String> info = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            HistoricalIndexMigrationDiagnostics.createIndex(connection, file.toFile(), "idx_fg_actor_time",
                    "CREATE INDEX idx_fg_actor_time ON block_changes(actor_uuid, happened_at DESC, id DESC)",
                    info::add, warnings::add);

            assertTrue(warnings.isEmpty());
            assertEquals(2, info.size());
            assertTrue(info.get(0).contains("idx_fg_actor_time: starting"));
            assertTrue(info.get(1).contains("idx_fg_actor_time: completed"));
            assertTrue(info.get(0).contains("temp_store="));
            assertTrue(info.get(0).contains("sqlite-native-temp-dir=unresolved"));
            assertTrue(info.get(0).contains("data-fs-host-usable="));
            assertFalse(String.join("\n", info).contains(directory.toString()),
                    "storage diagnostics must never print host filesystem paths");
            try (ResultSet indexes = setup.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_fg_actor_time'")) {
                assertTrue(indexes.next());
            }
        }
    }

    @Test
    void failedIndexReportsItsNameAndPreservesOriginalSqlException() throws Exception {
        Path file = directory.resolve("fragguard-invalid.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement setup = connection.createStatement()) {
            setup.execute("CREATE TABLE block_changes(id INTEGER PRIMARY KEY, actor_uuid TEXT)");
            List<String> warnings = new ArrayList<>();
            SQLException failure = assertThrows(SQLException.class,
                    () -> HistoricalIndexMigrationDiagnostics.createIndex(connection, file.toFile(),
                            "idx_fg_actor_time", "CREATE INDEX idx_fg_actor_time ON missing_table(actor_uuid)",
                            message -> { }, warnings::add));

            assertTrue(failure.getMessage().contains("idx_fg_actor_time"));
            assertNotNull(failure.getCause());
            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0).contains("FAILED"));
            assertTrue(warnings.get(0).contains("SQLite error"));
            assertTrue(warnings.get(0).contains("before:"));
            assertTrue(warnings.get(0).contains("after:"));
            assertTrue(warnings.get(0).contains("NOT an account quota"));
            assertFalse(warnings.get(0).contains(directory.toString()));
        }
    }

    @Test
    void fullDatabaseDuringIndexBuildRetainsVersionFourAndAllRows() throws Exception {
        // Deliberately constrain the SQLite main database, not a real filesystem.
        // This reproduces SQLITE_FULL in an index build without relying on hosting quotas.
        Path file = directory.resolve("fragguard-cap.db");
        final int rows = 8_000;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement setup = connection.createStatement()) {
            setup.execute("PRAGMA journal_mode=DELETE");
            setup.execute("CREATE TABLE block_changes (id INTEGER PRIMARY KEY, actor_uuid TEXT, "
                    + "world_uuid TEXT, happened_at INTEGER, actor_name TEXT)");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO block_changes(actor_uuid, world_uuid, happened_at, actor_name) VALUES (?,?,?,?)")) {
                connection.setAutoCommit(false);
                for (int i = 0; i < rows; i++) {
                    insert.setString(1, "actor-" + (i % 500));
                    insert.setString(2, "world-" + (i % 8));
                    insert.setLong(3, i);
                    insert.setString(4, "Builder" + (i % 500));
                    insert.addBatch();
                }
                insert.executeBatch();
                connection.commit();
                connection.setAutoCommit(true);
            }
            setup.execute("PRAGMA user_version=4");
            long pages;
            try (ResultSet size = setup.executeQuery("PRAGMA page_count")) {
                assertTrue(size.next());
                pages = size.getLong(1);
            }
            try (ResultSet limit = setup.executeQuery("PRAGMA max_page_count=" + (pages + 1))) {
                assertTrue(limit.next());
                assertEquals(pages + 1, limit.getLong(1));
            }

            List<String> warning = new ArrayList<>();
            connection.setAutoCommit(false);
            SQLException failure = assertThrows(SQLException.class,
                    () -> HistoricalIndexMigrationDiagnostics.createIndex(connection, file.toFile(),
                            "idx_fg_actor_time",
                            "CREATE INDEX idx_fg_actor_time ON block_changes(actor_uuid, happened_at DESC, id DESC)",
                            message -> { }, warning::add));
            assertTrue(failure.getMessage().contains("idx_fg_actor_time"));
            assertTrue(failure.getMessage().contains("SQLITE_FULL"));
            assertTrue(warning.stream().anyMatch(message -> message.contains("SQLITE_FULL")));
            connection.rollback();
            connection.setAutoCommit(true);
        }

        try (Connection reopened = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement verify = reopened.createStatement()) {
            try (ResultSet version = verify.executeQuery("PRAGMA user_version")) {
                assertTrue(version.next());
                assertEquals(4, version.getInt(1));
            }
            try (ResultSet history = verify.executeQuery("SELECT COUNT(*) FROM block_changes")) {
                assertTrue(history.next());
                assertEquals(rows, history.getInt(1));
            }
            try (ResultSet indexes = verify.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_fg_actor_time'")) {
                assertFalse(indexes.next(), "failed index creation must not leave a committed index");
            }
        }
    }
}
