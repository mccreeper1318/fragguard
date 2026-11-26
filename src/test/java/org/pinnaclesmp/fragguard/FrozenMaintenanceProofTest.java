package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** #205: durable evidence is operation-bound and cannot authorize stale copies. */
class FrozenMaintenanceProofTest {
    @TempDir Path root;

    @Test
    void sealIsFsyncedBoundToOperationAndSourceAndCarriesAuthorizedGenerations()
            throws Exception {
        Path source = DatabaseDeepDiagnosticsTest.seededDatabase(root, 4);
        try (MaintenanceCoordinator coordinator = MaintenanceCoordinator.open(root)) {
            long now = System.currentTimeMillis() - 5_000;
            coordinator.schedule(UUID.randomUUID(), now, now + 1_000,
                    MaintenancePolicy.STRICT).get(10, TimeUnit.SECONDS);
            assertThrows(java.io.IOException.class,
                    () -> FrozenMaintenanceProof.read(root, coordinator.snapshot()),
                    "Copying without completed #203/#204 evidence must fail closed");
            var proof = FrozenMaintenanceProof.persistSnapshot(
                    root, coordinator.snapshot(), 12L, 21L);
            assertTrue(Files.isRegularFile(FrozenMaintenanceProof.file(root, coordinator.snapshot())));
            assertEquals(12L, proof.historyGeneration());
            assertEquals(21L, proof.sqlGeneration());
            assertEquals(64, proof.sha256().length());
            FrozenMaintenanceProof.requireSourceUnchanged(root, proof);
            assertThrows(java.io.IOException.class,
                    () -> FrozenMaintenanceProof.persistSnapshot(
                            root, coordinator.snapshot(), 13L, 22L),
                    "A single operation cannot silently replace its frozen baseline");

            // Alter file metadata without modifying SQL tables. No stale
            // source stamp is allowed through VACUUM.
            FileTime earlier = Files.getLastModifiedTime(source);
            Files.setLastModifiedTime(source, FileTime.fromMillis(
                    Math.max(1L, earlier.toMillis() - 60_000L)));
            var error = assertThrows(java.io.IOException.class,
                    () -> FrozenMaintenanceProof.requireSourceUnchanged(root, proof));
            assertTrue(error.getMessage().contains("lastAuthorized"));
        }
    }

    @Test
    void oldFreezeEvidenceCannotBeReusedForLaterOperation() throws Exception {
        DatabaseDeepDiagnosticsTest.seededDatabase(root, 3);
        try (MaintenanceCoordinator coordinator = MaintenanceCoordinator.open(root)) {
            long now = System.currentTimeMillis() - 6_000;
            coordinator.schedule(UUID.randomUUID(), now, now + 1_000,
                    MaintenancePolicy.STRICT).get(10, TimeUnit.SECONDS);
            var first = FrozenMaintenanceProof.persistSnapshot(
                    root, coordinator.snapshot(), 1L, 2L);
            coordinator.cancel("Synthetic cancellation preserves source").get(10, TimeUnit.SECONDS);
            coordinator.schedule(UUID.randomUUID(), now + 2_000, now + 3_000,
                    MaintenancePolicy.STRICT).get(10, TimeUnit.SECONDS);
            assertNotEquals(first.operationId(), coordinator.snapshot().operationId());
            assertThrows(java.io.IOException.class,
                    () -> FrozenMaintenanceProof.read(root, coordinator.snapshot()));
            var second = FrozenMaintenanceProof.persistSnapshot(
                    root, coordinator.snapshot(), 4L, 5L);
            assertNotEquals(first.sha256(), second.sha256());
            assertEquals(coordinator.snapshot().operationId(), second.operationId());
        }
    }

