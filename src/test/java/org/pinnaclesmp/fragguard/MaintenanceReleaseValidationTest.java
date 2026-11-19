package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Release gate #172: full, host-independent compaction -> full verification ->
 * strict comparison -> clean-shutdown receipt -> operator-equivalent file
 * moves -> activation. Never touches production or a real host file manager.
 *
 * Normal CI intentionally uses a modest, representative SQLite database.
 * Provisioned, isolated stress runners can set FG_COMPACTION_VALIDATION_ROWS
 * to as many as 57,000,000. This is NOT suitable for a shared CI runner or a
 * live production database; at-scale owner sign-off remains manual.
 */
@Tag("maintenance-release")
class MaintenanceReleaseValidationTest {
    private static final int DEFAULT_ROWS = 3_000;
    private static final int MAX_ROWS = 57_000_000;
    private static final String WORLD_UUID = "12345678-1234-4123-8123-123456789abc";

    @TempDir Path root;

    @Test
    void isolatedHistorySurvivesFullStrictMaintenanceAndManualActivation()
            throws Exception {
        int rows = configuredRows();
        long started = System.nanoTime();
        Path source = DatabaseDeepDiagnosticsTest.seededDatabase(root, rows);

        // Give the synthetic history real, consistent world UUIDs throughout
        // block history, rollback job, undo records, and chunk reservations.
        try (Connection writer = open(source); Statement sql = writer.createStatement()) {
            sql.execute("UPDATE block_changes SET world_uuid='" + WORLD_UUID + "'");
            sql.execute("UPDATE rollback_jobs SET world_uuid='" + WORLD_UUID + "'");
            sql.execute("UPDATE rollback_job_changes SET world_uuid='" + WORLD_UUID + "'");
            sql.execute("UPDATE rollback_job_chunks SET world_uuid='" + WORLD_UUID + "'");
            sql.execute("PRAGMA journal_mode=WAL");
            assertEquals(rows, scalar(sql, "SELECT COUNT(*) FROM block_changes"));
        }

        UUID operator = UUID.randomUUID();
        long cutoffLower;
        long cutoffUpper;
        Path backup;
        try (MaintenanceCoordinator coordinator = MaintenanceCoordinator.open(root);
             CompactionStoragePreflight preflight = new CompactionStoragePreflight(root);
             OnlineDatabaseCompaction compaction = new OnlineDatabaseCompaction(root, coordinator, preflight);
             CompactionCandidateVerifier verifier = new CompactionCandidateVerifier(root, coordinator);
             CompactionReplacementPolicy policy = new CompactionReplacementPolicy(root, coordinator)) {
            long now = System.currentTimeMillis() - 5_000;
            coordinator.schedule(operator, now, now + 1_000, MaintenancePolicy.STRICT)
                    .get(15, TimeUnit.SECONDS);
            coordinator.activate().get(15, TimeUnit.SECONDS);
            assertTrue(coordinator.blocksNewJobs());

            var capacity = CompactionStoragePreflight.evaluate(preflight.measure(),
                    new CompactionStoragePreflight.Evidence(null, null, 256, true));
            assertTrue(capacity.mayReserve(),
                    "Isolated host needs enough working storage: " + capacity.reasons());
            int timeout = rows > 100_000 ? 21_600 : 180;
            var copy = compaction.start(capacity).get(timeout, TimeUnit.SECONDS);
            assertTrue(Files.isRegularFile(source), "VACUUM INTO must preserve original");
            assertEquals(MaintenancePhase.VERIFYING, coordinator.snapshot().phase());
            assertEquals(rows, copy.lastIncludedHistoryId());
            cutoffLower = copy.invocationMillis();
            cutoffUpper = copy.completedMillis();
            assertTrue(cutoffUpper >= cutoffLower);
            assertEquals(cutoffLower, coordinator.snapshot().snapshotCutoffMillis(),
                    "A lower timestamp bound is NOT an exact SQLite commit cutoff");

            var verified = verifier.verify(copy).get(timeout, TimeUnit.SECONDS);
            assertEquals(rows, verified.tables().get("block_changes").rows());
            assertEquals(1, verified.tables().get("rollback_jobs").rows());
            assertEquals(1, verified.tables().get("rollback_job_changes").rows());
            assertEquals(1, verified.tables().get("rollback_job_chunks").rows());
            assertEquals(rows + 3L, verified.scannedRows());
            assertEquals(64, verified.candidateSha256().length());
            assertTrue(verified.tables().get("block_changes").blobBytes() >= rows * 128L);
            assertEquals(MaintenancePhase.VERIFYING, coordinator.snapshot().phase(),
                    "Verified never means automatically approved");

            try (Connection candidate = openReadOnly(copy.candidate());
                 Statement sql = candidate.createStatement()) {
                assertEquals(rows, scalar(sql, "SELECT COUNT(*) FROM block_changes"));
                assertEquals(rows, scalar(sql,
                        "SELECT COUNT(*) FROM block_changes WHERE world_uuid='" + WORLD_UUID + "'"));
                assertEquals(1, scalar(sql, "SELECT COUNT(*) FROM rollback_jobs"));
                assertEquals(1, scalar(sql, "SELECT COUNT(*) FROM rollback_job_changes"));
                assertEquals(1, scalar(sql, "SELECT COUNT(*) FROM rollback_job_chunks"));
                assertEquals(1, scalar(sql,
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='index'"
                        + " AND name='idx_fg_spatial_time'"));
                try (ResultSet result = sql.executeQuery(
                        "SELECT before_entity_data FROM rollback_job_changes WHERE job_id=1")) {
                    assertTrue(result.next());
                    assertArrayEquals(new byte[]{1, 2, 3}, result.getBytes(1));
                }
                try (ResultSet result = sql.executeQuery(
                        "SELECT before_entity_data FROM block_changes WHERE id=1")) {
                    assertTrue(result.next());
                    byte[] saved = result.getBytes(1);
                    assertEquals(128, saved.length);
                    for (byte value : saved) assertEquals((byte) 42, value);
                }
            }

            var pending = policy.prepare(operator, "").get(timeout, TimeUnit.SECONDS);
            assertFalse(pending.approved(), "STRICT must wait for final source check");
            assertTrue(policy.validateBeforeStoppingWriter().get(15, TimeUnit.SECONDS));
            // All synthetic SQLite handles are closed: finalize the source as a
            // stopped writer with WAL fully checkpointed, matching the real gate.
            try (Connection writer = open(source); Statement sql = writer.createStatement();
                 ResultSet checkpoint = sql.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                assertTrue(checkpoint.next());
                assertEquals(0, checkpoint.getInt(1), "no reader may block checkpoint");
            }
            var approved = policy.finalizeStrictAfterShutdown(true, true, true)
                    .get(timeout, TimeUnit.SECONDS);
            assertTrue(approved.approved(), "No post-snapshot edits means STRICT may pass");
            assertEquals(MaintenancePhase.READY_TO_SWAP, coordinator.snapshot().phase());
            assertTrue(CompactionReplacementPolicy.blocksWriterStartup(coordinator.snapshot()));

            var receipt = CompactionActivation.recordCleanShutdown(root, coordinator.snapshot(),
                    true, policy.finalizedStrictProof());
            assertTrue(Files.isRegularFile(receipt.path()));
            backup = receipt.backup();
            // Simulate the hosting file manager on a test directory only.
            Files.move(source, backup);
            for (String sidecar : new String[]{"-wal", "-shm"}) {
                Path originalSidecar = source.resolveSibling("fragguard.db" + sidecar);
                if (Files.exists(originalSidecar)) {
                    Files.move(originalSidecar, backup.resolveSibling(backup.getFileName() + sidecar));
                }
            }
            Files.move(copy.candidate(), source);
            CompactionActivation.requirePreparedActivation(root, coordinator.snapshot());
            var installed = CompactionActivation.verifyInstalled(root, coordinator.snapshot());
            assertEquals(rows + 3L, installed.verifiedRows());
            assertTrue(Files.isRegularFile(backup),
                    "The original must remain available for independent recovery");
            // Actual activation is executed by the next Paper startup. Here it
            // is deliberately simulated after closing the file handles.
        }

        try (MaintenanceCoordinator restart = MaintenanceCoordinator.open(root)) {
            assertEquals(MaintenancePhase.READY_TO_SWAP, restart.snapshot().phase());
            restart.beginActivation().get(15, TimeUnit.SECONDS);
            assertEquals(MaintenancePhase.ACTIVATING, restart.snapshot().phase());
            CompactionActivation.verifyRecovering(root, restart.snapshot());
            restart.finishVerifiedActivation().get(15, TimeUnit.SECONDS);
            assertEquals(MaintenancePhase.IDLE, restart.snapshot().phase());
            assertTrue(Files.isRegularFile(backup));
            try (Connection installed = openReadOnly(source);
                 Statement sql = installed.createStatement()) {
                assertEquals(rows, scalar(sql, "SELECT COUNT(*) FROM block_changes"));
                assertEquals(1, scalar(sql, "SELECT COUNT(*) FROM rollback_job_changes"));
                assertEquals("ok", string(sql, "PRAGMA integrity_check(1)"));
            }
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        System.out.println("FG #172 isolated strict compaction validation: rows=" + rows
                + ", elapsedMs=" + elapsedMillis
                + ", installedDbBytes=" + Files.size(source)
                + ", originalBackupBytes=" + Files.size(backup)
                + ", snapshotLowerBound=" + cutoffLower
                + ", snapshotUpperBound=" + cutoffUpper
                + ", liveServerApproval=NOT_TESTED");
    }

    @Test
    void sourceChangedAfterSnapshotRefusesStrictApprovalAndPreservesCandidate()
            throws Exception {
        Path source = DatabaseDeepDiagnosticsTest.seededDatabase(root, 60);
        UUID operator = UUID.randomUUID();
        try (MaintenanceCoordinator coordinator = MaintenanceCoordinator.open(root);
             CompactionStoragePreflight preflight = new CompactionStoragePreflight(root);
             OnlineDatabaseCompaction compaction = new OnlineDatabaseCompaction(root, coordinator, preflight);
             CompactionCandidateVerifier verifier = new CompactionCandidateVerifier(root, coordinator);
             CompactionReplacementPolicy policy = new CompactionReplacementPolicy(root, coordinator)) {
            long now = System.currentTimeMillis() - 5_000;
            coordinator.schedule(operator, now, now + 1_000, MaintenancePolicy.STRICT)
                    .get(15, TimeUnit.SECONDS);
            coordinator.activate().get(15, TimeUnit.SECONDS);
            var capacity = CompactionStoragePreflight.evaluate(preflight.measure(),
                    new CompactionStoragePreflight.Evidence(null, null, 256, true));
            assertTrue(capacity.mayReserve());
            var copy = compaction.start(capacity).get(120, TimeUnit.SECONDS);
            verifier.verify(copy).get(120, TimeUnit.SECONDS);
            policy.prepare(operator, "").get(30, TimeUnit.SECONDS);

            // Simulates a queued post-snapshot history write. STRICT cannot
            // pretend this source is identical to the frozen copied snapshot.
            try (Connection writer = open(source); Statement sql = writer.createStatement()) {
                sql.execute("""
                        INSERT INTO block_changes (happened_at,actor_uuid,actor_name,
                          world,world_uuid,x,y,z,chunk_x,chunk_z,action,before_data,after_data)
                        VALUES (999999,'release-test','release-test','world','uuid',
                          80,64,0,5,0,'PLACE','minecraft:air','minecraft:stone')
                        """);
                assertEquals(61, scalar(sql, "SELECT COUNT(*) FROM block_changes"));
            }
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> policy.validateBeforeStoppingWriter().get(30, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> policy.finalizeStrictAfterShutdown(true, true, true)
                            .get(120, TimeUnit.SECONDS));
            assertEquals(MaintenancePhase.VERIFYING, coordinator.snapshot().phase());
            assertEquals(60, readCount(copy.candidate(), "block_changes"));
            assertEquals(61, readCount(source, "block_changes"));
            assertTrue(Files.isRegularFile(copy.candidate()));
            assertTrue(Files.isRegularFile(source));
            assertFalse(Files.exists(root.resolve("backups")),
                    "Refused strict approval must not install or delete a database");
        }
    }

    private static int configuredRows() {
        String raw = System.getenv("FG_COMPACTION_VALIDATION_ROWS");
        int rows = raw == null || raw.isBlank() ? DEFAULT_ROWS : Integer.parseInt(raw);
        assertTrue(rows >= 100 && rows <= MAX_ROWS,
                "Only 100..57,000,000 synthetic rows; use a dedicated isolated runner for large values");
        return rows;
    }

    private static Connection open(Path database) throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private static Connection openReadOnly(Path database) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + database.toUri() + "?mode=ro");
    }

    private static long readCount(Path db, String table) throws Exception {
        try (Connection c = openReadOnly(db); Statement st = c.createStatement()) {
            return scalar(st, "SELECT COUNT(*) FROM " + table);
        }
    }

    private static long scalar(Statement sql, String query) throws Exception {
        try (ResultSet result = sql.executeQuery(query)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static String string(Statement sql, String query) throws Exception {
        try (ResultSet result = sql.executeQuery(query)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
