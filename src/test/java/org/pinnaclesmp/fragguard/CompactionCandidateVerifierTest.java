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

class CompactionCandidateVerifierTest {
    @TempDir Path directory;

    @Test
    void fullVerificationRecordsAllHistoryAndUndoHashesButDoesNotApproveReplacement()
            throws Exception {
        try (Setup state = new Setup(directory, 30)) {
            var verified = state.verifier.verify(state.copy).get(90, TimeUnit.SECONDS);
            assertEquals(30, verified.tables().get("block_changes").rows());
            assertEquals(1, verified.tables().get("rollback_jobs").rows());
            assertEquals(1, verified.tables().get("rollback_job_changes").rows());
            assertEquals(1, verified.tables().get("rollback_job_chunks").rows());
            assertTrue(verified.tables().get("rollback_job_changes").blobBytes() >= 3);
            assertEquals(33, verified.scannedRows());
            assertEquals(64, verified.candidateSha256().length());
            assertTrue(Files.isRegularFile(verified.manifest()));

            Properties manifest = load(verified.manifest());
            assertEquals("fragguard-compaction-verified-1", manifest.getProperty("format"));
            assertEquals("true", manifest.getProperty("verified"));
            assertEquals("false", manifest.getProperty("replacement_approved"));
            assertEquals(state.coordinator.snapshot().operationId(),
                    manifest.getProperty("maintenance_operation_id"));
            assertEquals(verified.tables().get("rollback_job_changes").sha256(),
                    manifest.getProperty("rollback_job_changes.sha256"));
            assertEquals(verified.candidateSha256(), manifest.getProperty("candidate_sha256"));
            assertEquals("1", manifest.getProperty("rollback_jobs.rows"));
            assertEquals(state.copy.candidate().getFileName().toString(),
                    manifest.getProperty("candidate"));
            assertEquals(MaintenancePhase.VERIFYING, state.coordinator.snapshot().phase(),
                    "Issue #167 must approve the policy before any READY_TO_SWAP transition");
            assertEquals(verified.manifest().getFileName().toString(),
                    state.coordinator.snapshot().manifestFile());
            String manifestText = Files.readString(verified.manifest());
            assertFalse(manifestText.contains("secret-world"));
            assertFalse(manifestText.contains("minecraft:diamond_block"));
            assertFalse(manifestText.contains(directory.toAbsolutePath().toString()));
            assertEquals("verified", state.verifier.progress().stage());
        }
    }

    @Test
    void laterLiveSourceWritesDoNotInvalidateTheActualCopiedSnapshot()
            throws Exception {
        try (Setup state = new Setup(directory, 12)) {
            try (Connection live = DriverManager.getConnection("jdbc:sqlite:" + state.source);
                 Statement statement = live.createStatement()) {
                statement.execute("""
                        INSERT INTO block_changes
                        (happened_at,actor_uuid,actor_name,world,world_uuid,x,y,z,
                         chunk_x,chunk_z,action,before_data,after_data)
                        VALUES(12345678,'later-player','later-player','secret-world','uuid',
                               160,64,0,10,0,'PLACE','minecraft:air','minecraft:stone')
                        """);
            }
            var verified = state.verifier.verify(state.copy).get(90, TimeUnit.SECONDS);
            assertEquals(12, verified.tables().get("block_changes").rows(),
                    "do not compare candidate against later source row counts");
            assertEquals(MaintenancePhase.VERIFYING, state.coordinator.snapshot().phase());
        }
    }

    @Test
    void changedSavedInventoryBlobCanNeverPassVerification() throws Exception {
        try (Setup state = new Setup(directory, 3)) {
            try (Connection candidate = DriverManager.getConnection(
                    "jdbc:sqlite:" + state.copy.candidate());
                 Statement change = candidate.createStatement()) {
                change.execute("UPDATE rollback_job_changes "
                        + "SET before_entity_data = X'0123456789' WHERE job_id = 1");
            }
            assertRejected(state);
        }
    }

    @Test
    void removedIndexCanNeverPassVerification() throws Exception {
        try (Setup state = new Setup(directory, 3)) {
            try (Connection candidate = DriverManager.getConnection(
                    "jdbc:sqlite:" + state.copy.candidate());
                 Statement change = candidate.createStatement()) {
                change.execute("DROP INDEX idx_fg_actor_world_time");
            }
            assertRejected(state);
        }
    }

