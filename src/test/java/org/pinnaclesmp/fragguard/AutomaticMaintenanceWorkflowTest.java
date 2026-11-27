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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;
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
        return setup(configuredEvidence, writable,
                AbandonedCandidateQuarantine::archiveIfAbandoned, ForkJoinPool.commonPool());
    }

    private AutomaticMaintenanceWorkflow setup(
            CompactionStoragePreflight.Evidence configuredEvidence,
            java.util.function.BooleanSupplier writable,
            AutomaticMaintenanceWorkflow.CandidateArchiver archiver, Executor archiveExecutor) {
        when(coordinator.snapshot()).thenAnswer(ignored -> state.get());
        when(coordinator.scheduleIfUnchanged(eq(ADMIN), anyLong(), anyLong(),
                eq(MaintenancePolicy.STRICT), any(MaintenanceSnapshot.class)))
                .thenAnswer(args -> {
                    MaintenanceSnapshot expected = args.getArgument(4);
                    if (!state.get().equals(expected)) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("Maintenance generation changed"));
                    }
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
                messages::add, archiver, archiveExecutor);
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
            verify(coordinator, times(1)).scheduleIfUnchanged(eq(ADMIN), anyLong(), anyLong(),
                    eq(MaintenancePolicy.STRICT), any(MaintenanceSnapshot.class));
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
            verify(coordinator, never()).scheduleIfUnchanged(any(), anyLong(), anyLong(), any(),
                    any(MaintenanceSnapshot.class));
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
            verify(coordinator, never()).scheduleIfUnchanged(any(), anyLong(), anyLong(), any(),
                    any(MaintenanceSnapshot.class));
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
            verify(coordinator, never()).scheduleIfUnchanged(any(), anyLong(), anyLong(), any(),
                    any(MaintenanceSnapshot.class));
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
    void queuedCancelledQuarantineCannotMoveArtifactsOfReplacementPlan()
            throws Exception {
        Path active = Files.createDirectory(root.resolve("compaction-candidates"));
        Files.writeString(active.resolve("fragguard-compact-old.db"), "old evidence");
        List<Runnable> queued = new CopyOnWriteArrayList<>();
        AtomicInteger archiveCalls = new AtomicInteger();
        AutomaticMaintenanceWorkflow.CandidateArchiver archiver = (folder, snapshot, operator) -> {
            archiveCalls.incrementAndGet();
            return AbandonedCandidateQuarantine.archiveIfAbandoned(folder, snapshot, operator);
        };
        try (var auto = setup(known(), () -> true, archiver, queued::add)) {
            var first = auto.start(ADMIN, messages::add, false);
            assertEquals(1, queued.size(), "Archive task was queued but not executed");
            assertTrue(auto.cancelPreparation(ADMIN));
            // A later invocation may already have produced distinct artifacts
            // in this same directory by the time the old task is scheduled.
            Files.writeString(active.resolve("fragguard-compact-later.db"),
                    "new attempt must not be moved by old task");
            var next = auto.start(ADMIN, messages::add, false);
            assertEquals(2, queued.size());

            queued.get(0).run();
            assertEquals(AutomaticMaintenanceWorkflow.ResultType.REFUSED, first.join().type());
            assertEquals(0, archiveCalls.get(),
                    "A cancelled archive must not call any filesystem mutation");
            assertEquals("new attempt must not be moved by old task",
                    Files.readString(active.resolve("fragguard-compact-later.db")));
            assertTrue(Files.exists(active.resolve("fragguard-compact-old.db")));

            queued.get(1).run();
            assertEquals(AutomaticMaintenanceWorkflow.ResultType.COUNTDOWN_STARTED,
                    next.join().type());
            assertEquals(1, archiveCalls.get(),
                    "Only the replacement plan may archive its candidate directory");
            assertFalse(Files.exists(active));
            Path archiveRoot = root.resolve("compaction-candidates-old");
            try (var listing = Files.list(archiveRoot)) {
                Path retained = listing.findFirst().orElseThrow();
                assertEquals("new attempt must not be moved by old task",
                        Files.readString(retained.resolve("fragguard-compact-later.db")));
            }
        }
    }

    @Test
    void runningQuarantinePreventsCancellationAndRetryUntilMoveCompletes()
            throws Exception {
        Path active = Files.createDirectory(root.resolve("compaction-candidates"));
        Files.writeString(active.resolve("fragguard-compact-old.db"), "preserve me");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AutomaticMaintenanceWorkflow.CandidateArchiver held = (folder, snapshot, operator) -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                if (!resume.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out in held quarantine test");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted archive test", interrupted);
            }
            return AbandonedCandidateQuarantine.archiveIfAbandoned(folder, snapshot, operator);
        };
        try (var auto = setup(known(), () -> true, held, ForkJoinPool.commonPool())) {
            var first = auto.start(ADMIN, messages::add, false);
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertTrue(auto.quarantineInProgress(ADMIN));
                assertFalse(auto.cancelPreparation(ADMIN),
                        "Cancellation must not free the plan during a candidate rename");
                assertEquals(AutomaticMaintenanceWorkflow.ResultType.REFUSED,
                        auto.start(ADMIN, messages::add, false).join().type(),
                        "A new plan cannot start while old archive mutation is in flight");
                assertTrue(Files.isRegularFile(active.resolve("fragguard-compact-old.db")));
            } finally {
                resume.countDown();
            }
            assertEquals(AutomaticMaintenanceWorkflow.ResultType.COUNTDOWN_STARTED,
                    first.get(20, TimeUnit.SECONDS).type());
            assertEquals(1, calls.get());
            assertFalse(Files.exists(active));
            assertEquals(MaintenancePhase.COUNTDOWN, state.get().phase());
        }
    }

    @Test
    void operatorMayCancelPreCountdownWhileCapacityCheckIsPending() throws Exception {
        try (var auto = setup(known())) {
            CompletableFuture<CompactionStoragePreflight.Report> outstanding =
                    new CompletableFuture<>();
            CountDownLatch enteredPreflight = new CountDownLatch(1);
            doAnswer(args -> {
                // This callback runs only after the archive mutex is
                // released. Test the preflight-cancel path deterministically.
                enteredPreflight.countDown();
                return outstanding;
            }).when(preflight).run(any());
            CompletableFuture<AutomaticMaintenanceWorkflow.StartResult> task =
                    auto.start(ADMIN, messages::add, false);
            assertTrue(enteredPreflight.await(10, TimeUnit.SECONDS));
            assertTrue(auto.cancelPreparation(ADMIN));
            assertFalse(auto.cancelPreparation(ADMIN));
            outstanding.complete(CompactionStoragePreflight.evaluate(measure(), known()));
            assertEquals(AutomaticMaintenanceWorkflow.ResultType.REFUSED,
                    task.join().type());
            verify(coordinator, never()).scheduleIfUnchanged(any(), anyLong(), anyLong(), any(),
                    any(MaintenanceSnapshot.class));
            verifyNoInteractions(compaction, verifier, policy);
        }
    }

    @Test
    void manualScheduleThenCancelInvalidatesPendingAutomaticPreflight() throws Exception {
        try (var auto = setup(known())) {
            CompletableFuture<CompactionStoragePreflight.Report> waiting =
                    new CompletableFuture<>();
            CountDownLatch reachedPreflight = new CountDownLatch(1);
            doAnswer(args -> {
                reachedPreflight.countDown();
                return waiting;
            }).when(preflight).run(any());
            var automatic = auto.start(ADMIN, messages::add, false);
            assertTrue(reachedPreflight.await(10, TimeUnit.SECONDS));

            // A different/manual operation transitions IDLE -> COUNTDOWN ->
            // CANCELLED while an older automatic capacity report is pending.
            MaintenanceSnapshot manual = MaintenanceSnapshot.scheduled(ADMIN,
                    System.currentTimeMillis(), System.currentTimeMillis() + 60_000L,
                    MaintenancePolicy.MAINTENANCE_SNAPSHOT);
            state.set(manual.withPhase(MaintenancePhase.CANCELLED, "manual cancelled"));
            waiting.complete(CompactionStoragePreflight.evaluate(measure(), known()));

            assertEquals(AutomaticMaintenanceWorkflow.ResultType.REFUSED,
                    automatic.get(10, TimeUnit.SECONDS).type());
            verify(coordinator, never()).scheduleIfUnchanged(any(), anyLong(),
                    anyLong(), any(), any(MaintenanceSnapshot.class));
            assertEquals(MaintenancePolicy.MAINTENANCE_SNAPSHOT, state.get().policy());
            assertEquals(MaintenancePhase.CANCELLED, state.get().phase());
            assertFalse(auto.blocksManualStart());
            verifyNoInteractions(compaction, verifier, policy);
            assertTrue(stopped.isEmpty());
        }
    }

    @Test
    void pluginCloseDrainsActiveCandidateArchiveBeforeReenable() throws Exception {
        Path active = Files.createDirectory(root.resolve("compaction-candidates"));
        Files.writeString(active.resolve("fragguard-compact-old.db"), "original old evidence");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AutomaticMaintenanceWorkflow.CandidateArchiver held = (folder, snapshot, operator) -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting for simulated archive fsync");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Archive interrupted", exception);
            }
            return AbandonedCandidateQuarantine.archiveIfAbandoned(folder, snapshot, operator);
        };
        var auto = setup(known(), () -> true, held, ForkJoinPool.commonPool());
        try {
            var start = auto.start(ADMIN, messages::add, false);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            CountDownLatch closing = new CountDownLatch(1);
            var shutdown = CompletableFuture.runAsync(() -> {
                closing.countDown();
                auto.close();
            });
            try {
                assertTrue(closing.await(10, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> shutdown.get(100, TimeUnit.MILLISECONDS),
                        "Disable must not finish while the former classloader's "
                                + "archive task can still rename candidate files");
                assertTrue(Files.isRegularFile(active.resolve("fragguard-compact-old.db")));
            } finally {
                release.countDown();
            }
            shutdown.get(10, TimeUnit.SECONDS);
            assertEquals(AutomaticMaintenanceWorkflow.ResultType.REFUSED,
                    start.get(10, TimeUnit.SECONDS).type());
            assertFalse(Files.exists(active));
            try (var archived = Files.list(root.resolve("compaction-candidates-old"))) {
                Path kept = archived.findFirst().orElseThrow();
                assertEquals("original old evidence",
                        Files.readString(kept.resolve("fragguard-compact-old.db")));
            }
            // A new plugin instance can begin only AFTER close() returned,
            // so it cannot have a stale old-classloader rename in flight.
            try (var replacement = setup(known())) {
                assertEquals(AutomaticMaintenanceWorkflow.ResultType.COUNTDOWN_STARTED,
                        replacement.start(ADMIN, messages::add, false).join().type());
            }
        } finally {
            release.countDown();
            auto.close();
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
            }).when(coordinator).scheduleIfUnchanged(eq(ADMIN), anyLong(), anyLong(),
                    eq(MaintenancePolicy.STRICT), any(MaintenanceSnapshot.class));
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
    void persistedCountdownCancellationAbandonsPlanBeforeScheduleCallbackPublishesId()
            throws Exception {
        try (var auto = setup(known())) {
            CompletableFuture<MaintenanceSnapshot> saving = new CompletableFuture<>();
            java.util.concurrent.CountDownLatch submitted =
                    new java.util.concurrent.CountDownLatch(1);
            doAnswer(args -> {
                submitted.countDown();
                return saving;
            }).when(coordinator).scheduleIfUnchanged(eq(ADMIN), anyLong(), anyLong(),
                    eq(MaintenancePolicy.STRICT), any(MaintenanceSnapshot.class));
            var starting = auto.start(ADMIN, messages::add, false);
            assertTrue(submitted.await(10, java.util.concurrent.TimeUnit.SECONDS));
            MaintenanceSnapshot persisted = MaintenanceSnapshot.scheduled(ADMIN,
                    System.currentTimeMillis(), System.currentTimeMillis() + 60_000L,
                    MaintenancePolicy.STRICT);
            state.set(persisted); // Durable worker committed; callback still pending.
            auto.abandon(persisted.operationId()); // Proper COUNTDOWN cancellation begins.
            saving.complete(persisted);
            assertEquals(AutomaticMaintenanceWorkflow.ResultType.REFUSED,
                    starting.join().type(), "Stale callback must not report started");
            assertFalse(auto.controlsOperation(persisted.operationId()));
            verifyNoInteractions(compaction, verifier, policy);
            assertTrue(stopped.isEmpty());
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
