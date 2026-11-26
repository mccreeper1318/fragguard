package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #206: simulate a host kill after every individual atomic rename and verify
 * that the next ordinary startup can resume without losing old SQLite state.
 * Optional at-scale run: FG_COMPACTION_VALIDATION_ROWS=57000000 (storage permitting).
 */
class AutomaticCompactionActivationTest {
    @TempDir Path root;

    @Test
    void strictAutomaticallyPreservesOriginalAndActivatesVerifiedHistory()
            throws Exception {
        Path folder = root.resolve("strict");
        try (var job = prepared(folder, MaintenancePolicy.STRICT, rowCount())) {
            var state = job.state.snapshot();
            var receipt = receipt(job);
            assertFalse(AutomaticCompactionActivation.hasJournal(folder, state));
            AutomaticCompactionActivation.activateBeforeDatabaseOpen(folder, state);
            assertTrue(Files.isRegularFile(receipt.backup()));
            assertFalse(Files.exists(job.copy.candidate()));
            assertTrue(Files.exists(AutomaticCompactionActivation.journal(folder, state)));
            var verified = CompactionActivation.verifyInstalled(folder, state);
            assertEquals(rowCount() + 3L, verified.verifiedRows());
            AutomaticCompactionActivation.recordVerifiedBeforeDatabaseOpen(folder, state);
            job.state.beginActivation().get(20, TimeUnit.SECONDS);
            assertEquals(MaintenancePhase.ACTIVATING, job.state.snapshot().phase());
            job.state.finishVerifiedActivation().get(20, TimeUnit.SECONDS);
            assertEquals(MaintenancePhase.IDLE, job.state.snapshot().phase());
            assertTrue(Files.isRegularFile(receipt.backup()),
                    "Successful activation must never erase original history");
            assertTrue(Files.isRegularFile(receipt.path()),
                    "Provenance remains available after activation");
        }
    }

    @Test
    void snapshotAutomationStillRequiresExplicitAcknowledgementAndKeepsRecoverySource()
            throws Exception {
        Path folder = root.resolve("snapshot");
        try (var job = prepared(folder, MaintenancePolicy.MAINTENANCE_SNAPSHOT, 6)) {
            var receipt = receipt(job);
            AutomaticCompactionActivation.activateBeforeDatabaseOpen(folder,
                    job.state.snapshot());
            assertEquals("MAINTENANCE_SNAPSHOT",
                    CompactionActivation.verifyInstalled(folder, job.state.snapshot()).policy());
            assertTrue(Files.isRegularFile(receipt.backup()));
        }
    }

    @Test
    void everyRenameInterruptionCanResumeAndNeverMixOriginalSidecars()
            throws Exception {
        for (AutomaticCompactionActivation.Step crashAfter : List.of(
                AutomaticCompactionActivation.Step.ORIGINAL_BACKED_UP,
                AutomaticCompactionActivation.Step.WAL_BACKED_UP,
                AutomaticCompactionActivation.Step.SHM_BACKED_UP,
                AutomaticCompactionActivation.Step.CANDIDATE_INSTALLED)) {
            Path folder = root.resolve("interrupted-" + crashAfter);
            Path backup;
            String operationId;
            try (var job = prepared(folder, MaintenancePolicy.STRICT, 6)) {
                // The clean shutdown receipt must account for EXACTLY these
                // sidecar bytes, including the valid empty checkpointed WAL.
                Files.write(folder.resolve("fragguard.db-wal"), new byte[0]);
                Files.write(folder.resolve("fragguard.db-shm"), new byte[0]);
                receipt(job);
                var state = job.state.snapshot();
                operationId = state.operationId();
                backup = folder.resolve(CompactionActivation.backupName(state));

                IOException interrupted = assertThrows(IOException.class,
                        () -> AutomaticCompactionActivation.activateBeforeDatabaseOpen(
                                folder, state, finished -> {
                                    if (finished == crashAfter) {
                                        throw new IOException("simulated host kill after " + finished);
                                    }
                                }));
                assertTrue(interrupted.getMessage().contains("simulated host kill"));
                assertEquals(MaintenancePhase.READY_TO_SWAP, job.state.snapshot().phase());
                assertTrue(Files.isRegularFile(
                        AutomaticCompactionActivation.journal(folder, state)));
            }
            // A new JVM cannot rely on process-local watcher/connection state.
            try (MaintenanceCoordinator restarted = MaintenanceCoordinator.open(folder)) {
                var state = restarted.snapshot();
                assertEquals(operationId, state.operationId());
                assertEquals(MaintenancePhase.READY_TO_SWAP, state.phase());
                AutomaticCompactionActivation.activateBeforeDatabaseOpen(folder, state);
                AutomaticCompactionActivation.activateBeforeDatabaseOpen(folder, state);
                assertTrue(Files.isRegularFile(backup));
                assertTrue(Files.isRegularFile(Path.of(backup + "-wal")));
                assertTrue(Files.isRegularFile(Path.of(backup + "-shm")));
                assertFalse(Files.exists(folder.resolve("fragguard.db-wal")),
                        "Old WAL must not follow the installed candidate");
                assertFalse(Files.exists(folder.resolve("fragguard.db-shm")),
                        "Old SHM must not follow the installed candidate");
                assertEquals(9L,
                        CompactionActivation.verifyInstalled(folder, state).verifiedRows());
                AutomaticCompactionActivation.recordVerifiedBeforeDatabaseOpen(folder, state);
                restarted.beginActivation().get(20, TimeUnit.SECONDS);
                restarted.finishVerifiedActivation().get(20, TimeUnit.SECONDS);
                assertEquals(MaintenancePhase.IDLE, restarted.snapshot().phase());
            }
        }
    }

