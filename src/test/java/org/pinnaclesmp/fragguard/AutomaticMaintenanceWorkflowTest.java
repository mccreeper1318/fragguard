package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** #207: no Bukkit thread or production SQLite files required for the controller tests. */
class AutomaticMaintenanceWorkflowTest {
    @TempDir Path root;

    private static final UUID ADMIN =
            UUID.fromString("b9ec92ba-11d8-4a7d-9a5d-015f66d18d52");

    private final MaintenanceCoordinator coordinator = mock(MaintenanceCoordinator.class);
    private final CompactionStoragePreflight preflight = mock(CompactionStoragePreflight.class);
    private final OnlineDatabaseCompaction compaction = mock(OnlineDatabaseCompaction.class);
    private final CompactionCandidateVerifier verifier = mock(CompactionCandidateVerifier.class);
    private final CompactionReplacementPolicy policy = mock(CompactionReplacementPolicy.class);
    private final AtomicReference<MaintenanceSnapshot> state =
            new AtomicReference<>(MaintenanceSnapshot.idle());
    private final List<String> messages = new ArrayList<>();
    private final List<String> stopped = new ArrayList<>();

    private AutomaticMaintenanceWorkflow setup(
            CompactionStoragePreflight.Evidence configuredEvidence) {
        return setup(configuredEvidence, () -> true);
    }

    private AutomaticMaintenanceWorkflow setup(
            CompactionStoragePreflight.Evidence configuredEvidence,
            java.util.function.BooleanSupplier writable) {
        when(coordinator.snapshot()).thenAnswer(ignored -> state.get());
        when(coordinator.schedule(eq(ADMIN), anyLong(), anyLong(), eq(MaintenancePolicy.STRICT)))
                .thenAnswer(args -> {
                    MaintenanceSnapshot fresh = MaintenanceSnapshot.scheduled(
                            args.getArgument(0), args.getArgument(1), args.getArgument(2),
                            MaintenancePolicy.STRICT);
                    state.set(fresh);
                    return CompletableFuture.completedFuture(fresh);
                });
        when(preflight.run(any())).thenAnswer(args ->
                CompletableFuture.completedFuture(
                        CompactionStoragePreflight.evaluate(measure(), args.getArgument(0))));
        when(coordinator.failCurrentWorkIfOperation(anyString(), anyString()))
                .thenAnswer(args -> {
                    MaintenanceSnapshot prior = state.get();
                    if (prior.phase() == MaintenancePhase.ACTIVE
                            || prior.phase() == MaintenancePhase.COMPACTING
                            || prior.phase() == MaintenancePhase.VERIFYING) {
                        state.set(prior.withPhase(MaintenancePhase.FAILED, args.getArgument(1)));
                    }
                    return CompletableFuture.completedFuture(state.get());
                });
        return new AutomaticMaintenanceWorkflow(root, coordinator, preflight,
                compaction, verifier, policy, () -> configuredEvidence, () -> 30,
                writable, Runnable::run, (id, mode) -> stopped.add(id + ":" + mode),
                messages::add);
    }

    @Test
    void oneCommandSchedulesStrictCountdownWithoutManualWorkerCalls() {
        try (var auto = setup(known())) {
            var answer = auto.start(ADMIN, messages::add, false).join();
            assertEquals(AutomaticMaintenanceWorkflow.ResultType.COUNTDOWN_STARTED,
                    answer.type());
            assertEquals(MaintenancePhase.COUNTDOWN, state.get().phase());
            assertEquals(MaintenancePolicy.STRICT, state.get().policy());
            assertTrue(auto.controlsOperation(state.get().operationId()));
            verify(preflight, times(1)).run(any());
            verifyNoInteractions(compaction, verifier, policy);
            assertTrue(stopped.isEmpty());
            assertTrue(messages.stream().anyMatch(m -> m.contains("continue to be logged")));
            assertEquals(AutomaticMaintenanceWorkflow.ResultType.REFUSED,
                    auto.start(ADMIN, messages::add, false).join().type());
            verify(coordinator, times(1)).schedule(eq(ADMIN), anyLong(), anyLong(),
                    eq(MaintenancePolicy.STRICT));
        }
    }

    @Test
    void unknownHeadroomNeedsOneExplicitConfirmationBeforeAnyCountdown() {
        var unknown = new CompactionStoragePreflight.Evidence(null, null, 256, false);
        try (var auto = setup(unknown)) {
            var pending = auto.start(ADMIN, messages::add, false).join();
            assertEquals(AutomaticMaintenanceWorkflow.ResultType.NEEDS_CAPACITY_ACK,
                    pending.type());
            assertEquals(MaintenancePhase.IDLE, state.get().phase());
            verify(coordinator, never()).schedule(any(), anyLong(), anyLong(), any());
            var confirmed = auto.start(ADMIN, messages::add, true).join();
            assertEquals(AutomaticMaintenanceWorkflow.ResultType.COUNTDOWN_STARTED,
                    confirmed.type());
            assertEquals(MaintenancePhase.COUNTDOWN, state.get().phase());
            var confirmation = org.mockito.ArgumentCaptor
                    .forClass(CompactionStoragePreflight.Evidence.class);
            verify(preflight, times(2)).run(confirmation.capture());
            assertFalse(confirmation.getAllValues().get(0).acknowledgeUnknown());
            assertTrue(confirmation.getAllValues().get(1).acknowledgeUnknown());
            assertEquals(AutomaticMaintenanceWorkflow.ResultType.REFUSED,
                    auto.start(ADMIN, messages::add, true).join().type());
        }
    }

