package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CompactionReplacementPolicyTest {
    @TempDir Path root;
    private static final String CONSENT = CompactionReplacementPolicy.SNAPSHOT_ACKNOWLEDGEMENT;

    @Test
    void snapshotModeNeedsExactExplicitConsentAndRecordsOperatorCutoffAndWarnings()
            throws Exception {
        try (Setup setup = new Setup(root, MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            assertThrows(ExecutionException.class,
                    () -> setup.policy.prepare(setup.operator, "").get(30, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,
                    () -> setup.policy.prepare(setup.operator, "I accept loss").get(30, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,
                    () -> setup.policy.prepare(UUID.randomUUID(), CONSENT).get(30, TimeUnit.SECONDS));
            assertEquals(MaintenancePhase.VERIFYING, setup.state.snapshot().phase());
            assertThrows(java.util.concurrent.CompletionException.class,
                    () -> setup.state.markReady().join(), "FORCE never bypasses policy approval");

            var approval = setup.policy.prepare(setup.operator, CONSENT).get(60, TimeUnit.SECONDS);
            assertTrue(approval.approved());
            assertEquals(MaintenancePolicy.MAINTENANCE_SNAPSHOT, approval.policy());
            assertEquals(setup.operator.toString(), approval.consentOperatorUuid());
            assertTrue(approval.consentMillis() >= setup.copy.completedMillis());
            assertEquals(setup.copy.invocationMillis(), approval.snapshotLowerBoundMillis());
            assertEquals(setup.copy.completedMillis(), approval.snapshotUpperBoundMillis());
            assertTrue(approval.detail().contains("Minecraft world is NEVER rolled back"));
            assertEquals(MaintenancePhase.READY_TO_SWAP, setup.state.snapshot().phase());
            assertTrue(CompactionReplacementPolicy.blocksWriterStartup(setup.state.snapshot()),
                    "approved snapshot may not reopen the original SQLite writer on restart");
            assertTrue(Files.isRegularFile(approval.policyFile()));
            Properties props = load(approval.policyFile());
            assertEquals("APPROVED", props.getProperty("status"));
            assertEquals("MAINTENANCE_SNAPSHOT", props.getProperty("policy"));
            assertEquals(setup.state.snapshot().operationId(),
                    props.getProperty("maintenance_operation_id"));
            assertEquals(setup.copy.candidate().getFileName().toString(),
                    props.getProperty("candidate"));
            assertEquals(setup.operator.toString(), props.getProperty("consent_operator_uuid"));
            assertEquals("BETWEEN_INVOCATION_AND_COMPLETION",
                    props.getProperty("snapshot_time_kind"));
            assertFalse(Files.readString(approval.policyFile()).contains(root.toString()));
        }
        try (MaintenanceCoordinator restart = MaintenanceCoordinator.open(root)) {
            assertEquals(MaintenancePhase.READY_TO_SWAP, restart.snapshot().phase());
            CompactionReplacementPolicy.requireApproved(root, restart.snapshot());
            assertTrue(restart.snapshot().blocksNewJobs());
        }
    }

    @Test
    void snapshotModeExplicitlyAllowsNewHistoryAfterTheCopiedSnapshot() throws Exception {
        try (Setup setup = new Setup(root, MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            // The live source changes after the candidate's genuine WAL snapshot.
            try (Connection source = DriverManager.getConnection("jdbc:sqlite:" + setup.source);
                 Statement sql = source.createStatement()) {
                sql.execute("UPDATE rollback_jobs SET updated_at = updated_at + 500 WHERE id = 1");
                sql.execute("INSERT INTO block_changes "
                        + "(happened_at,actor_uuid,actor_name,world,world_uuid,x,y,z,"
                        + "chunk_x,chunk_z,action,before_data,after_data) "
                        + "VALUES(999999,'actor','actor','world','uuid',32,64,0,2,0,"
                        + "'PLACE','minecraft:air','minecraft:stone')");
            }
            var result = setup.policy.prepare(setup.operator, CONSENT).get(60, TimeUnit.SECONDS);
            assertTrue(result.approved());
            assertEquals(MaintenancePhase.READY_TO_SWAP, setup.state.snapshot().phase());
            assertTrue(result.detail().contains("rollback/undo"));
        }
    }

    @Test
    void strictModeCannotBeApprovedWhileSourceCouldStillWrite() throws Exception {
        try (Setup setup = new Setup(root, MaintenancePolicy.STRICT)) {
            assertThrows(ExecutionException.class,
                    () -> setup.policy.prepare(setup.operator, CONSENT).get(30, TimeUnit.SECONDS));
            var pending = setup.policy.prepare(setup.operator, "").get(30, TimeUnit.SECONDS);
            assertFalse(pending.approved());
            assertEquals("PENDING_FINAL_SHUTDOWN", pending.status());
            assertEquals(MaintenancePhase.VERIFYING, setup.state.snapshot().phase());
            assertEquals("PENDING_FINAL_SHUTDOWN", load(pending.policyFile()).getProperty("status"));
            assertThrows(ExecutionException.class,
                    () -> setup.policy.finalizeStrictAfterShutdown(false, true, true)
                            .get(30, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,
                    () -> setup.policy.finalizeStrictAfterShutdown(true, false, true)
                            .get(30, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,
                    () -> setup.policy.finalizeStrictAfterShutdown(true, true, false)
                            .get(30, TimeUnit.SECONDS));
            assertEquals(MaintenancePhase.VERIFYING, setup.state.snapshot().phase());
            assertThrows(java.util.concurrent.CompletionException.class,
                    () -> setup.state.markReady().join());
            assertFalse(Files.exists(root.resolve("compaction-candidates")
                    .resolve(setup.copy.candidate().getFileName().toString()
                            .replace(".db", ".policy.properties"))));
        }
    }

    @Test
    void strictModeApprovesOnlyAfterFullCheckpointedSourceEqualsCandidate() throws Exception {
        try (Setup setup = new Setup(root, MaintenancePolicy.STRICT)) {
            setup.policy.prepare(setup.operator, "").get(30, TimeUnit.SECONDS);
            assertTrue(setup.policy.validateBeforeStoppingWriter().get(10, TimeUnit.SECONDS));
            var approved = setup.policy.finalizeStrictAfterShutdown(true, true, true)
                    .get(90, TimeUnit.SECONDS);
            assertTrue(approved.approved());
            assertEquals(MaintenancePhase.READY_TO_SWAP, setup.state.snapshot().phase());
            assertTrue(CompactionReplacementPolicy.blocksWriterStartup(setup.state.snapshot()),
                    "strict approval may not become stale after writer restarts");
            Properties proof = load(approved.policyFile());
            assertEquals("STRICT", proof.getProperty("policy"));
            assertEquals("APPROVED", proof.getProperty("status"));
            assertEquals("0", proof.getProperty("consent_at_millis"));
            assertEquals(64, proof.getProperty("strict_source_final_sha256").length());
            CompactionReplacementPolicy.requireApproved(root, setup.state.snapshot());
        }
        try (MaintenanceCoordinator restart = MaintenanceCoordinator.open(root)) {
            assertEquals(MaintenancePhase.READY_TO_SWAP, restart.snapshot().phase());
            CompactionReplacementPolicy.requireApproved(root, restart.snapshot());
        }
    }

    @Test
    void cancellingMaintenanceImmediatelyClosesOldStrictWatcher() throws Exception {
        try (Setup setup = new Setup(root, MaintenancePolicy.STRICT)) {
            setup.policy.prepare(setup.operator, "").get(90, TimeUnit.SECONDS);
            assertTrue(setup.policy.hasLiveStrictProof());
            setup.state.cancel("operator cancelled").get(10, TimeUnit.SECONDS);
            assertTrue(setup.policy.releaseStrictProofAfterCancel().get(10, TimeUnit.SECONDS));
            assertFalse(setup.policy.hasLiveStrictProof(),
                    "A cancelled operation must not retain a stale SQLite watcher");
            assertThrows(ExecutionException.class,
                    () -> setup.policy.finalizeStrictAfterShutdown(true, true, true)
                            .get(10, TimeUnit.SECONDS));
            assertFalse(Files.exists(CompactionActivation.receiptPath(
                    root, setup.state.snapshot())));
        }
    }

    @Test
    void strictShutdownCannotReuseProofFromAnotherProcessOrWorker() throws Exception {
        try (Setup setup = new Setup(root, MaintenancePolicy.STRICT)) {
            setup.policy.prepare(setup.operator, "").get(90, TimeUnit.SECONDS);
            // New policy instance models a host force-kill and restart: only
            // persisted PENDING metadata survives, never the watcher/proof.
            try (var replacementAfterRestart =
                         new CompactionReplacementPolicy(root, setup.state)) {
                assertThrows(ExecutionException.class,
                        () -> replacementAfterRestart.finalizeStrictAfterShutdown(true, true, true)
                                .get(15, TimeUnit.SECONDS));
            }
            assertEquals(MaintenancePhase.VERIFYING, setup.state.snapshot().phase());
            assertThrows(IllegalStateException.class,
                    setup.policy::finalizedStrictProof);
            assertFalse(Files.exists(CompactionActivation.receiptPath(
                    root, setup.state.snapshot())));
        }
    }

    @Test
    void strictShutdownRejectsSourceFileChangedWithoutSqliteCommit() throws Exception {
        try (Setup setup = new Setup(root, MaintenancePolicy.STRICT)) {
            setup.policy.prepare(setup.operator, "").get(90, TimeUnit.SECONDS);
            var old = Files.getLastModifiedTime(setup.source);
            Files.setLastModifiedTime(setup.source,
                    java.nio.file.attribute.FileTime.fromMillis(old.toMillis() + 10_000));
            assertThrows(ExecutionException.class,
                    () -> setup.policy.validateBeforeStoppingWriter().get(15, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,
                    () -> setup.policy.finalizeStrictAfterShutdown(true, true, true)
                            .get(15, TimeUnit.SECONDS));
            assertFalse(Files.exists(CompactionActivation.receiptPath(
                    root, setup.state.snapshot())));
        }
    }

    @Test
    void strictModeRejectsHistoryInsertedAfterSnapshot() throws Exception {
        try (Setup setup = new Setup(root, MaintenancePolicy.STRICT)) {
            setup.policy.prepare(setup.operator, "").get(30, TimeUnit.SECONDS);
            try (Connection source = DriverManager.getConnection("jdbc:sqlite:" + setup.source);
                 Statement sql = source.createStatement()) {
                sql.execute("INSERT INTO block_changes "
                        + "(happened_at,actor_uuid,actor_name,world,world_uuid,x,y,z,"
                        + "chunk_x,chunk_z,action,before_data,after_data) "
                        + "VALUES(999999,'actor','actor','world','uuid',32,64,0,2,0,"
                        + "'PLACE','minecraft:air','minecraft:stone')");
            }
            assertStrictRefused(setup);
        }
    }

    @Test
    void strictModeRejectsPostSnapshotRollbackJobChanges() throws Exception {
        try (Setup setup = new Setup(root, MaintenancePolicy.STRICT)) {
            setup.policy.prepare(setup.operator, "").get(30, TimeUnit.SECONDS);
            try (Connection source = DriverManager.getConnection("jdbc:sqlite:" + setup.source);
                 Statement sql = source.createStatement()) {
                sql.execute("UPDATE rollback_jobs SET updated_at = updated_at + 10 WHERE id = 1");
            }
            assertStrictRefused(setup);
        }
    }

    @Test
    void strictModeRejectsPostSnapshotRetentionDeletions() throws Exception {
        try (Setup setup = new Setup(root, MaintenancePolicy.STRICT)) {
            setup.policy.prepare(setup.operator, "").get(30, TimeUnit.SECONDS);
            try (Connection source = DriverManager.getConnection("jdbc:sqlite:" + setup.source);
                 Statement sql = source.createStatement()) {
                sql.execute("DELETE FROM block_changes WHERE id = 1");
            }
            assertStrictRefused(setup);
        }
    }

    @Test
    void policyApprovalDoesNotIgnoreFalseCutoffOrTamperedVerificationManifest()
            throws Exception {
        try (Setup setup = new Setup(root, MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            Path manifest = setup.copy.candidate().resolveSibling(
                    setup.copy.candidate().getFileName().toString().replace(".db", ".manifest"));
            Properties p = load(manifest);
            p.setProperty("invoked_at_millis", "1");
            try (var output = Files.newOutputStream(manifest)) {
                p.store(output, "Tampered snapshot timestamp");
            }
            assertThrows(ExecutionException.class,
                    () -> setup.policy.prepare(setup.operator, CONSENT)
                            .get(30, TimeUnit.SECONDS));
            assertEquals(MaintenancePhase.VERIFYING, setup.state.snapshot().phase());
        }
    }

    @Test
    void staleModifiedApprovedPolicyIsRejectedOnRestart() throws Exception {
        try (Setup setup = new Setup(root, MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            var approved = setup.policy.prepare(setup.operator, CONSENT).get(30, TimeUnit.SECONDS);
            Properties props = load(approved.policyFile());
            props.setProperty("candidate_sha256", "0".repeat(64));
            try (var output = Files.newOutputStream(approved.policyFile())) {
                props.store(output, "Tampered policy");
            }
            assertThrows(java.io.IOException.class,
                    () -> CompactionReplacementPolicy.requireApproved(root, setup.state.snapshot()));
        }
    }

    @Test
    void missingCandidateAfterApprovalIsNotMistakenForVerifiedActivation() throws Exception {
        Path moved;
        try (Setup setup = new Setup(root, MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            setup.policy.prepare(setup.operator, CONSENT).get(30, TimeUnit.SECONDS);
            moved = setup.copy.candidate().resolveSibling(
                    setup.copy.candidate().getFileName().toString() + ".preserved");
            Files.move(setup.copy.candidate(), moved);
        }
        assertTrue(Files.isRegularFile(moved), "Do not delete or overwrite candidate contents");
        assertThrows(java.io.IOException.class, () -> MaintenanceCoordinator.open(root),
                "Issue #168 must verify activation rather than assuming a missing candidate was installed");
        assertTrue(Files.isRegularFile(root.resolve("fragguard.db")));
    }

    @Test
    void tamperedApprovedPolicyPreventsCoordinatorFromOpeningAtStartup() throws Exception {
        try (Setup setup = new Setup(root, MaintenancePolicy.MAINTENANCE_SNAPSHOT)) {
            var approved = setup.policy.prepare(setup.operator, CONSENT).get(30, TimeUnit.SECONDS);
            Properties props = load(approved.policyFile());
            props.setProperty("candidate_sha256", "f".repeat(64));
            try (var output = Files.newOutputStream(approved.policyFile())) {
                props.store(output, "Wrong candidate fingerprint");
            }
        }
        assertThrows(java.io.IOException.class, () -> MaintenanceCoordinator.open(root),
                "Do not open the live SQLite writer while persisted READY policy is untrusted");
    }

    private static void assertStrictRefused(Setup setup) throws Exception {
        assertThrows(ExecutionException.class,
                () -> setup.policy.validateBeforeStoppingWriter().get(15, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class,
                () -> setup.policy.finalizeStrictAfterShutdown(true, true, true)
                        .get(90, TimeUnit.SECONDS));
        assertEquals(MaintenancePhase.VERIFYING, setup.state.snapshot().phase());
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> setup.state.markReady().join());
        assertTrue(Files.isRegularFile(setup.source));
    }

    private static Properties load(Path file) throws Exception {
        Properties props = new Properties();
        try (var input = Files.newInputStream(file)) {
            props.load(input);
        }
        return props;
    }

    static final class Setup implements AutoCloseable {
        final Path source;
        final MaintenanceCoordinator state;
        final CompactionStoragePreflight capacity;
        final OnlineDatabaseCompaction vacuum;
        final CompactionCandidateVerifier verifier;
        final CompactionReplacementPolicy policy;
        final OnlineDatabaseCompaction.Result copy;
        final UUID operator = UUID.randomUUID();

        Setup(Path root, MaintenancePolicy kind) throws Exception {
            source = DatabaseDeepDiagnosticsTest.seededDatabase(root, 6);
            state = MaintenanceCoordinator.open(root);
            long begin = System.currentTimeMillis() - 5_000;
            state.schedule(operator, begin, begin + 1000, kind)
                    .get(10, TimeUnit.SECONDS);
            state.activate().get(10, TimeUnit.SECONDS);
            capacity = new CompactionStoragePreflight(root);
            vacuum = new OnlineDatabaseCompaction(root, state, capacity);
            verifier = new CompactionCandidateVerifier(root, state);
            policy = new CompactionReplacementPolicy(root, state);
            var approvedCapacity = CompactionStoragePreflight.evaluate(capacity.measure(),
                    new CompactionStoragePreflight.Evidence(null, null, 256, true));
            assertTrue(approvedCapacity.mayReserve());
            copy = vacuum.start(approvedCapacity).get(90, TimeUnit.SECONDS);
            var verified = verifier.verify(copy).get(90, TimeUnit.SECONDS);
            assertEquals(64, verified.candidateSha256().length());
            assertEquals(MaintenancePhase.VERIFYING, state.snapshot().phase());
            assertFalse(state.snapshot().manifestFile().isBlank());
        }

        @Override public void close() {
            policy.close();
            verifier.close();
            vacuum.close();
            capacity.close();
            state.close();
        }
    }
}
