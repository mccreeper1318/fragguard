package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseQuickDiagnosticsTest {
    @TempDir Path directory;

    private static final DatabaseHealth HEALTH =
            new DatabaseHealth(2, 100, 1, 20, 0, 5, true, "");

    @Test
    void overviewReturnsReadOnlySqlitePagesSchemaAndFileSizesWithoutScanningRecords() throws Exception {
        Path db = seededDatabase(true);
        long before = Files.size(db);
        try (var diagnostics = new DatabaseQuickDiagnostics(db)) {
            var r = diagnostics.run(DatabaseQuickDiagnostics.Mode.OVERVIEW,
                    HEALTH, 30, 45, 15).join();
            assertEquals(DatabaseQuickDiagnostics.Mode.OVERVIEW, r.mode());
            assertEquals(db.toAbsolutePath(), r.databasePath());
            assertEquals(9, r.schemaVersion());
            assertEquals(before, r.mainBytes());
            assertTrue(r.pageSize() >= 512L);
            assertTrue(r.pageCount() >= r.freelistCount());
            assertTrue(r.freelistCount() > 0, "deleted large BLOB should free multiple SQLite pages");
            assertEquals(r.freelistCount() * r.pageSize(), r.recyclableBytes());
            assertNull(r.historyRows(), "fast overview must not scan 57M-row history");
            assertNull(r.rollbackJobs());
            assertNull(r.quickCheck(), "integrity scan must be opt-in");
            assertEquals(30, r.retentionDays());
            assertEquals(45, r.rollbackRetentionDays());
            assertEquals(0, r.health().droppedWrites());
            List<String> lines = r.lines();
            assertTrue(lines.stream().anyMatch(s -> s.contains("VACUUM INTO") && s.contains("NOT")));
            assertTrue(lines.stream().anyMatch(s -> s.contains("hosting quota") && s.contains("unknown")));
            assertTrue(lines.stream().anyMatch(s -> s.contains("not scanned")));
            assertTrue(r.measuredAt().isBefore(Instant.now().plusSeconds(2)));
        }
        assertEquals(before, Files.size(db), "diagnostic read must not rewrite database file");
    }

    @Test
    void explicitCountsAndQuickCheckReportActualValuesWithoutChangingHistory() throws Exception {
        Path db = seededDatabase(false);
        try (var diagnostics = new DatabaseQuickDiagnostics(db)) {
            var counts = diagnostics.run(DatabaseQuickDiagnostics.Mode.COUNTS,
                    HEALTH, 30, 30, 15).join();
            assertEquals(3L, counts.historyRows());
            assertEquals(2L, counts.rollbackJobs());
            assertNull(counts.quickCheck());
            assertTrue(counts.lines().stream().anyMatch(s -> s.contains("History rows: 3")));
            var checked = diagnostics.run(DatabaseQuickDiagnostics.Mode.QUICK_CHECK,
                    HEALTH, 30, 30, 15).join();
            assertEquals("ok", checked.quickCheck());
            assertNull(checked.historyRows(), "check must not also scan every row");
            assertTrue(checked.lines().stream().anyMatch(s -> s.contains("Quick integrity check: ok")));
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM block_changes")) {
            assertTrue(rs.next());
            assertEquals(3, rs.getLong(1));
        }
    }

    @Test
    void doesNotCreateMissingDatabaseOrSilentlyReportZeroRows() {
        Path missing = directory.resolve("fragguard.db");
        try (var diagnostics = new DatabaseQuickDiagnostics(missing)) {
            var failure = assertThrows(CompletionException.class,
                    () -> diagnostics.run(DatabaseQuickDiagnostics.Mode.OVERVIEW,
                            HEALTH, 30, 30, 5).join());
            assertTrue(failure.getCause().getMessage().contains("missing"));
        }
        assertFalse(Files.exists(missing), "read-only connection must not create missing database");
    }

    @Test
    void readOnlyConnectionDoesNotMigrateOrRewriteOlderSchema() throws Exception {
        Path db = directory.resolve("fragguard.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE block_changes (id INTEGER PRIMARY KEY)");
            statement.execute("CREATE TABLE rollback_jobs (id INTEGER PRIMARY KEY)");
            statement.execute("PRAGMA user_version = 7");
        }
        try (var diagnostics = new DatabaseQuickDiagnostics(db)) {
            var overview = diagnostics.run(DatabaseQuickDiagnostics.Mode.OVERVIEW,
                    HEALTH, 7, 7, 10).join();
            assertEquals(7, overview.schemaVersion());
            assertTrue(overview.lines().stream().anyMatch(s -> s.contains("expects v9")));
            assertEquals(0L, diagnostics.run(DatabaseQuickDiagnostics.Mode.COUNTS,
                    HEALTH, 7, 7, 10).join().historyRows());
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement stmt = connection.createStatement();
             ResultSet version = stmt.executeQuery("PRAGMA user_version")) {
            assertTrue(version.next());
            assertEquals(7, version.getInt(1), "diagnostics may not migrate schema");
        }
    }

    @Test
    void errorReportsAreExplicitWhenExpectedTablesAreMissing() throws Exception {
        Path db = directory.resolve("fragguard.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE other_table (id INTEGER)");
        }
        try (var diagnostics = new DatabaseQuickDiagnostics(db)) {
            assertNotNull(diagnostics.run(DatabaseQuickDiagnostics.Mode.OVERVIEW,
                    HEALTH, 30, 30, 5).join());
            var failure = assertThrows(CompletionException.class,
                    () -> diagnostics.run(DatabaseQuickDiagnostics.Mode.COUNTS,
                            HEALTH, 30, 30, 5).join());
            assertInstanceOf(java.sql.SQLException.class, failure.getCause());
        }
    }

    @Test
    void consecutiveCompletedDiagnosticsCanBeStartedImmediatelyAfterJoin() throws Exception {
        Path db = seededDatabase(false);
        try (var diagnostics = new DatabaseQuickDiagnostics(db)) {
            // Regression for the future-completion race: join() can wake a
            // waiting caller before the worker's finally block runs. A follow-up
            // diagnostic must never see the previous request as still active.
            for (int i = 0; i < 500; i++) {
                var result = diagnostics.run(DatabaseQuickDiagnostics.Mode.OVERVIEW,
                        HEALTH, 30, 30, 15).join();
                assertEquals(DatabaseQuickDiagnostics.Mode.OVERVIEW, result.mode(),
                        "completed overview " + i + " must free the slot before join() returns");
            }
            assertEquals(3L, diagnostics.run(DatabaseQuickDiagnostics.Mode.COUNTS,
                    HEALTH, 30, 30, 15).join().historyRows());
        }
    }

    @Test
    void consecutiveFailedDiagnosticsAlsoReleaseSlotBeforeJoin() throws Exception {
        Path db = directory.resolve("fragguard.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unrelated_table(id INTEGER)");
        }
        try (var diagnostics = new DatabaseQuickDiagnostics(db)) {
            for (int i = 0; i < 100; i++) {
                assertThrows(CompletionException.class, () ->
                        diagnostics.run(DatabaseQuickDiagnostics.Mode.COUNTS,
                                HEALTH, 30, 30, 15).join());
                assertEquals(DatabaseQuickDiagnostics.Mode.OVERVIEW,
                        diagnostics.run(DatabaseQuickDiagnostics.Mode.OVERVIEW,
                                HEALTH, 30, 30, 15).join().mode(),
                        "a completed failed scan must free its slot before join()");
            }
        }
    }

    @Test
    void cancellingQueuedTaskReleasesSlotWithoutRunningCancelledScan() throws Exception {
        Path db = seededDatabase(false);
        try (var diagnostics = new DatabaseQuickDiagnostics(db)) {
            Field field = DatabaseQuickDiagnostics.class.getDeclaredField("worker");
            field.setAccessible(true);
            ExecutorService worker = (ExecutorService) field.get(diagnostics);

            CountDownLatch occupied = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> blocker = worker.submit(() -> {
                occupied.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out holding diagnostics worker");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            try {
                assertTrue(occupied.await(5, TimeUnit.SECONDS));

                var cancelled = diagnostics.run(DatabaseQuickDiagnostics.Mode.COUNTS,
                        HEALTH, 30, 30, 10);
                assertTrue(cancelled.cancel(true), "queued diagnostic must be cancellable");
                assertTrue(cancelled.isCancelled());

                // Codex #176: the queued FutureTask will not enter its finally
                // block after cancellation. A new request must be accepted now,
                // while the single worker is still occupied.
                CompletableFuture<DatabaseQuickDiagnostics.Report> next = diagnostics.run(
                        DatabaseQuickDiagnostics.Mode.OVERVIEW,
                        HEALTH, 30, 30, 10);
                assertFalse(next.isCompletedExceptionally(),
                        "cancelling an unstarted request must free the active slot");
                release.countDown();
                blocker.get(5, TimeUnit.SECONDS);
                assertEquals(DatabaseQuickDiagnostics.Mode.OVERVIEW,
                        next.get(10, TimeUnit.SECONDS).mode());
                assertTrue(cancelled.isCancelled(), "cancelled scan may not later publish results");
                assertEquals(3L, diagnostics.run(DatabaseQuickDiagnostics.Mode.COUNTS,
                        HEALTH, 30, 30, 10).join().historyRows(),
                        "later requests must work after the queued cancellation");
            } finally {
                release.countDown();
                blocker.cancel(true);
            }
        }
    }

    @Test
    void cancelledRequestDequeuedAfterReplacementNeverStartsItsSqliteScan() throws Exception {
        Path db = seededDatabase(false);
        try (var diagnostics = new DatabaseQuickDiagnostics(db)) {
            Field field = DatabaseQuickDiagnostics.class.getDeclaredField("worker");
            field.setAccessible(true);
            ExecutorService worker = (ExecutorService) field.get(diagnostics);

            CountDownLatch occupied = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> blocker = worker.submit(() -> {
                occupied.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            try {
                assertTrue(occupied.await(5, TimeUnit.SECONDS));
                var old = diagnostics.run(DatabaseQuickDiagnostics.Mode.QUICK_CHECK,
                        HEALTH, 30, 30, 10);
                assertTrue(old.cancel(true));
                var next = diagnostics.run(DatabaseQuickDiagnostics.Mode.COUNTS,
                        HEALTH, 30, 30, 10);

                // Remove the SQLite database only after the replacement request
                // has already been accepted: the cancelled request must not
                // hold the active slot or complete successfully when released.
                release.countDown();
                blocker.get(5, TimeUnit.SECONDS);
                assertTrue(old.isCancelled());
                assertEquals(3L, next.get(10, TimeUnit.SECONDS).historyRows());
            } finally {
                release.countDown();
                blocker.cancel(true);
            }
        }
    }

    @Test
    void shutdownRejectsAdditionalDiagnostics() throws Exception {
        Path db = seededDatabase(false);
        var diagnostics = new DatabaseQuickDiagnostics(db);
        diagnostics.close();
        var failed = assertThrows(CompletionException.class,
                () -> diagnostics.run(DatabaseQuickDiagnostics.Mode.OVERVIEW,
                        HEALTH, 30, 30, 5).join());
        assertTrue(failed.getCause().getMessage().contains("stopped"));
    }

    @Test
    void humanReadableReportKeepsUnknownSpaceAndSensitiveErrorSeparate() {
        var degraded = new DatabaseHealth(8, 100, 3, 20, 2, 4, false, "disk busy");
        var report = new DatabaseQuickDiagnostics.Report(
                DatabaseQuickDiagnostics.Mode.OVERVIEW, Instant.now(),
                directory.resolve("fragguard.db"), null, null, 0L, null,
                12, 4096, 3, 12288, 9, null, null, null, degraded, 30, 7);
        List<String> lines = report.lines();
        assertTrue(lines.stream().anyMatch(s -> s.contains("main unknown")));
        assertTrue(lines.stream().anyMatch(s -> s.contains("WAL unknown")));
        assertTrue(lines.stream().anyMatch(s -> s.contains("SHM 0 B")));
        assertTrue(lines.stream().anyMatch(s -> s.contains("Storage: DEGRADED")));
        assertTrue(lines.stream().anyMatch(s -> s.contains("Storage error: disk busy")));
    }

    private Path seededDatabase(boolean reclaim) throws Exception {
        Path folder = directory.resolve("sqlite test folder");
        Files.createDirectories(folder);
        Path db = folder.resolve("fragguard.db");
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("CREATE TABLE block_changes (id INTEGER PRIMARY KEY, payload BLOB)");
            s.execute("CREATE TABLE rollback_jobs (id INTEGER PRIMARY KEY)");
            s.execute("PRAGMA user_version = 9");
            try (PreparedStatement insert = c.prepareStatement("INSERT INTO block_changes(payload) VALUES (?)")) {
                for (int i = 0; i < 3; i++) {
                    insert.setBytes(1, new byte[]{1, (byte) i, 3});
                    insert.executeUpdate();
                }
            }
            s.execute("INSERT INTO rollback_jobs(id) VALUES (1), (2)");
            if (reclaim) {
                s.execute("CREATE TABLE storage_padding (id INTEGER PRIMARY KEY, payload BLOB)");
                try (PreparedStatement padded = c.prepareStatement(
                        "INSERT INTO storage_padding (payload) VALUES (?)")) {
                    padded.setBytes(1, new byte[250_000]);
                    padded.executeUpdate();
                }
                s.execute("DELETE FROM storage_padding");
            }
        }
        return db;
    }
}
