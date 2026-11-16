package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class IncrementalSqliteReclamationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void brandNewDatabaseCanUseIncrementalModeBeforeTablesExist() throws Exception {
        try (Connection connection = open("new.db")) {
            assertEquals(IncrementalSqliteReclamation.NONE,
                    IncrementalSqliteReclamation.readMode(connection));
            IncrementalSqliteReclamation.configureNewDatabase(connection);
            assertEquals(IncrementalSqliteReclamation.INCREMENTAL,
                    IncrementalSqliteReclamation.readMode(connection));
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE history (id INTEGER PRIMARY KEY, payload BLOB)");
                statement.execute("PRAGMA journal_mode=WAL");
            }
            assertEquals(IncrementalSqliteReclamation.INCREMENTAL,
                    IncrementalSqliteReclamation.readMode(connection));
        }
        try (Connection restarted = open("new.db")) {
            assertEquals(IncrementalSqliteReclamation.INCREMENTAL,
                    IncrementalSqliteReclamation.readMode(restarted),
                    "format option must persist across restarts");
        }
    }

    @Test
    void existingDefaultDatabaseIsSkippedWithoutModeConversionOrMutation() throws Exception {
        try (Connection connection = open("existing.db")) {
            createAndPruneHistory(connection);
            assertEquals(IncrementalSqliteReclamation.NONE,
                    IncrementalSqliteReclamation.readMode(connection));
            long prior = pragma(connection, "page_count");
            long free = pragma(connection, "freelist_count");
            assertTrue(free > 0);
            var result = IncrementalSqliteReclamation.reclaim(connection, 8);
            assertFalse(result.compatible());
            assertEquals(0, result.requestedPages());
            assertEquals(prior, result.pagesAfter());
            assertEquals(free, result.freelistAfter());
            assertEquals(IncrementalSqliteReclamation.NONE,
                    IncrementalSqliteReclamation.readMode(connection));
            assertEquals(1, countRows(connection), "retained history must be untouched");
        }
    }

    @Test
    void fullAutoVacuumModeIsAlsoIncompatible() throws Exception {
        try (Connection connection = open("full.db")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA auto_vacuum=FULL");
                statement.execute("CREATE TABLE history (id INTEGER PRIMARY KEY, payload BLOB)");
            }
            assertEquals(IncrementalSqliteReclamation.FULL,
                    IncrementalSqliteReclamation.readMode(connection));
            var skipped = IncrementalSqliteReclamation.reclaim(connection, 16);
            assertFalse(skipped.compatible());
            assertEquals(0, skipped.requestedPages());
        }
    }

    @Test
    void compatibleDatabaseReclaimsOnlyLimitedPagesAndPreservesLiveRows() throws Exception {
        try (Connection connection = open("incremental.db")) {
            IncrementalSqliteReclamation.configureNewDatabase(connection);
            // Exercise the same WAL journal mode as FragGuard's live writer.
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
            }
            createAndPruneHistory(connection);
            long before = pragma(connection, "page_count");
            long free = pragma(connection, "freelist_count");
            assertTrue(free >= 5, "deleted blobs should leave free SQLite pages");
            var first = IncrementalSqliteReclamation.reclaim(connection, 3);
            assertTrue(first.compatible());
            assertEquals(3, first.requestedPages());
            assertEquals(3, first.reclaimedPages(),
                    "sqlite-jdbc must execute all three vacuum steps, not just one");
            assertEquals(3, first.freelistBefore() - first.freelistAfter(),
                    "each requested page should be removed from the free list");
            assertEquals(before, first.pagesBefore());
            assertEquals(before - 3, first.pagesAfter());
            assertEquals(1, countRows(connection),
                    "reclaiming free pages must not remove retained history");
            assertEquals(1, countRows(connection));
            var bounded = IncrementalSqliteReclamation.reclaim(connection, Integer.MAX_VALUE);
            assertTrue(bounded.requestedPages() <= IncrementalSqliteReclamation.MAX_PAGES_PER_PASS);
            assertEquals(1, countRows(connection));
            assertTrue(bounded.summary().contains("not a guaranteed number of bytes"));
        }
    }

    @Test
    void emptyFreelistIsNoOpEvenForCompatibleDatabase() throws Exception {
        try (Connection connection = open("empty.db")) {
            IncrementalSqliteReclamation.configureNewDatabase(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE history (id INTEGER PRIMARY KEY)");
            }
            var report = IncrementalSqliteReclamation.reclaim(connection, 20);
            assertTrue(report.compatible());
            assertEquals(0, report.requestedPages());
            assertEquals(0, report.reclaimedPages());
        }
    }

    @Test
    void rejectsReclamationInsideTransactionsAndInvalidFormatConfiguration() throws Exception {
        try (Connection connection = open("transaction.db")) {
            connection.setAutoCommit(false);
            assertThrows(java.sql.SQLException.class,
                    () -> IncrementalSqliteReclamation.configureNewDatabase(connection));
            assertThrows(java.sql.SQLException.class,
                    () -> IncrementalSqliteReclamation.reclaim(connection, 20));
            connection.rollback();
        }
    }

    private Connection open(String filename) throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + temporaryDirectory.resolve(filename));
    }

    private static void createAndPruneHistory(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE history (id INTEGER PRIMARY KEY, payload BLOB)");
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO history (payload) VALUES (zeroblob(32768))")) {
            for (int i = 0; i < 64; i++) insert.executeUpdate();
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM history WHERE id > 1");
        }
    }

    private static long pragma(Connection connection, String name) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA " + name)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static long countRows(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM history")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
}