    @Test
    void tamperedBackupOrReceiptFailsClosedWithoutDeletingAnyFile() throws Exception {
        Path folder = root.resolve("tamper");
        try (var job = prepared(folder, MaintenancePolicy.STRICT, 6)) {
            var receipt = receipt(job);
            var state = job.state.snapshot();
            assertThrows(IOException.class, () -> AutomaticCompactionActivation
                    .activateBeforeDatabaseOpen(folder, state, finished -> {
                        if (finished == AutomaticCompactionActivation.Step.ORIGINAL_BACKED_UP) {
                            throw new IOException("simulated partial source move");
                        }
                    }));
            Files.writeString(receipt.backup(), "tampered original");
            assertThrows(IOException.class,
                    () -> AutomaticCompactionActivation.activateBeforeDatabaseOpen(folder, state));
            assertTrue(Files.isRegularFile(receipt.backup()));
            assertTrue(Files.isRegularFile(job.copy.candidate()));
            assertEquals(MaintenancePhase.READY_TO_SWAP, state.phase());
            assertThrows(IOException.class,
                    () -> CompactionActivation.verifyInstalled(folder, state));
        }
    }

    @Test
    void missingShutdownReceiptCannotAuthorizeAutomaticReplacement() throws Exception {
        Path folder = root.resolve("no-receipt");
        try (var job = prepared(folder, MaintenancePolicy.STRICT, 6)) {
            var state = job.state.snapshot();
            assertThrows(IOException.class,
                    () -> AutomaticCompactionActivation.activateBeforeDatabaseOpen(folder, state));
            assertTrue(Files.isRegularFile(job.source));
            assertTrue(Files.isRegularFile(job.copy.candidate()));
            assertFalse(AutomaticCompactionActivation.hasJournal(folder, state));
        }
    }

    @Test
    void anUnknownExistingBackupIsNeverOverwritten() throws Exception {
        Path folder = root.resolve("backup-collision");
        try (var job = prepared(folder, MaintenancePolicy.STRICT, 6)) {
            var receipt = receipt(job);
            Files.writeString(receipt.backup(), "preexisting recovery data");
            assertThrows(IOException.class,
                    () -> AutomaticCompactionActivation.activateBeforeDatabaseOpen(
                            folder, job.state.snapshot()));
            assertEquals("preexisting recovery data", Files.readString(receipt.backup()));
            assertTrue(Files.isRegularFile(job.source));
            assertTrue(Files.isRegularFile(job.copy.candidate()));
        }
    }

    @Test
    void gracefulStopIsPermittedOnlyForSameApprovedOperation() {
        var state = MaintenanceSnapshot.scheduled(UUID.randomUUID(), 1000, 5000,
                MaintenancePolicy.STRICT)
                .withPhase(MaintenancePhase.ACTIVE, "active")
                .withCandidate("fragguard-compact-test.db", "copy")
                .withCutoff(2000, "copied")
                .withPhase(MaintenancePhase.VERIFYING, "verify")
                .withManifest("fragguard-compact-test.manifest", "verified");
        assertTrue(FragGuardPlugin.mayStopForApprovedCompaction(
                state.operationId(), MaintenancePolicy.STRICT, state, true));
        assertFalse(FragGuardPlugin.mayStopForApprovedCompaction(
                UUID.randomUUID().toString(), MaintenancePolicy.STRICT, state, true));
        assertFalse(FragGuardPlugin.mayStopForApprovedCompaction(
                state.operationId(), MaintenancePolicy.STRICT, state, false));
        assertFalse(FragGuardPlugin.mayStopForApprovedCompaction(
                state.operationId(), MaintenancePolicy.MAINTENANCE_SNAPSHOT, state, true));
        assertFalse(FragGuardPlugin.mayStopForApprovedCompaction(
                state.operationId(), MaintenancePolicy.STRICT,
                state.withPhase(MaintenancePhase.FAILED, "failed"), true));
    }

    private int rowCount() {
        String value = System.getenv("FG_COMPACTION_VALIDATION_ROWS");
        int rows = value == null || value.isBlank() ? 3_000 : Integer.parseInt(value);
        assertTrue(rows >= 100 && rows <= 57_000_000,
                "At-scale tests require 100..57,000,000 isolated synthetic history rows");
        return rows;
    }

    private CompactionReplacementPolicyTest.Setup prepared(
            Path folder, MaintenancePolicy policy, int rows) throws Exception {
        Files.createDirectories(folder);
        var setup = new CompactionReplacementPolicyTest.Setup(folder, policy, rows);
        if (policy == MaintenancePolicy.STRICT) {
            setup.policy.prepare(setup.operator, "").get(21_630, TimeUnit.SECONDS);
            assertTrue(setup.policy.validateBeforeStoppingWriter()
                    .get(30, TimeUnit.SECONDS));
            assertTrue(setup.policy.finalizeStrictAfterShutdown(true, true, true)
                    .get(30, TimeUnit.SECONDS).approved());
        } else {
            setup.policy.prepare(setup.operator,
                    CompactionReplacementPolicy.SNAPSHOT_ACKNOWLEDGEMENT)
                    .get(21_630, TimeUnit.SECONDS);
        }
        assertEquals(MaintenancePhase.READY_TO_SWAP, setup.state.snapshot().phase());
        return setup;
    }

    private CompactionActivation.ShutdownReceipt receipt(
            CompactionReplacementPolicyTest.Setup setup) throws IOException {
        if (setup.state.snapshot().policy() == MaintenancePolicy.STRICT) {
            return CompactionActivation.recordCleanShutdown(
                    setup.source.getParent(), setup.state.snapshot(), true,
                    setup.policy.finalizedStrictProof());
        }
        return CompactionActivation.recordCleanShutdown(
                setup.source.getParent(), setup.state.snapshot(), true);
    }
}
