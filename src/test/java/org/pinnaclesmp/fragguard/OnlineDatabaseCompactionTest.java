package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OnlineDatabaseCompactionTest {
    @TempDir Path folder;
    private static final byte[] PRIVATE_INVENTORY = new byte[]{1, 0, 3, (byte) 255, 42};

    @Test
    void vacuumCopiesWalHistoryAndRollbackSnapshotsWithoutReplacingActiveDatabase()
            throws Exception {
        Path source = seedDatabase(40);
        long scheduledAt = System.currentTimeMillis() - 2500;
        try (MaintenanceCoordinator coordinator = activeMaintenance(scheduledAt);
             CompactionStoragePreflight preflight = new CompactionStoragePreflight(folder);
             OnlineDatabaseCompaction vacuum = new OnlineDatabaseCompaction(folder, coordinator, preflight)) {
            var approval = approved(preflight);
            var result = vacuum.start(approval).get(60, TimeUnit.SECONDS);

            assertTrue(Files.isRegularFile(result.candidate()));
            assertTrue(result.candidate().getParent().endsWith("compaction-candidates"));
            assertTrue(result.candidate().getFileName().toString()
                    .matches("fragguard-compact-[a-zA-Z0-9_-]+\\.db"));
            assertEquals(9, result.schemaVersion());
            assertEquals(40, result.lastIncludedHistoryId());
            assertEquals(2, result.lastIncludedJobId());
            assertTrue(result.candidateBytes() > 0);
            assertTrue(result.invocationMillis() >= scheduledAt);
            assertTrue(result.completedMillis() >= result.invocationMillis());

            try (Connection copy = DriverManager.getConnection(
                    "jdbc:sqlite:" + result.candidate().toUri() + "?mode=ro");
                 Statement statements = copy.createStatement()) {
                assertEquals(40, scalar(statements, "SELECT COUNT(*) FROM block_changes"));
                assertEquals(2, scalar(statements, "SELECT COUNT(*) FROM rollback_jobs"));
                assertEquals(1, scalar(statements, "SELECT COUNT(*) FROM rollback_job_changes"));
                try (ResultSet blobs = statements.executeQuery(
                        "SELECT before_entity_data FROM rollback_job_changes")) {
                    assertTrue(blobs.next());
                    assertArrayEquals(PRIVATE_INVENTORY, blobs.getBytes(1),
                            "rollback undo snapshots are copied in full");
                }
            }
            Properties evidence = new Properties();
            try (var file = Files.newInputStream(result.snapshotEvidence())) {
                evidence.load(file);
            }
            assertEquals("fragguard-vacuum-snapshot-1", evidence.getProperty("format"));
            assertEquals("false", evidence.getProperty("verified"));
            assertEquals(coordinator.snapshot().operationId(),
                    evidence.getProperty("maintenance_operation_id"));
            assertEquals("between_invocation_and_completion",
                    evidence.getProperty("sqlite_snapshot_time"));
            assertEquals(result.candidate().getFileName().toString(),
                    evidence.getProperty("candidate"));
            assertEquals(Long.toString(result.invocationMillis()),
                    evidence.getProperty("invoked_at_millis"));
            assertEquals("40", evidence.getProperty("included_max_history_id"));
            assertFalse(Files.readString(result.snapshotEvidence()).contains(
                    "secret-private-block-state"), "evidence must not include block/player values");
            assertFalse(Files.readString(result.snapshotEvidence()).contains(
                    source.toAbsolutePath().toString()), "do not disclose active path in evidence");

            MaintenanceSnapshot state = coordinator.snapshot();
            assertEquals(MaintenancePhase.VERIFYING, state.phase(),
                    "new candidates must WAIT for #166 verification");
            assertEquals(result.invocationMillis(), state.snapshotCutoffMillis());
            assertEquals(result.candidate().getFileName().toString(), state.candidateFile());
            assertTrue(state.manifestFile().isEmpty(), "not yet a verified replacement");
            assertFalse(vacuum.progress().running());
            assertEquals("awaiting full verification", vacuum.progress().stage());

            // Open the original independently: it must not have been replaced,
            // checkpointed, wiped or copied into the output as an input duplicate.
            try (Connection original = DriverManager.getConnection(
                    "jdbc:sqlite:" + source.toUri() + "?mode=ro");
                 Statement statements = original.createStatement()) {
                assertEquals(40, scalar(statements, "SELECT COUNT(*) FROM block_changes"));
                assertEquals(2, scalar(statements, "SELECT COUNT(*) FROM rollback_jobs"));
            }
            assertFalse(Files.exists(folder.resolve("backups")),
                    "VACUUM must not create or delete any backups");
            assertTrue(Files.isRegularFile(folder.resolve("compaction-candidates")
                    .resolve(".fg-compaction-reservation")));
        }
    }

    @Test
    void copiedSnapshotIsConsistentWhileLiveWalWriterCanContinue() throws Exception {
        Path source = seedDatabase(1000);
        long before = System.currentTimeMillis() - 1000;
        try (MaintenanceCoordinator coordinator = activeMaintenance(before);
             CompactionStoragePreflight preflight = new CompactionStoragePreflight(folder);
             OnlineDatabaseCompaction worker = new OnlineDatabaseCompaction(folder, coordinator, preflight);
             Connection writer = DriverManager.getConnection("jdbc:sqlite:" + source)) {
            var approval = approved(preflight);
            CompletableFuture<OnlineDatabaseCompaction.Result> future = worker.start(approval);
            // Writes made after VACUUM initiation may or may not be present;
            // only the candidate's copied rows define the SQLite snapshot.
            try (PreparedStatement change = writer.prepareStatement(
                    "INSERT INTO block_changes(id, happened_at, before_data) VALUES(?,?,?)")) {
                for (int i = 1001; i <= 1020; i++) {
                    change.setInt(1, i);
                    change.setLong(2, System.currentTimeMillis());
                    change.setString(3, "subsequent event " + i);
                    change.executeUpdate();
                }
            }
            var result = future.get(60, TimeUnit.SECONDS);
            long copiedRows;
            try (Connection candidate = DriverManager.getConnection(
                    "jdbc:sqlite:" + result.candidate().toUri() + "?mode=ro");
                 Statement statement = candidate.createStatement()) {
                copiedRows = scalar(statement, "SELECT COUNT(*) FROM block_changes");
                assertEquals(copiedRows, scalar(statement,
                        "SELECT COUNT(DISTINCT id) FROM block_changes"));
            }
            assertTrue(copiedRows >= 1000 && copiedRows <= 1020);
            try (Statement statement = writer.createStatement()) {
                assertEquals(1020, scalar(statement, "SELECT COUNT(*) FROM block_changes"));
            }
            assertEquals(MaintenancePhase.VERIFYING, coordinator.snapshot().phase());
            assertTrue(result.invocationMillis() > before);
        }
    }

    @Test
    void refusesCompactionBeforeMaintenanceAndWithoutApprovedCapacity() throws Exception {
        seedDatabase(1);
        try (MaintenanceCoordinator state = MaintenanceCoordinator.open(folder);
             CompactionStoragePreflight preflight = new CompactionStoragePreflight(folder);
             OnlineDatabaseCompaction vacuum = new OnlineDatabaseCompaction(folder, state, preflight)) {
            var denied = CompactionStoragePreflight.evaluate(preflight.measure(),
                    new CompactionStoragePreflight.Evidence(null, null, 256, false));
            assertFalse(denied.mayReserve());
            assertTrue(vacuum.start(denied).isCompletedExceptionally());
            var approved = approved(preflight);
            assertTrue(vacuum.start(approved).isCompletedExceptionally(),
                    "an idle or countdown server may not run compaction");
            assertFalse(Files.exists(folder.resolve("compaction-candidates")));
            assertEquals(MaintenancePhase.IDLE, state.snapshot().phase());
        }
    }

    @Test
    void queuedCancellationAndOverlappingStartCannotAllocateASecondCandidate()
            throws Exception {
        seedDatabase(3);
        try (MaintenanceCoordinator state = activeMaintenance(System.currentTimeMillis() - 2000);
             CompactionStoragePreflight preflight = new CompactionStoragePreflight(folder);
             OnlineDatabaseCompaction vacuum = new OnlineDatabaseCompaction(folder, state, preflight)) {
            // Pause its private worker before the first job can touch SQLite.
            Field field = OnlineDatabaseCompaction.class.getDeclaredField("worker");
            field.setAccessible(true);
            ExecutorService executor = (ExecutorService) field.get(vacuum);
            CountDownLatch blocking = new CountDownLatch(1);
            CountDownLatch entered = new CountDownLatch(1);
            var blocker = executor.submit(() -> {
                entered.countDown();
                try { blocking.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var approved = approved(preflight);
                var pending = vacuum.start(approved);
                assertTrue(vacuum.start(approved).isCompletedExceptionally());
                assertTrue(vacuum.cancel());
                blocking.countDown();
                blocker.get(5, TimeUnit.SECONDS);
                assertThrows(CancellationException.class, () -> pending.get(20, TimeUnit.SECONDS),
                        "CompletableFuture.get propagates cancellation directly");
                assertEquals(MaintenancePhase.ACTIVE, state.snapshot().phase(),
                        "cancelled-before-start must not transition state or create output");
                assertFalse(Files.exists(folder.resolve("compaction-candidates")));
                assertFalse(vacuum.progress().running());
            } finally {
                blocking.countDown();
            }
        }
    }

    @Test
    void anExistingUnverifiedCandidateAlwaysPreventsAnotherCompaction() throws Exception {
        seedDatabase(2);
        try (MaintenanceCoordinator state = activeMaintenance(System.currentTimeMillis() - 2000);
             CompactionStoragePreflight preflight = new CompactionStoragePreflight(folder);
             OnlineDatabaseCompaction vacuum = new OnlineDatabaseCompaction(folder, state, preflight)) {
            // Race/stale candidate appears between the approved probe and
            // reservation; reserve() must still refuse without deleting it.
            var approved = approved(preflight);
            Path dir = Files.createDirectory(folder.resolve("compaction-candidates"));
            Path existing = Files.writeString(dir.resolve("user-preserved-copy.bak"), "KEEP ME");
            assertThrows(ExecutionException.class, () -> vacuum.start(approved)
                    .get(20, TimeUnit.SECONDS));
            assertEquals("KEEP ME", Files.readString(existing));
            assertEquals(MaintenancePhase.ACTIVE, state.snapshot().phase());
            assertEquals(1L, Files.list(dir).count());
        }
    }

    private Path seedDatabase(int count) throws Exception {
        Path db = folder.resolve("fragguard.db");
        try (Connection source = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statements = source.createStatement()) {
            statements.execute("PRAGMA journal_mode=WAL");
            statements.execute("PRAGMA user_version=9");
            statements.execute("CREATE TABLE block_changes(id INTEGER PRIMARY KEY, "
                    + "happened_at INTEGER NOT NULL, before_data TEXT NOT NULL)");
            statements.execute("CREATE TABLE rollback_jobs(id INTEGER PRIMARY KEY, "
                    + "updated_at INTEGER NOT NULL, status TEXT NOT NULL)");
            statements.execute("CREATE TABLE rollback_job_changes(job_id INTEGER NOT NULL, "
                    + "sequence INTEGER NOT NULL, before_entity_data BLOB, "
                    + "PRIMARY KEY(job_id, sequence))");
            statements.execute("CREATE TABLE rollback_job_chunks(job_id INTEGER, chunk_x INTEGER)");
            try (PreparedStatement insert = source.prepareStatement(
                    "INSERT INTO block_changes(id,happened_at,before_data) VALUES(?,?,?)")) {
                for (int i = 1; i <= count; i++) {
                    insert.setInt(1, i);
                    insert.setLong(2, System.currentTimeMillis() - i);
                    insert.setString(3, "secret-private-block-state");
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            statements.execute("INSERT INTO rollback_jobs VALUES(1,1234,'COMPLETE')");
            statements.execute("INSERT INTO rollback_jobs VALUES(2,1235,'DONE')");
            try (PreparedStatement saved = source.prepareStatement(
                    "INSERT INTO rollback_job_changes VALUES(1,0,?)")) {
                saved.setBytes(1, PRIVATE_INVENTORY);
                saved.executeUpdate();
            }
            statements.execute("INSERT INTO rollback_job_chunks VALUES(1,7)");
        }
        return db;
    }

    private MaintenanceCoordinator activeMaintenance(long now) throws Exception {
        MaintenanceCoordinator coordinator = MaintenanceCoordinator.open(folder);
        coordinator.schedule(UUID.randomUUID(), now, now + 400,
                MaintenancePolicy.STRICT).get(10, TimeUnit.SECONDS);
        coordinator.activate().get(10, TimeUnit.SECONDS);
        assertEquals(MaintenancePhase.ACTIVE, coordinator.snapshot().phase());
        return coordinator;
    }

    private CompactionStoragePreflight.Report approved(CompactionStoragePreflight preflight)
            throws Exception {
        var report = CompactionStoragePreflight.evaluate(preflight.measure(),
                new CompactionStoragePreflight.Evidence(null, null, 256, true));
        assertTrue(report.mayReserve(), report.reasons().toString());
        return report;
    }

    private static long scalar(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
}