    @Test
    void delayedOldProofCannotOverwriteNewProofAfterCancelAndReschedule() throws Exception {
        DatabaseDeepDiagnosticsTest.seededDatabase(root, 3);
        try (MaintenanceCoordinator coordinator = MaintenanceCoordinator.open(root)) {
            long now = System.currentTimeMillis() - 8_000;
            MaintenanceSnapshot oldOperation = coordinator.schedule(UUID.randomUUID(),
                    now, now + 1_000, MaintenancePolicy.STRICT).join();
            // Hold A's async writer until AFTER B has already finished
            // publishing, reproducing the order reported in Codex P2.
            java.util.concurrent.CountDownLatch releaseOld = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CompletableFuture<FrozenMaintenanceProof.Seal> staleTask =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            if (!releaseOld.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Timed out waiting for old task");
                            }
                            return FrozenMaintenanceProof.persistSnapshot(
                                    root, oldOperation, 11L, 12L);
                        } catch (Exception exception) {
                            throw new java.util.concurrent.CompletionException(exception);
                        }
                    });
            try {
                coordinator.cancel("Cancelled original countdown").join();
                MaintenanceSnapshot newOperation = coordinator.schedule(UUID.randomUUID(),
                        now + 2_000L, now + 3_000L, MaintenancePolicy.STRICT).join();
                assertNotEquals(oldOperation.operationId(), newOperation.operationId());

                // Until B publishes, only A's path might exist; never
                // use an old/shared filename as the newer operation's proof.
                assertThrows(java.io.IOException.class,
                        () -> FrozenMaintenanceProof.read(root, newOperation));
                FrozenMaintenanceProof.Seal newProof = FrozenMaintenanceProof.persistSnapshot(
                        root, newOperation, 21L, 22L);
                Path newPath = FrozenMaintenanceProof.file(root, newOperation);
                byte[] before = Files.readAllBytes(newPath);
                releaseOld.countDown();
                FrozenMaintenanceProof.Seal oldProof = staleTask.get(15, TimeUnit.SECONDS);

                assertNotEquals(oldProof.operationId(), newProof.operationId());
                assertNotEquals(FrozenMaintenanceProof.file(root, oldOperation), newPath);
                assertArrayEquals(before, Files.readAllBytes(newPath),
                        "Stale operation A must not replace B's durable proof");
                assertEquals(newProof.sha256(),
                        FrozenMaintenanceProof.read(root, newOperation).sha256());
                assertEquals(21L, FrozenMaintenanceProof.read(root, newOperation)
                        .historyGeneration());
                FrozenMaintenanceProof.requireSourceUnchanged(root,
                        FrozenMaintenanceProof.read(root, newOperation));
                assertEquals(newOperation, coordinator.snapshot(),
                        "A's delayed proof publication must not change B's lifecycle");
            } finally {
                releaseOld.countDown();
            }
        }
    }

    @Test
    void alreadyPublishedProofCannotBeOverwrittenByConcurrentSameOperationWriter()
            throws Exception {
        DatabaseDeepDiagnosticsTest.seededDatabase(root, 3);
        try (MaintenanceCoordinator coordinator = MaintenanceCoordinator.open(root)) {
            long now = System.currentTimeMillis() - 5_000;
            MaintenanceSnapshot state = coordinator.schedule(UUID.randomUUID(),
                    now, now + 1_000, MaintenancePolicy.STRICT).join();
            FrozenMaintenanceProof.persistSnapshot(root, state, 7L, 8L);
            Path proof = FrozenMaintenanceProof.file(root, state);
            byte[] first = Files.readAllBytes(proof);
            assertThrows(java.io.IOException.class,
                    () -> FrozenMaintenanceProof.persistSnapshot(root, state, 9L, 10L));
            assertArrayEquals(first, Files.readAllBytes(proof));
            assertEquals(7L, FrozenMaintenanceProof.read(root, state).historyGeneration());
        }
    }

    @Test
    void corruptedProofIsRefusedRatherThanRepairedInPlace() throws Exception {
        DatabaseDeepDiagnosticsTest.seededDatabase(root, 2);
        try (MaintenanceCoordinator coordinator = MaintenanceCoordinator.open(root)) {
            long now = System.currentTimeMillis() - 5_000;
            coordinator.schedule(UUID.randomUUID(), now, now + 1_000,
                    MaintenancePolicy.STRICT).get(10, TimeUnit.SECONDS);
            FrozenMaintenanceProof.persistSnapshot(root, coordinator.snapshot(), 0L, 0L);
            Files.writeString(FrozenMaintenanceProof.file(root, coordinator.snapshot()), "format=untrusted\n");
            assertThrows(java.io.IOException.class,
                    () -> FrozenMaintenanceProof.read(root, coordinator.snapshot()));
            assertThrows(java.io.IOException.class,
                    () -> FrozenMaintenanceProof.persistSnapshot(
                            root, coordinator.snapshot(), 0L, 0L));
        }
    }
}