    @Test
    void missingUndoRecordCannotPassEvenIfHistoryCountMatches() throws Exception {
        try (Setup state = new Setup(directory, 3)) {
            try (Connection candidate = DriverManager.getConnection(
                    "jdbc:sqlite:" + state.copy.candidate());
                 Statement change = candidate.createStatement()) {
                change.execute("DELETE FROM rollback_job_changes WHERE job_id = 1");
            }
            assertRejected(state);
        }
    }

    @Test
    void corruptCandidateCannotProduceVerifiedManifest() throws Exception {
        try (Setup state = new Setup(directory, 3)) {
            Files.write(state.copy.candidate(), new byte[]{12, 34, 56});
            assertRejected(state);
        }
    }

    @Test
    void unexpectedCandidateWalSidecarFailsClosed() throws Exception {
        try (Setup state = new Setup(directory, 3)) {
            Files.writeString(state.copy.candidate().resolveSibling(
                    state.copy.candidate().getFileName() + "-wal"), "not-a-matching-sqlite-sidecar");
            assertRejected(state);
        }
    }

    @Test
    void evidenceWithoutCopyTimeSha256CannotBeUpgradedToVerified() throws Exception {
        try (Setup state = new Setup(directory, 3)) {
            Properties evidence = load(state.copy.snapshotEvidence());
            evidence.remove("candidate_sha256");
            try (var out = Files.newOutputStream(state.copy.snapshotEvidence())) {
                evidence.store(out, "Legacy evidence without immutable fingerprint");
            }
            assertRejected(state);
        }
    }

    @Test
    void mismatchedMaintenanceOperationNeverCreatesManifest() throws Exception {
        try (Setup state = new Setup(directory, 3)) {
            Properties evidence = load(state.copy.snapshotEvidence());
            evidence.setProperty("maintenance_operation_id", UUID.randomUUID().toString());
            try (var out = Files.newOutputStream(state.copy.snapshotEvidence())) {
                evidence.store(out, "Wrong operation");
            }
            assertRejected(state);
        }
    }

    private static Properties load(Path file) throws Exception {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(file)) {
            properties.load(input);
        }
        return properties;
    }

    private static void assertRejected(Setup state) throws Exception {
        assertThrows(ExecutionException.class,
                () -> state.verifier.verify(state.copy).get(90, TimeUnit.SECONDS));
        assertEquals(MaintenancePhase.FAILED, state.coordinator.snapshot().phase());
        assertEquals("", state.coordinator.snapshot().manifestFile());
        Path manifest = state.copy.candidate().resolveSibling(
                state.copy.candidate().getFileName().toString().replace(".db", ".manifest"));
        assertFalse(Files.exists(manifest), "a rejected candidate is never certified");
        assertTrue(Files.isRegularFile(state.source), "original must remain intact");
    }

    private static final class Setup implements AutoCloseable {
        final Path source;
        final MaintenanceCoordinator coordinator;
        final CompactionStoragePreflight preflight;
        final OnlineDatabaseCompaction vacuum;
        final CompactionCandidateVerifier verifier;
        final OnlineDatabaseCompaction.Result copy;

        Setup(Path root, int rows) throws Exception {
            source = DatabaseDeepDiagnosticsTest.seededDatabase(root, rows);
            coordinator = MaintenanceCoordinator.open(root);
            long begin = System.currentTimeMillis() - 5_000;
            coordinator.schedule(UUID.randomUUID(), begin, begin + 1_000,
                    MaintenancePolicy.STRICT).get(10, TimeUnit.SECONDS);
            FrozenMaintenanceProof.persistSnapshot(root, coordinator.snapshot(), 0L, 0L);
            coordinator.activate().get(10, TimeUnit.SECONDS);
            preflight = new CompactionStoragePreflight(root);
            vacuum = new OnlineDatabaseCompaction(root, coordinator, preflight);
            verifier = new CompactionCandidateVerifier(root, coordinator);
            var approval = CompactionStoragePreflight.evaluate(preflight.measure(),
                    new CompactionStoragePreflight.Evidence(null, null, 256, true));
            assertTrue(approval.mayReserve(), approval.reasons().toString());
            copy = vacuum.start(approval).get(90, TimeUnit.SECONDS);
            assertEquals(MaintenancePhase.VERIFYING, coordinator.snapshot().phase());
            assertEquals("", coordinator.snapshot().manifestFile());
            assertEquals("false", load(copy.snapshotEvidence()).getProperty("verified"));
            assertEquals(64, load(copy.snapshotEvidence()).getProperty("candidate_sha256").length());
        }

        @Override
        public void close() {
            verifier.close();
            vacuum.close();
            preflight.close();
            coordinator.close();
        }
    }
}