    @Test
    void cancelledButStillPausedSqliteCannotStartNewCountdownOrMoveCandidates() {
        try (var auto = setup(known(), () -> false)) {
            var refused = auto.start(ADMIN, messages::add, false).join();
            assertEquals(AutomaticMaintenanceWorkflow.ResultType.REFUSED, refused.type());
            assertTrue(refused.detail().contains("logging is not writable"));
            verifyNoInteractions(preflight, compaction, verifier, policy);
            verify(coordinator, never()).schedule(any(), anyLong(), anyLong(), any());
            assertEquals(MaintenancePhase.IDLE, state.get().phase());
        }
    }

    @Test
    void knownShortageRefusesBeforeCountdownAndNeverSilentlyAcceptsRisk() {
        var shortage = new CompactionStoragePreflight.Evidence(0L, 0L, 256, false);
        try (var auto = setup(shortage)) {
            var refused = auto.start(ADMIN, messages::add, false).join();
            assertEquals(AutomaticMaintenanceWorkflow.ResultType.REFUSED, refused.type());
            assertTrue(refused.detail().contains("insufficient"));
            assertEquals(MaintenancePhase.IDLE, state.get().phase());
            verify(coordinator, never()).schedule(any(), anyLong(), anyLong(), any());
            verifyNoInteractions(compaction, verifier, policy);
        }
    }

    @Test
    void activationAutomaticallyRunsCopyFullVerifyStrictAndSafeStop()
            throws Exception {
        try (var auto = setup(known())) {
            auto.start(ADMIN, messages::add, false).join();
            MaintenanceSnapshot active = state.get()
                    .withPhase(MaintenancePhase.ACTIVE, "freeze fully sealed");
            state.set(active);
            long now = System.currentTimeMillis();
            Path candidate = root.resolve("compaction-candidates")
                    .resolve("fragguard-compact-test.db");
            var copy = new OnlineDatabaseCompaction.Result(candidate,
                    candidate.resolveSibling("fragguard-compact-test.snapshot.properties"),
                    "source-id", now, now + 1L, 1234L, Database.SCHEMA_VERSION,
                    6L, 1L, now);
            when(compaction.start(any())).thenAnswer(args -> {
                state.set(state.get().withCandidate("fragguard-compact-test.db", "copying")
                        .withCutoff(System.currentTimeMillis(), "copied")
                        .withPhase(MaintenancePhase.VERIFYING, "verifying"));
                return CompletableFuture.completedFuture(copy);
            });
            when(verifier.verify(eq(copy))).thenAnswer(args -> {
                state.set(state.get().withManifest(
                        "fragguard-compact-test.manifest", "verified"));
                return CompletableFuture.completedFuture(new CompactionCandidateVerifier.Result(
                        candidate, root.resolve("example.manifest"), "hash", "schema", 9L,
                        Map.of()));
            });
            when(policy.prepare(eq(ADMIN), eq(""))).thenAnswer(args ->
                    CompletableFuture.completedFuture(new CompactionReplacementPolicy.Decision(
                            MaintenancePolicy.STRICT, "PENDING_FINAL_SHUTDOWN",
                            root.resolve("pending.policy"), state.get().operationId(),
                            now, now + 1L, 0L, "", "full equality passed")));
            auto.onActivated(active);
            verify(preflight, times(2)).run(any()); // a NEW capacity report at ACTIVE
            verify(compaction, times(1)).start(any());
            verify(verifier, times(1)).verify(eq(copy));
            verify(policy, times(1)).prepare(eq(ADMIN), eq(""));
            assertEquals(List.of(active.operationId() + ":STRICT"), stopped);
            assertEquals(MaintenancePhase.VERIFYING, state.get().phase());
            assertTrue(messages.stream().anyMatch(m -> m.contains("BLOCK LOGGING PAUSED")));
            assertTrue(messages.stream().anyMatch(m -> m.contains("PASSED")));
        }
    }

