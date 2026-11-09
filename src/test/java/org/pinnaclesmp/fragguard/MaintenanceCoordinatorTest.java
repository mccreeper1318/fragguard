package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MaintenanceCoordinatorTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String CANDIDATE = "fragguard-compact-test.db";
    private static final String MANIFEST = "fragguard-compact-test.manifest";

    @TempDir Path directory;

    @Test
    void storesSchedulesAndPoliciesWithoutOpeningSqlite() throws Exception {
        try (var maintenance = MaintenanceCoordinator.open(directory)) {
            assertEquals(MaintenancePhase.IDLE, maintenance.snapshot().phase());
            assertFalse(Files.exists(directory.resolve("fragguard.db")));
            var planned = maintenance.schedule(ADMIN, NOW, NOW + 600_000L,
                    MaintenancePolicy.MAINTENANCE_SNAPSHOT).join();
            assertEquals(MaintenancePhase.COUNTDOWN, planned.phase());
            assertEquals(NOW + 600_000L, planned.countdownEndsAtMillis());
            assertEquals(MaintenancePolicy.MAINTENANCE_SNAPSHOT, planned.policy());
            assertEquals(ADMIN.toString(), planned.operatorUuid());
            assertEquals(planned, new MaintenanceStateStore(directory).load());
            assertFalse(planned.blocksNewJobs());
        }
    }

    @Test
    void cannotSkipStagesOrPromoteCandidateWithoutCutoffAndManifest() throws Exception {
        try (var maintenance = MaintenanceCoordinator.open(directory)) {
            assertThrows(CompletionException.class, () -> maintenance.activate().join());
            assertEquals(MaintenancePhase.IDLE, maintenance.snapshot().phase());
            maintenance.schedule(ADMIN, NOW, NOW + 600_000L, MaintenancePolicy.STRICT).join();
            maintenance.activate().join();
            assertTrue(maintenance.blocksNewJobs());
            assertThrows(CompletionException.class, () -> maintenance.markReady().join());
            assertThrows(CompletionException.class, () -> maintenance.beginCompaction("../fragguard.db").join());
            maintenance.beginCompaction(CANDIDATE).join();
            assertThrows(CompletionException.class, () -> maintenance.beginVerification().join()
                    .phase()); // Should not be able to reach READY without a snapshot.
            assertThrows(CompletionException.class, () -> maintenance.recordSnapshotCutoff(-1).join());
            maintenance.recordSnapshotCutoff(NOW + 2000).join();
            maintenance.beginVerification().join();
            assertThrows(CompletionException.class, () -> maintenance.markReady().join());
            maintenance.recordVerifiedManifest(MANIFEST).join();
            var ready = maintenance.markReady().join();
            assertEquals(MaintenancePhase.READY_TO_SWAP, ready.phase());
            assertEquals(MANIFEST, ready.manifestFile());
            assertEquals(CANDIDATE, ready.candidateFile());
            assertEquals(NOW + 2000, ready.snapshotCutoffMillis());
        }
    }

    @Test
    void interruptedCountdownCancelsRatherThanKickingAfterRestart() throws Exception {
        try (var maintenance = MaintenanceCoordinator.open(directory)) {
            maintenance.schedule(ADMIN, NOW, NOW + 600_000L, MaintenancePolicy.STRICT).join();
        }
        try (var reopened = MaintenanceCoordinator.open(directory)) {
            assertEquals(MaintenancePhase.CANCELLED, reopened.snapshot().phase());
            assertFalse(reopened.blocksNewJobs());
            assertTrue(reopened.snapshot().detail().contains("restarted"));
            assertEquals(reopened.snapshot(), new MaintenanceStateStore(directory).load());
        }
    }

    @Test
    void interruptedCompactionFailsClosedAndLeavesAllDatabaseFilesUntouched() throws Exception {
        Path original = directory.resolve("fragguard.db");
        Path candidate = directory.resolve(CANDIDATE);
        Files.writeString(original, "placeholder-original");
        Files.writeString(candidate, "incomplete-candidate");
        try (var maintenance = MaintenanceCoordinator.open(directory)) {
            maintenance.schedule(ADMIN, NOW, NOW + 600_000L, MaintenancePolicy.STRICT).join();
            maintenance.activate().join();
            maintenance.beginCompaction(CANDIDATE).join();
        }
        try (var reopened = MaintenanceCoordinator.open(directory)) {
            assertEquals(MaintenancePhase.FAILED, reopened.snapshot().phase());
            assertTrue(reopened.blocksNewJobs());
            assertEquals(CANDIDATE, reopened.snapshot().candidateFile());
            assertTrue(reopened.snapshot().detail().contains("unverified"));
            assertEquals("placeholder-original", Files.readString(original));
            assertEquals("incomplete-candidate", Files.readString(candidate));
            assertThrows(CompletionException.class, () -> reopened.schedule(ADMIN, NOW,
                    NOW + 600_000L, MaintenancePolicy.STRICT).join());
            reopened.cancel("Administrator acknowledged the interrupted job").join();
            reopened.clearCancelled().join();
            assertFalse(reopened.blocksNewJobs());
        }
    }

    @Test
    void interruptedVerificationRemainsUntrusted() throws Exception {
        try (var maintenance = MaintenanceCoordinator.open(directory)) {
            maintenance.schedule(ADMIN, NOW, NOW + 600_000L, MaintenancePolicy.STRICT).join();
            maintenance.activate().join();
            maintenance.beginCompaction(CANDIDATE).join();
            maintenance.recordSnapshotCutoff(NOW + 10).join();
            maintenance.beginVerification().join();
        }
        try (var reopened = MaintenanceCoordinator.open(directory)) {
            assertEquals(MaintenancePhase.FAILED, reopened.snapshot().phase());
            assertTrue(reopened.snapshot().blocksNewJobs());
            assertEquals(NOW + 10, reopened.snapshot().snapshotCutoffMillis());
        }
    }

    @Test
    void readyCandidateWaitsForExplicitActivationVerificationOnRestart() throws Exception {
        try (var maintenance = MaintenanceCoordinator.open(directory)) {
            maintenance.schedule(ADMIN, NOW, NOW + 600_000L, MaintenancePolicy.STRICT).join();
            maintenance.activate().join();
            maintenance.beginCompaction(CANDIDATE).join();
            maintenance.recordSnapshotCutoff(NOW + 10).join();
            maintenance.beginVerification().join();
            maintenance.recordVerifiedManifest(MANIFEST).join();
            maintenance.markReady().join();
        }
        try (var reopened = MaintenanceCoordinator.open(directory)) {
            assertEquals(MaintenancePhase.READY_TO_SWAP, reopened.snapshot().phase());
            assertTrue(reopened.blocksNewJobs());
            // This explicit hook is reserved for the future startup activation
            // validator; simply reopening must not call it.
            reopened.finishVerifiedActivation().join();
            assertEquals(MaintenancePhase.IDLE, reopened.snapshot().phase());
            assertFalse(reopened.blocksNewJobs());
        }
    }

    @Test
    void activeModePersistsAcrossRestartUntilCancelled() throws Exception {
        try (var maintenance = MaintenanceCoordinator.open(directory)) {
            maintenance.schedule(ADMIN, NOW, NOW + 600_000L, MaintenancePolicy.STRICT).join();
            maintenance.activate().join();
        }
        try (var reopened = MaintenanceCoordinator.open(directory)) {
            assertEquals(MaintenancePhase.ACTIVE, reopened.snapshot().phase());
            assertTrue(reopened.blocksNewJobs());
            reopened.cancel("Cancelled by operator").join();
            assertEquals(MaintenancePhase.CANCELLED, reopened.snapshot().phase());
            assertFalse(reopened.blocksNewJobs());
        }
    }

    @Test
    void invalidOrCorruptMetadataFailsClosedWithoutDeletingIt() throws Exception {
        MaintenanceStateStore store = new MaintenanceStateStore(directory);
        var scheduled = MaintenanceSnapshot.scheduled(ADMIN, NOW, NOW + 600_000L,
                MaintenancePolicy.STRICT);
        store.save(scheduled);
        Path statePath = directory.resolve("maintenance-state.properties");
        String original = Files.readString(statePath);
        Files.writeString(statePath, original.replace("COUNTDOWN", "READY_TO_SWAP"));
        assertThrows(IOException.class, () -> MaintenanceCoordinator.open(directory));
        assertTrue(Files.readString(statePath).contains("READY_TO_SWAP"));
    }

    @Test
    void failedStateWriteDoesNotPublishUncommittedTransition() throws Exception {
        try (var maintenance = MaintenanceCoordinator.open(directory)) {
            // Force an atomic move failure while keeping the existing state.
            Files.createDirectory(directory.resolve("maintenance-state.properties"));
            assertThrows(CompletionException.class, () -> maintenance.schedule(
                    ADMIN, NOW, NOW + 600_000L, MaintenancePolicy.STRICT).join());
            assertEquals(MaintenancePhase.IDLE, maintenance.snapshot().phase());
            assertTrue(Files.isDirectory(directory.resolve("maintenance-state.properties")));
        }
    }

    @Test
    void refusesStateUpdateWhenFilesystemCannotSyncDirectoryBeforeRename() throws Exception {
        var store = new MaintenanceStateStore(directory, path -> {
            throw new IOException("simulated unsupported directory fsync");
        });
        try (var maintenance = MaintenanceCoordinator.open(store, ignored -> { })) {
            var failure = assertThrows(CompletionException.class, () ->
                    maintenance.schedule(ADMIN, NOW, NOW + 600_000L, MaintenancePolicy.STRICT).join());
            assertInstanceOf(IOException.class, failure.getCause());
            assertEquals(MaintenancePhase.IDLE, maintenance.snapshot().phase());
            assertFalse(maintenance.blocksNewJobs(), "no state was renamed or acknowledged");
            assertFalse(Files.exists(directory.resolve("maintenance-state.properties")));
        }
    }

    @Test
    void failedDirectorySyncAfterRenameFailsClosedWithoutAcknowledgingStage() throws Exception {
        AtomicInteger syncs = new AtomicInteger();
        var store = new MaintenanceStateStore(directory, path -> {
            if (syncs.incrementAndGet() == 2) {
                throw new IOException("simulated crash-risk after atomic move");
            }
        });
        try (var maintenance = MaintenanceCoordinator.open(store, ignored -> { })) {
            var failure = assertThrows(CompletionException.class, () ->
                    maintenance.schedule(ADMIN, NOW, NOW + 600_000L, MaintenancePolicy.STRICT).join());
            assertInstanceOf(MaintenanceStateStore.UncertainDurabilityException.class, failure.getCause());
            assertEquals(2, syncs.get(), "directory must be synced before and after atomic rename");
            assertEquals(MaintenancePhase.IDLE, maintenance.snapshot().phase(),
                    "unconfirmed transition must not be published");
            assertTrue(maintenance.blocksNewJobs(),
                    "a renamed but unconfirmed state must not permit new rollback work");
            assertThrows(CompletionException.class, () ->
                    maintenance.schedule(ADMIN, NOW, NOW + 600_000L, MaintenancePolicy.STRICT).join(),
                    "do not permit further transitions after an uncertain rename");
            assertEquals(2, syncs.get(), "no more filesystem updates should be attempted");
            assertEquals(MaintenancePhase.COUNTDOWN, new MaintenanceStateStore(directory).load().phase(),
                    "after the rename, the on-disk file may be newer than the last acknowledged state");
        }
    }

    @Test
    void newJobsBlockedOnlyAfterCountdownEndsOrInFailure() {
        assertFalse(MaintenancePhase.IDLE.blocksNewJobs());
        assertFalse(MaintenancePhase.COUNTDOWN.blocksNewJobs());
        assertFalse(MaintenancePhase.CANCELLED.blocksNewJobs());
        for (MaintenancePhase phase : new MaintenancePhase[]{
                MaintenancePhase.ACTIVE, MaintenancePhase.COMPACTING,
                MaintenancePhase.VERIFYING, MaintenancePhase.READY_TO_SWAP,
                MaintenancePhase.FAILED}) {
            assertTrue(phase.blocksNewJobs(), () -> phase + " must block new rollback and undo jobs");
        }
    }
}
