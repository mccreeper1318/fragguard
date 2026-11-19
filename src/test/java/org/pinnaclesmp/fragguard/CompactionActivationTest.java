package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CompactionActivationTest {
    @TempDir Path root;

    @Test
    void manualStrictSwapValidatesOriginalBackupAndInstalledHistoryBeforeClearingState()
            throws Exception {
        try (var job = prepared(MaintenancePolicy.STRICT)) {
            var receipt = CompactionActivation.recordCleanShutdown(
                    root, job.state.snapshot(), true, job.policy.finalizedStrictProof());
            assertTrue(Files.isRegularFile(receipt.path()));
            assertTrue(Files.isRegularFile(job.source));
            assertTrue(Files.isRegularFile(job.copy.candidate()));
            manualSwap(job);
            assertTrue(Files.isRegularFile(receipt.backup()));
            assertFalse(Files.exists(job.copy.candidate()));
            CompactionActivation.requirePreparedActivation(root, job.state.snapshot());
            try (MaintenanceCoordinator reopened = MaintenanceCoordinator.open(root)) {
                assertEquals(MaintenancePhase.READY_TO_SWAP, reopened.snapshot().phase());
                var verified = CompactionActivation.verifyInstalled(root, reopened.snapshot());
                assertEquals(receipt.backup(), verified.backup());
                assertEquals(receipt.candidateSha256(), verified.candidateSha256());
                assertEquals(9L, verified.verifiedRows(),
                        "Six history records, a rollback job, undo snapshot and chunk reservation");
                // The plugin persists ACTIVATING before DB.init() can
                // switch journal_mode or create fresh WAL/SHM sidecars.
                reopened.beginActivation().get(10, TimeUnit.SECONDS);
                assertEquals(MaintenancePhase.ACTIVATING, reopened.snapshot().phase());
                // This hook is called only AFTER successful DB.init().
                reopened.finishVerifiedActivation().get(10, TimeUnit.SECONDS);
                assertEquals(MaintenancePhase.IDLE, reopened.snapshot().phase());
            }
            assertTrue(Files.isRegularFile(receipt.backup()),
                    "successful activation must NEVER delete the original");
            assertTrue(Files.isRegularFile(receipt.path()),
                    "preserve provenance for later operator recovery");
        }
        try (var restarted = MaintenanceCoordinator.open(root)) {
            assertEquals(MaintenancePhase.IDLE, restarted.snapshot().phase());
        }
    }

    @Test
    void strictFastReceiptRefusesCandidateChangesAfterOnlineFullComparison()
            throws Exception {
        try (var job = prepared(MaintenancePolicy.STRICT)) {
            Path candidate = job.copy.candidate();
            var before = Files.getLastModifiedTime(candidate);
            Files.setLastModifiedTime(candidate,
                    java.nio.file.attribute.FileTime.fromMillis(before.toMillis() + 10_000));
            assertThrows(IOException.class,
                    () -> CompactionActivation.recordCleanShutdown(root, job.state.snapshot(),
                            true, job.policy.finalizedStrictProof()));
            assertFalse(Files.exists(
                    CompactionActivation.receiptPath(root, job.state.snapshot())));
            assertTrue(Files.isRegularFile(job.source));
            assertTrue(Files.isRegularFile(candidate));
        }
    }

    @Test
    void strictFastReceiptRejectsReportedFailedShutdownWithoutAnyFileMove()
            throws Exception {
        try (var job = prepared(MaintenancePolicy.STRICT)) {
            assertThrows(IOException.class,
                    () -> CompactionActivation.recordCleanShutdown(root, job.state.snapshot(),
                            false, job.policy.finalizedStrictProof()));
            assertFalse(Files.exists(
                    CompactionActivation.receiptPath(root, job.state.snapshot())));
        }
    }

    @Test
    void acknowledgedSnapshotCanActivateAlthoughOriginalHasNewerHistoryAndJobs()
            throws Exception {
        try (var job = prepared(MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            try (Connection source = DriverManager.getConnection("jdbc:sqlite:" + job.source);
                 Statement sql = source.createStatement()) {
                sql.execute("UPDATE rollback_jobs SET updated_at=updated_at+500 WHERE id=1");
                sql.execute("INSERT INTO block_changes "
                        + "(happened_at,actor_uuid,actor_name,world,world_uuid,x,y,z,"
                        + "chunk_x,chunk_z,action,before_data,after_data) "
                        + "VALUES(999999,'actor','actor','world','uuid',32,64,0,2,0,"
                        + "'PLACE','minecraft:air','minecraft:stone')");
            }
            var receipt = CompactionActivation.recordCleanShutdown(root, job.state.snapshot(), true);
            assertNotEquals(receipt.originalSha256(), receipt.candidateSha256(),
                    "source is expected to include changes after the copied WAL snapshot");
            manualSwap(job);
            var installed = CompactionActivation.verifyInstalled(root, job.state.snapshot());
            assertEquals(6L, installed.verifiedRows()
                    - 3L, "the earlier copied six history entries are what is actually installed");
            assertEquals(MaintenancePhase.READY_TO_SWAP, job.state.snapshot().phase());
            assertTrue(Files.isRegularFile(receipt.backup()));
        }
    }

    @Test
    void snapshotApprovalUsesOnlineFingerprintsAndCleanCheckpointWithoutShutdownFileHashes()
            throws Exception {
        try (var job = new CompactionReplacementPolicyTest.Setup(root,
                MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            // Snapshot consent permits loss of history between VACUUM snapshot
            // and approval. The original backup hash must reflect those rows.
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + job.source);
                 Statement sql = connection.createStatement()) {
                sql.execute("UPDATE rollback_jobs SET updated_at=updated_at+250 WHERE id=1");
                sql.execute("INSERT INTO block_changes "
                        + "(happened_at,actor_uuid,actor_name,world,world_uuid,x,y,z,"
                        + "chunk_x,chunk_z,action,before_data,after_data) "
                        + "VALUES(999999,'actor','actor','world','uuid',32,64,0,2,0,"
                        + "'PLACE','minecraft:air','minecraft:stone')");
            }
            var approved = job.policy.prepare(job.operator,
                    CompactionReplacementPolicy.SNAPSHOT_ACKNOWLEDGEMENT)
                    .get(90, TimeUnit.SECONDS);
            assertTrue(approved.approved());
            assertTrue(job.policy.hasPendingSnapshotProof());
            assertTrue(job.policy.validateBeforeStoppingWriter().get(10, TimeUnit.SECONDS));
            // A legitimate shutdown checkpoint is allowed to advance
            // PRAGMA data_version without committing new history.
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + job.source);
                 Statement sql = connection.createStatement();
                 var checkpoint = sql.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                assertTrue(checkpoint.next());
                assertEquals(0, checkpoint.getInt(1));
            }
            var proof = job.policy.finalizeSnapshotAfterShutdown(true, true, true)
                    .get(15, TimeUnit.SECONDS);
            assertEquals(MaintenancePolicy.MAINTENANCE_SNAPSHOT, proof.policy());
            var receipt = CompactionActivation.recordCleanShutdown(root,
                    job.state.snapshot(), true, proof);
            assertEquals(proof.originalSha256(), receipt.originalSha256());
            assertNotEquals(receipt.originalSha256(), receipt.candidateSha256());
            assertTrue(Files.isRegularFile(receipt.path()));
            manualSwap(job);
            var installed = CompactionActivation.verifyInstalled(root, job.state.snapshot());
            assertEquals(9L, installed.verifiedRows(),
                    "Snapshot copy must preserve six original changes and saved jobs");
            assertTrue(Files.isRegularFile(receipt.backup()));
        }
    }

    @Test
    void snapshotProofRefusesChangesAfterFingerprintAndNeverWritesReceipt() throws Exception {
        try (var job = new CompactionReplacementPolicyTest.Setup(root,
                MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            job.policy.prepare(job.operator, CompactionReplacementPolicy.SNAPSHOT_ACKNOWLEDGEMENT)
                    .get(90, TimeUnit.SECONDS);
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + job.source);
                 Statement sql = connection.createStatement()) {
                sql.execute("UPDATE rollback_jobs SET updated_at=updated_at+500 WHERE id=1");
            }
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> job.policy.validateBeforeStoppingWriter().get(15, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> job.policy.finalizeSnapshotAfterShutdown(true, true, true)
                            .get(15, TimeUnit.SECONDS));
            assertFalse(Files.exists(CompactionActivation.receiptPath(root, job.state.snapshot())));
            assertTrue(Files.isRegularFile(job.source));
            assertTrue(Files.isRegularFile(job.copy.candidate()));
        }
    }

    @Test
    void snapshotProofCannotBeReusedByAnotherPolicyInstanceAfterForceKill() throws Exception {
        try (var job = new CompactionReplacementPolicyTest.Setup(root,
                MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            job.policy.prepare(job.operator, CompactionReplacementPolicy.SNAPSHOT_ACKNOWLEDGEMENT)
                    .get(90, TimeUnit.SECONDS);
            try (var other = new CompactionReplacementPolicy(root, job.state)) {
                assertFalse(other.hasPendingSnapshotProof());
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> other.finalizeSnapshotAfterShutdown(true, true, true)
                                .get(15, TimeUnit.SECONDS));
            }
            assertFalse(Files.exists(CompactionActivation.receiptPath(root, job.state.snapshot())));
        }
    }

    @Test
    void shutdownReceiptCannotBeCreatedIfCheckpointWasNotConfirmed()
            throws Exception {
        try (var job = prepared(MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            assertThrows(IOException.class,
                    () -> CompactionActivation.recordCleanShutdown(root, job.state.snapshot(), false));
            assertFalse(Files.exists(CompactionActivation.receiptPath(root, job.state.snapshot())));
            Files.write(root.resolve("fragguard.db-wal"), new byte[]{1, 2, 3});
            assertThrows(IOException.class,
                    () -> CompactionActivation.recordCleanShutdown(root, job.state.snapshot(), true),
                    "Nonempty WAL cannot be thrown away, separated or used with a new candidate");
            assertFalse(Files.exists(CompactionActivation.receiptPath(root, job.state.snapshot())));
        }
    }

    @Test
    void noFileSwapOrIncompleteSwapNeverOpensTheLiveDatabase() throws Exception {
        try (var job = prepared(MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            CompactionActivation.recordCleanShutdown(root, job.state.snapshot(), true);
            assertThrows(IOException.class,
                    () -> CompactionActivation.verifyInstalled(root, job.state.snapshot()),
                    "old database is still active, candidate not installed");
            Path backup = root.resolve(CompactionActivation.backupName(job.state.snapshot()));
            Files.move(job.source, backup);
            assertTrue(Files.isRegularFile(backup));
            assertThrows(IOException.class, () -> MaintenanceCoordinator.open(root),
                    "original renamed but candidate not activated is not a valid startup");
            assertTrue(Files.isRegularFile(job.copy.candidate()));
        }
    }

    @Test
    void wrongOrMissingOriginalBackupIsNeverAccepted() throws Exception {
        try (var job = prepared(MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            CompactionActivation.recordCleanShutdown(root, job.state.snapshot(), true);
            manualSwap(job);
            Path backup = root.resolve(CompactionActivation.backupName(job.state.snapshot()));
            Files.writeString(backup, "not-the-checkpointed-original");
            assertThrows(IOException.class,
                    () -> CompactionActivation.verifyInstalled(root, job.state.snapshot()));
            assertEquals(MaintenancePhase.READY_TO_SWAP, job.state.snapshot().phase());
        }
    }

    @Test
    void missingOriginalBackupCannotBecomeSuccessfulActivation() throws Exception {
        try (var job = prepared(MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            CompactionActivation.recordCleanShutdown(root, job.state.snapshot(), true);
            // Simulate an operator wrongly overwriting the old DB without
            // preserving a separate original backup. Never auto-create one.
            Files.move(job.copy.candidate(), job.source, StandardCopyOption.REPLACE_EXISTING);
            assertThrows(IOException.class, () -> CompactionActivation.verifyInstalled(root, job.state.snapshot()));
            assertFalse(Files.exists(root.resolve(CompactionActivation.backupName(job.state.snapshot()))));
        }
    }

    @Test
    void incorrectOrMissingMatchingOriginalWalSidecarIsRejected() throws Exception {
        try (var job = prepared(MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            // A zero-byte WAL can remain after a *confirmed* checkpoint and
            // must be renamed alongside the preserved original if it exists.
            Files.write(root.resolve("fragguard.db-wal"), new byte[0]);
            CompactionActivation.recordCleanShutdown(root, job.state.snapshot(), true);
            manualSwap(job);
            Path backupWal = root.resolve(CompactionActivation.backupName(job.state.snapshot()) + "-wal");
            assertTrue(Files.exists(backupWal));
            Files.write(backupWal, new byte[]{42});
            assertThrows(IOException.class, () -> MaintenanceCoordinator.open(root),
                    "a modified backup sidecar is not the one from the stopped source");
        }
    }

    @Test
    void activeWalSidecarFromOriginalMustNotBePairedWithInstalledCandidate()
            throws Exception {
        try (var job = prepared(MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            CompactionActivation.recordCleanShutdown(root, job.state.snapshot(), true);
            manualSwap(job);
            Files.write(root.resolve("fragguard.db-wal"), new byte[]{42});
            assertThrows(IOException.class,
                    () -> CompactionActivation.verifyInstalled(root, job.state.snapshot()));
            assertTrue(Files.isRegularFile(
                    root.resolve(CompactionActivation.backupName(job.state.snapshot()))));
        }
    }

    @Test
    void alteredOrMissingShutdownReceiptCannotAuthorizeStartup() throws Exception {
        try (var job = prepared(MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            var receipt = CompactionActivation.recordCleanShutdown(root, job.state.snapshot(), true);
            manualSwap(job);
            Properties forged = load(receipt.path());
            forged.setProperty("maintenance_operation_id", "incorrect-operation");
            try (var out = Files.newOutputStream(receipt.path())) {
                forged.store(out, "invalid receipt");
            }
            assertThrows(IOException.class, () -> MaintenanceCoordinator.open(root));
            assertEquals(MaintenancePhase.READY_TO_SWAP, job.state.snapshot().phase());
        }
    }

    @Test
    void copyCorruptionAfterManualSwapFailsWithoutResettingMaintenance()
            throws Exception {
        try (var job = prepared(MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            CompactionActivation.recordCleanShutdown(root, job.state.snapshot(), true);
            manualSwap(job);
            Files.write(job.source, new byte[]{5, 9, 12});
            assertThrows(IOException.class,
                    () -> CompactionActivation.verifyInstalled(root, job.state.snapshot()));
            assertEquals(MaintenancePhase.READY_TO_SWAP, job.state.snapshot().phase());
        }
    }

    @Test
    void tamperedVerifiedManifestAfterReceiptIsRejectedEvenIfCandidateIsIntact()
            throws Exception {
        try (var job = prepared(MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            CompactionActivation.recordCleanShutdown(root, job.state.snapshot(), true);
            manualSwap(job);
            Path manifest = job.copy.candidate().resolveSibling(
                    job.copy.candidate().getFileName().toString().replace(".db", ".manifest"));
            Properties altered = load(manifest);
            altered.setProperty("verified_scanned_rows", "999999");
            try (var out = Files.newOutputStream(manifest)) {
                altered.store(out, "changed after clean shutdown");
            }
            assertThrows(IOException.class, () -> MaintenanceCoordinator.open(root));
        }
    }

    @Test
    void manualInstructionsNeverRequestDeletingOrMixingOriginalSidecars() throws Exception {
        try (var job = prepared(MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            var instructions = String.join("\n",
                    CompactionActivation.manualInstructions(root, job.state.snapshot()));
            assertTrue(instructions.contains("fragguard.db-wal"));
            assertTrue(instructions.contains("fragguard-original-"));
            assertTrue(instructions.contains("Paper server completely"));
            assertTrue(instructions.contains("history"));
            assertTrue(instructions.contains("do not delete"));
            assertTrue(instructions.contains("is NOT rolled back"));
        }
    }

    @Test
    void interruptedBeforeSqliteOpenRecoversDurableActivatingState() throws Exception {
        try (var job = prepared(MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            CompactionActivation.recordCleanShutdown(root, job.state.snapshot(), true);
            manualSwap(job);
            CompactionActivation.verifyInstalled(root, job.state.snapshot());
            job.state.beginActivation().get(10, TimeUnit.SECONDS);
            assertEquals(MaintenancePhase.ACTIVATING, job.state.snapshot().phase());
            assertTrue(job.state.snapshot().blocksNewJobs());
        }
        try (MaintenanceCoordinator recovering = MaintenanceCoordinator.open(root)) {
            assertEquals(MaintenancePhase.ACTIVATING, recovering.snapshot().phase());
            assertEquals(9, CompactionActivation.verifyRecovering(root,
                    recovering.snapshot()).verifiedRows());
            recovering.finishVerifiedActivation().get(10, TimeUnit.SECONDS);
            assertEquals(MaintenancePhase.IDLE, recovering.snapshot().phase());
        }
    }

    @Test
    void interruptedAfterSqliteChangesJournalModeStillRecoversWithoutDeletingBackup()
            throws Exception {
        Path backup;
        try (var job = prepared(MaintenancePolicy.STRICT)) {
            CompactionActivation.recordCleanShutdown(root, job.state.snapshot(), true);
            manualSwap(job);
            CompactionActivation.verifyInstalled(root, job.state.snapshot());
            job.state.beginActivation().get(10, TimeUnit.SECONDS);
            backup = root.resolve(CompactionActivation.backupName(job.state.snapshot()));
            // DB.init() switches the copied DB's header to WAL; it can create
            // NEW -wal/-shm sidecars before finishVerifiedActivation commits.
            try (Connection sqlite = DriverManager.getConnection("jdbc:sqlite:" + job.source);
                 Statement command = sqlite.createStatement()) {
                command.execute("PRAGMA journal_mode=WAL");
            }
        }
        assertTrue(Files.isRegularFile(backup));
        try (MaintenanceCoordinator recovering = MaintenanceCoordinator.open(root)) {
            assertEquals(MaintenancePhase.ACTIVATING, recovering.snapshot().phase());
            var scan = CompactionActivation.verifyRecovering(root, recovering.snapshot());
            assertEquals(9, scan.verifiedRows());
            recovering.finishVerifiedActivation().get(10, TimeUnit.SECONDS);
            assertEquals(MaintenancePhase.IDLE, recovering.snapshot().phase());
        }
        assertTrue(Files.isRegularFile(backup), "manual original backup must survive recovery");
    }

    @Test
    void interruptedActivationRejectsChangedRollbackJobsAndPreservesLockedState()
            throws Exception {
        try (var job = prepared(MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            CompactionActivation.recordCleanShutdown(root, job.state.snapshot(), true);
            manualSwap(job);
            CompactionActivation.verifyInstalled(root, job.state.snapshot());
            job.state.beginActivation().get(10, TimeUnit.SECONDS);
            try (Connection sqlite = DriverManager.getConnection("jdbc:sqlite:" + job.source);
                 Statement command = sqlite.createStatement()) {
                command.execute("UPDATE rollback_jobs SET updated_at=updated_at+501 WHERE id=1");
            }
        }
        try (MaintenanceCoordinator recovering = MaintenanceCoordinator.open(root)) {
            assertEquals(MaintenancePhase.ACTIVATING, recovering.snapshot().phase());
            assertThrows(IOException.class,
                    () -> CompactionActivation.verifyRecovering(root, recovering.snapshot()));
            assertEquals(MaintenancePhase.ACTIVATING, recovering.snapshot().phase(),
                    "failed recovery cannot silently clear durable maintenance");
            assertTrue(Files.isRegularFile(root.resolve("fragguard.db")));
        }
    }

    private CompactionReplacementPolicyTest.Setup prepared(MaintenancePolicy policy)
            throws Exception {
        var setup = new CompactionReplacementPolicyTest.Setup(root, policy);
        if (policy == MaintenancePolicy.STRICT) {
            setup.policy.prepare(setup.operator, "").get(30, TimeUnit.SECONDS);
            assertTrue(setup.policy.validateBeforeStoppingWriter().get(10, TimeUnit.SECONDS));
            assertTrue(setup.policy.finalizeStrictAfterShutdown(true, true, true)
                    .get(90, TimeUnit.SECONDS).approved());
        } else {
            setup.policy.prepare(setup.operator,
                    CompactionReplacementPolicy.SNAPSHOT_ACKNOWLEDGEMENT)
                    .get(30, TimeUnit.SECONDS);
        }
        assertEquals(MaintenancePhase.READY_TO_SWAP, setup.state.snapshot().phase());
        return setup;
    }

    private static void manualSwap(CompactionReplacementPolicyTest.Setup job)
            throws IOException {
        Path original = job.source;
        Path backup = original.resolveSibling(CompactionActivation.backupName(job.state.snapshot()));
        Files.move(original, backup);
        for (String suffix : new String[]{"-wal", "-shm"}) {
            Path sidecar = original.resolveSibling(original.getFileName() + suffix);
            if (Files.exists(sidecar)) {
                Files.move(sidecar, backup.resolveSibling(backup.getFileName() + suffix));
            }
        }
        Files.move(job.copy.candidate(), original);
    }

    private static Properties load(Path file) throws Exception {
        Properties p = new Properties();
        try (var input = Files.newInputStream(file)) {
            p.load(input);
        }
        return p;
    }
}