    @Test
    void cancelledOldCopyCannotVerifyApproveStopOrFailReplacement() {
        try (var auto = setup(known())) {
            auto.start(ADMIN, messages::add, false).join();
            MaintenanceSnapshot active = state.get().withPhase(
                    MaintenancePhase.ACTIVE, "fully frozen");
            state.set(active);
            CompletableFuture<OnlineDatabaseCompaction.Result> pending = new CompletableFuture<>();
            when(compaction.start(any())).thenReturn(pending);
            auto.onActivated(active);
            auto.abandon(active.operationId());
            MaintenanceSnapshot another = MaintenanceSnapshot.scheduled(ADMIN,
                    System.currentTimeMillis(), System.currentTimeMillis() + 100_000L,
                    MaintenancePolicy.STRICT);
            state.set(another);
            Path candidate = root.resolve("compaction-candidates")
                    .resolve("fragguard-compact-stale.db");
            pending.complete(new OnlineDatabaseCompaction.Result(candidate,
                    candidate, "stale", 1, 2, 10, 7, 2, 0, 0));
            verifyNoInteractions(verifier, policy);
            assertTrue(stopped.isEmpty());
            verify(coordinator, never()).failCurrentWorkIfOperation(anyString(), anyString());
            assertEquals(MaintenancePhase.COUNTDOWN, state.get().phase());
        }
    }

    @Test
    void operatorMayCancelPreCountdownWhileCapacityCheckIsPending() {
        try (var auto = setup(known())) {
            CompletableFuture<CompactionStoragePreflight.Report> outstanding =
                    new CompletableFuture<>();
            doReturn(outstanding).when(preflight).run(any());
            CompletableFuture<AutomaticMaintenanceWorkflow.StartResult> task =
                    auto.start(ADMIN, messages::add, false);
            assertTrue(auto.cancelPreparation(ADMIN));
            assertFalse(auto.cancelPreparation(ADMIN));
            outstanding.complete(CompactionStoragePreflight.evaluate(measure(), known()));
            assertEquals(AutomaticMaintenanceWorkflow.ResultType.REFUSED,
                    task.join().type());
            verify(coordinator, never()).schedule(any(), anyLong(), anyLong(), any());
            verifyNoInteractions(compaction, verifier, policy);
        }
    }

    @Test
    void cancellationCannotFalselySucceedAfterCountdownWasQueuedForDurableSave()
            throws Exception {
        try (var auto = setup(known())) {
            CompletableFuture<MaintenanceSnapshot> saving = new CompletableFuture<>();
            java.util.concurrent.CountDownLatch submitted =
                    new java.util.concurrent.CountDownLatch(1);
            doAnswer(args -> {
                submitted.countDown(); // Worker handoff occurred, durable commit pending.
                return saving;
            }).when(coordinator).schedule(eq(ADMIN), anyLong(), anyLong(),
                    eq(MaintenancePolicy.STRICT));
            var starting = auto.start(ADMIN, messages::add, false);
            assertTrue(submitted.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(auto.countdownScheduling(ADMIN));
            assertFalse(auto.cancelPreparation(ADMIN),
                    "Cannot claim pre-countdown cancellation when a durable schedule is queued");
            MaintenanceSnapshot saved = MaintenanceSnapshot.scheduled(ADMIN,
                    System.currentTimeMillis(), System.currentTimeMillis() + 60_000L,
                    MaintenancePolicy.STRICT);
            state.set(saved);
            saving.complete(saved);
            assertEquals(AutomaticMaintenanceWorkflow.ResultType.COUNTDOWN_STARTED,
                    starting.join().type());
            assertTrue(auto.controlsOperation(saved.operationId()));
            assertFalse(auto.cancelPreparation(ADMIN));
            assertEquals(MaintenancePhase.COUNTDOWN, state.get().phase());
        }
    }

    @Test
    void failedFreshPreflightDoesNotRunCompactionOrAuthorizeShutdown() {
        try (var auto = setup(known())) {
            auto.start(ADMIN, messages::add, false).join();
            MaintenanceSnapshot active = state.get().withPhase(
                    MaintenancePhase.ACTIVE, "fully frozen");
            state.set(active);
            doReturn(CompletableFuture.completedFuture(
                    CompactionStoragePreflight.evaluate(measure(),
                            new CompactionStoragePreflight.Evidence(0L, 0L, 256, true))))
                    .when(preflight).run(any());
            auto.onActivated(active);
            verifyNoInteractions(compaction, verifier, policy);
            verify(coordinator).failCurrentWorkIfOperation(eq(active.operationId()), anyString());
            assertEquals(MaintenancePhase.FAILED, state.get().phase());
            assertTrue(stopped.isEmpty());
        }
    }

    private CompactionStoragePreflight.Measurement measure() {
        return new CompactionStoragePreflight.Measurement(
                root.resolve("fragguard.db"), 8_000_000L, 0L, 0L,
                4096L, 1024L, 0L, 200_000_000_000L, 200_000_000_000L,
                0, true, Instant.now());
    }

    private CompactionStoragePreflight.Evidence known() {
        return new CompactionStoragePreflight.Evidence(
                200_000_000_000L, 200_000_000_000L, 256L, false);
    }
}
