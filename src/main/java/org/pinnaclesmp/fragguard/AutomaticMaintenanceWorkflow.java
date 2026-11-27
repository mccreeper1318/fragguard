package org.pinnaclesmp.fragguard;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * #207: one-command, STRICT-only orchestration built on the existing audited
 * freeze, VACUUM, full verifier, final comparison and #206 activation workers.
 *
 * The operator starts only ONE operation. The unknown-quota exception needs
 * one separate acknowledgement before the countdown. Every completion is
 * operation-bound and may progress only from its expected durable phase.
 * Snapshot Mode is deliberately NOT available through this workflow.
 */
final class AutomaticMaintenanceWorkflow implements AutoCloseable {
    enum ResultType { COUNTDOWN_STARTED, NEEDS_CAPACITY_ACK, REFUSED }
    record StartResult(ResultType type, String detail, String operationId) { }

    @FunctionalInterface
    interface CandidateArchiver {
        Path archive(Path root, MaintenanceSnapshot state, UUID operator)
                throws IOException;
    }

    private static final long CONSENT_MILLIS = 120_000L;
    private final Path root;
    private final MaintenanceCoordinator state;
    private final CompactionStoragePreflight preflight;
    private final OnlineDatabaseCompaction compaction;
    private final CompactionCandidateVerifier verifier;
    private final CompactionReplacementPolicy replacementPolicy;
    private final Supplier<CompactionStoragePreflight.Evidence> evidenceSupplier;
    private final IntSupplier countdownSeconds;
    private final BooleanSupplier loggingWritable;
    private final Consumer<Runnable> onMain;
    private final BiConsumer<String, MaintenancePolicy> stopPaper;
    private final Consumer<String> audit;
    private final CandidateArchiver candidateArchiver;
    private final Executor archiveExecutor;
    // The same lock surrounds both the ownership check + filesystem rename
    // and cancellation of the pre-countdown plan. tryLock on the main thread
    // never waits for slow storage I/O. It also serializes obsolete queued
    // archive tasks against later attempts in this plugin instance.
    private final ReentrantLock archiveMutationLock = new ReentrantLock();
    private final AtomicReference<Plan> current = new AtomicReference<>();
    private volatile boolean closed;

    private static final class Plan {
        final UUID operator;
        volatile Consumer<String> notice;
        final AtomicBoolean startedCompaction = new AtomicBoolean();
        volatile boolean awaitingConsent;
        // Guarded by the Plan monitor together with cancellation. Once
        // schedule() is submitted, cancellation must use durable COUNTDOWN.
        boolean scheduling;
        volatile long consentRequestedAt;
        volatile String operationId = "";
        volatile CompactionStoragePreflight.Evidence acceptedEvidence;
        volatile String stage = "Checking available space and prior candidates";
        Plan(UUID operator, Consumer<String> notice) {
            this.operator = operator;
            this.notice = notice;
        }
    }

    AutomaticMaintenanceWorkflow(Path root, MaintenanceCoordinator state,
            CompactionStoragePreflight preflight, OnlineDatabaseCompaction compaction,
            CompactionCandidateVerifier verifier, CompactionReplacementPolicy replacementPolicy,
            Supplier<CompactionStoragePreflight.Evidence> evidenceSupplier,
            IntSupplier countdownSeconds, BooleanSupplier loggingWritable,
            Consumer<Runnable> onMain,
            BiConsumer<String, MaintenancePolicy> stopPaper, Consumer<String> audit) {
        this(root, state, preflight, compaction, verifier, replacementPolicy,
                evidenceSupplier, countdownSeconds, loggingWritable, onMain,
                stopPaper, audit, AbandonedCandidateQuarantine::archiveIfAbandoned,
                ForkJoinPool.commonPool());
    }

    /** Allows deterministic tests to hold a queued or in-flight archive. */
    AutomaticMaintenanceWorkflow(Path root, MaintenanceCoordinator state,
            CompactionStoragePreflight preflight, OnlineDatabaseCompaction compaction,
            CompactionCandidateVerifier verifier, CompactionReplacementPolicy replacementPolicy,
            Supplier<CompactionStoragePreflight.Evidence> evidenceSupplier,
            IntSupplier countdownSeconds, BooleanSupplier loggingWritable,
            Consumer<Runnable> onMain,
            BiConsumer<String, MaintenancePolicy> stopPaper, Consumer<String> audit,
            CandidateArchiver candidateArchiver, Executor archiveExecutor) {
        this.root = Objects.requireNonNull(root);
        this.state = Objects.requireNonNull(state);
        this.preflight = Objects.requireNonNull(preflight);
        this.compaction = Objects.requireNonNull(compaction);
        this.verifier = Objects.requireNonNull(verifier);
        this.replacementPolicy = Objects.requireNonNull(replacementPolicy);
        this.evidenceSupplier = Objects.requireNonNull(evidenceSupplier);
        this.countdownSeconds = Objects.requireNonNull(countdownSeconds);
        this.loggingWritable = Objects.requireNonNull(loggingWritable);
        this.onMain = Objects.requireNonNull(onMain);
        this.stopPaper = Objects.requireNonNull(stopPaper);
        this.audit = Objects.requireNonNull(audit);
        this.candidateArchiver = Objects.requireNonNull(candidateArchiver);
        this.archiveExecutor = Objects.requireNonNull(archiveExecutor);
    }

    /** Start or confirm a single initial unknown-capacity warning. */
    CompletableFuture<StartResult> start(UUID operator, Consumer<String> notifier,
                                         boolean acknowledgeUnknown) {
        Objects.requireNonNull(operator);
        Objects.requireNonNull(notifier);
        if (closed) return CompletableFuture.failedFuture(
                new IllegalStateException("Automatic maintenance is stopped"));
        if (!loggingWritable.getAsBoolean()) {
            return CompletableFuture.completedFuture(new StartResult(ResultType.REFUSED,
                    "FragGuard logging is not writable after earlier maintenance. "
                            + "Do not begin a new countdown until the SQLite worker "
                            + "has safely resumed and normal admission is restored.", ""));
        }
        Plan plan;
        if (acknowledgeUnknown) {
            plan = current.get();
            if (plan == null || !plan.operator.equals(operator) || !plan.awaitingConsent
                    || System.currentTimeMillis() - plan.consentRequestedAt > CONSENT_MILLIS) {
                if (plan != null && plan.operator.equals(operator) && plan.awaitingConsent
                        && System.currentTimeMillis() - plan.consentRequestedAt > CONSENT_MILLIS) {
                    current.compareAndSet(plan, null); // Expired consent cannot block retries.
                }
                return CompletableFuture.completedFuture(new StartResult(ResultType.REFUSED,
                        "No current unknown-capacity warning for this operator. "
                                + "Run /fg maintenance start again.", ""));
            }
            plan.notice = notifier; // The OP may have reconnected since the warning.
            plan.awaitingConsent = false; // consume confirmation exactly once
        } else {
            // A user who missed the optional consent window may start over
            // without restarting Paper or manually editing a maintenance file.
            Plan waiting = current.get();
            if (waiting != null && waiting.awaitingConsent
                    && System.currentTimeMillis() - waiting.consentRequestedAt > CONSENT_MILLIS) {
                current.compareAndSet(waiting, null);
            }
            if (!MaintenanceUiActions.canSchedule(state.snapshot())) {
                return CompletableFuture.completedFuture(new StartResult(ResultType.REFUSED,
                        "Another maintenance operation is active. Review status or cancel safely.", ""));
            }
            plan = new Plan(operator, notifier);
            if (!current.compareAndSet(null, plan)) {
                return CompletableFuture.completedFuture(new StartResult(ResultType.REFUSED,
                        "Automatic maintenance is already being prepared. Do not start it twice.", ""));
            }
        }

        CompletableFuture<Void> archive = acknowledgeUnknown
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.runAsync(() -> {
                    // Never let a cancelled, queued archive move another
                    // operation's candidate directory. Hold the gate across
                    // ownership validation AND the final filesystem rename.
                    archiveMutationLock.lock();
                    try {
                        if (!owned(plan)) return; // Cancel won before task began.
                        MaintenanceSnapshot snapshot = state.snapshot();
                        if (!MaintenanceUiActions.canSchedule(snapshot)
                                || !loggingWritable.getAsBoolean()) {
                            throw new IOException("Maintenance state/logging changed "
                                    + "during candidate quarantine; files preserved");
                        }
                        Path old = candidateArchiver.archive(root, snapshot, operator);
                        if (old != null) notifyOperator(plan,
                                "Archived an abandoned candidate without deleting it: "
                                        + old.getFileName());
                    } catch (IOException exception) {
                        throw new CompletionException(exception);
                    } finally {
                        archiveMutationLock.unlock();
                    }
                }, archiveExecutor);

        return archive.thenCompose(ignored -> {
            if (!owned(plan)) return CompletableFuture.failedFuture(
                    new IllegalStateException("Maintenance preparation was superseded"));
            CompactionStoragePreflight.Evidence base = evidenceSupplier.get();
            CompactionStoragePreflight.Evidence chosen = new CompactionStoragePreflight.Evidence(
                    base.accountFreeBytes(), base.sqliteNativeTempFreeBytes(),
                    base.safetyMarginMiB(), acknowledgeUnknown);
            plan.stage = "Inspecting SQLite size, quota and native temp headroom";
            return preflight.run(chosen);
        }).thenCompose(report -> {
            if (!owned(plan) || !MaintenanceUiActions.canSchedule(state.snapshot())
                    || !loggingWritable.getAsBoolean()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Maintenance changed during capacity preflight"));
            }
            if (report.decision() == CompactionStoragePreflight.Decision.ACKNOWLEDGEMENT_REQUIRED) {
                plan.awaitingConsent = true;
                plan.consentRequestedAt = System.currentTimeMillis();
                plan.stage = "Waiting for explicit unknown-capacity acknowledgement";
                return CompletableFuture.completedFuture(new StartResult(
                        ResultType.NEEDS_CAPACITY_ACK,
                        "Hosting account quota or SQLite temporary space is unknown. "
                            + "No maintenance countdown has started. For technical details, "
                            + "you may run /fg diagnostics preflight. Confirm risk ONCE with "
                            + "/fg maintenance start acknowledge (within two minutes). "
                            + "Known capacity shortages can never be bypassed.", ""));
            }
            if (!report.mayReserve()) {
                return CompletableFuture.completedFuture(new StartResult(ResultType.REFUSED,
                        "Storage preflight refused: " + String.join("; ", report.reasons())
                                + ". No countdown started and no database was changed.", ""));
            }
            plan.acceptedEvidence = report.evidence();
            plan.stage = "Saving maintenance countdown; normal history logging remains active";
            long now = System.currentTimeMillis();
            long seconds = MaintenanceUiActions.countdownSeconds(countdownSeconds.getAsInt());
            if (now <= 0 || now > Long.MAX_VALUE - seconds * 1_000L) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("System clock cannot schedule maintenance"));
            }
            // Hand-off to the coordinator's durable state worker and
            // cancellation must be mutually exclusive. A cancellation after
            // this point may ONLY succeed through the persisted COUNTDOWN
            // cancellation path, never by clearing a still-blank operationId.
            synchronized (plan) {
                if (!owned(plan)) {
                    throw new IllegalStateException(
                            "Preparation cancelled before countdown submission");
                }
                plan.scheduling = true;
                return state.schedule(operator, now, now + seconds * 1_000L,
                        MaintenancePolicy.STRICT).thenApply(saved -> {
                            if (!owned(plan) || saved.phase() != MaintenancePhase.COUNTDOWN
                                    || !saved.operationId().equals(state.snapshot().operationId())) {
                                throw new IllegalStateException("Countdown was cancelled or "
                                        + "superseded before the scheduling callback completed");
                            }
                            plan.operationId = saved.operationId();
                            plan.stage = "Countdown: block logging ON; waiting for frozen source";
                            notifyOperator(plan, "STRICT maintenance countdown started ("
                                    + seconds + " seconds). All block changes continue to be "
                                    + "logged until the countdown ends. Remaining steps run "
                                    + "automatically; do not touch SQLite files.");
                            return new StartResult(ResultType.COUNTDOWN_STARTED,
                                    "STRICT countdown started. No additional compact, verify "
                                            + "or approve commands are needed.", saved.operationId());
                        });
            }
        }).handle((result, failure) -> {
            if (failure != null) {
                if (current.compareAndSet(plan, null)) audit.accept(
                        "Automatic maintenance preparation stopped: " + unwrap(failure));
                return new StartResult(ResultType.REFUSED,
                        "Could not safely prepare automatic maintenance: "
                                + unwrap(failure).getMessage()
                                + ". No unverified replacement was approved.", "");
            }
            if (result.type() == ResultType.REFUSED) current.compareAndSet(plan, null);
            return result;
        });
    }

    /**
     * Called ONLY after the full #203/#204 write-freeze proof was saved,
     * COUNTDOWN -> ACTIVE was committed, and login admission was closed.
     * Starts a new, immediately-used storage measurement: the pre-countdown
     * result is deliberately never passed to VACUUM's 30-second reservation.
     */
    void onActivated(MaintenanceSnapshot activated) {
        Plan plan = current.get();
        if (!matches(plan, activated, MaintenancePhase.ACTIVE)
                || !plan.startedCompaction.compareAndSet(false, true)) return;
        plan.stage = "BLOCK LOGGING PAUSED: checking fresh compaction headroom";
        notifyOperator(plan, "SERVER MAINTENANCE — BLOCK LOGGING PAUSED. "
                + "World edits made during maintenance are NOT recorded and "
                + "cannot be rolled back with FragGuard.");
        preflight.run(plan.acceptedEvidence).thenCompose(report -> {
            require(plan, MaintenancePhase.ACTIVE);
            if (!report.mayReserve()) {
                throw new IllegalStateException("Fresh ACTIVE storage capacity refused: "
                        + String.join("; ", report.reasons()));
            }
            plan.stage = "BLOCK LOGGING PAUSED: making compacted database";
            notifyOperator(plan, "Creating compacted SQLite candidate; source is still "
                    + "preserved. Progress is measured in bytes, not a guessed percentage.");
            return compaction.start(report);
        }).thenCompose(copy -> {
            require(plan, MaintenancePhase.VERIFYING);
            plan.stage = "BLOCK LOGGING PAUSED: verifying complete history, BLOBs and undo";
            notifyOperator(plan, "SQLite copy completed. Checking every history and undo "
                    + "record, schema, indexes, and block-entity contents.");
            return verifier.verify(copy);
        }).thenCompose(verified -> {
            require(plan, MaintenancePhase.VERIFYING);
            if (state.snapshot().manifestFile().isBlank()) {
                throw new IllegalStateException("Candidate has no durable verified manifest");
            }
            plan.stage = "BLOCK LOGGING PAUSED: final STRICT source equality check";
            notifyOperator(plan, "Full candidate verification passed; performing the "
                    + "no-loss STRICT source comparison while Paper is still running.");
            return replacementPolicy.prepare(plan.operator, "");
        }).whenComplete((approved, failure) -> {
            if (!owned(plan) || !plan.operationId.equals(state.snapshot().operationId())) {
                return; // Cancellation/host stop superseded this operation.
            }
            if (failure != null) {
                fail(plan, unwrap(failure));
                return;
            }
            if (approved == null || approved.policy() != MaintenancePolicy.STRICT
                    || approved.approved()
                    || !"PENDING_FINAL_SHUTDOWN".equals(approved.status())
                    || !plan.operationId.equals(approved.operationId())
                    || state.snapshot().phase() != MaintenancePhase.VERIFYING) {
                fail(plan, new IllegalStateException("STRICT proof did not authorize shutdown"));
                return;
            }
            plan.stage = "STRICT checked: requesting graceful Paper shutdown";
            notifyOperator(plan, "STRICT full-history comparison PASSED. FragGuard "
                    + "will stop Paper cleanly, preserve the original DB/WAL/SHM, "
                    + "then install the verified copy on next startup. If the "
                    + "host stays stopped, press Start once in its panel.");
            try {
                onMain.accept(() -> {
                    if (owned(plan) && plan.operationId.equals(state.snapshot().operationId())) {
                        try {
                            stopPaper.accept(plan.operationId, MaintenancePolicy.STRICT);
                        } catch (RuntimeException shutdownFailure) {
                            fail(plan, shutdownFailure);
                        }
                    }
                });
            } catch (RuntimeException unavailable) {
                fail(plan, unavailable);
            }
        });
    }

    String status() {
        Plan plan = current.get();
        return plan == null ? "Automatic STRICT maintenance not running" : plan.stage;
    }

    boolean controlsOperation(String operationId) {
        Plan plan = current.get();
        return plan != null && !closed && !plan.operationId.isBlank()
                && plan.operationId.equals(operationId);
    }

    /** Cancel only while the DB is still IDLE, before any countdown was saved. */
    boolean cancelPreparation(UUID operator) {
        Plan plan = current.get();
        if (plan == null || closed || !plan.operator.equals(operator)) return false;
        // Paper's main thread must not wait for directory fsync/rename. If a
        // rename is underway, keep the existing plan owned until it finishes,
        // instead of falsely reporting cancellation or admitting a new plan.
        if (!archiveMutationLock.tryLock()) return false;
        try {
            synchronized (plan) {
                // A schedule enqueued on the serialized state worker might
                // not yet have an operation ID. Never report cancellation
                // while that queued durable COUNTDOWN can still commit.
                if (!owned(plan) || plan.scheduling || !plan.operationId.isBlank()) {
                    return false;
                }
                boolean cancelled = current.compareAndSet(plan, null);
                if (cancelled) audit.accept("Pre-countdown storage check cancelled by "
                        + operator + "; no maintenance state or SQLite writes were changed");
                return cancelled;
            }
        } finally {
            archiveMutationLock.unlock();
        }
    }

    /** Explains a refused cancel without treating a moving candidate as idle. */
    boolean quarantineInProgress(UUID operator) {
        Plan plan = current.get();
        return plan != null && !closed && plan.operator.equals(operator)
                && archiveMutationLock.isLocked();
    }

    boolean countdownScheduling(UUID operator) {
        Plan plan = current.get();
        if (plan == null || !plan.operator.equals(operator)) return false;
        synchronized (plan) {
            return owned(plan) && plan.scheduling && plan.operationId.isBlank();
        }
    }

    void abandon(String operationId) {
        Plan plan = current.get();
        if (plan == null) return;
        // The coordinator may have DURABLY committed COUNTDOWN while the
        // schedule future's callback has not yet recorded its operationId
        // on the Plan. The proper persisted cancellation path must still
        // abandon the corresponding automatic flow.
        if (plan.operationId.equals(operationId)
                || (plan.scheduling && plan.operationId.isBlank()
                    && !operationId.isBlank()
                    && operationId.equals(state.snapshot().operationId()))) {
            current.compareAndSet(plan, null);
        }
    }

    private void fail(Plan plan, Throwable cause) {
        if (!owned(plan)) return;
        String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        plan.stage = "FAILED: original and candidate preserved; review /fg maintenance status full";
        audit.accept("Automatic STRICT maintenance refused activation for "
                + plan.operationId + ": " + message);
        notifyOperator(plan, "Automatic maintenance FAILED safely: " + message
                + ". Logging remains paused if the SQLite freeze completed. "
                + "Review /fg maintenance status full; no files were replaced.");
        state.failCurrentWorkIfOperation(plan.operationId,
                "Automatic maintenance failed: " + abbreviate(message)).whenComplete((saved, error) -> {
                    if (error != null) audit.accept("Could not persist failed maintenance gate: "
                            + unwrap(error));
                });
    }

    private void require(Plan plan, MaintenancePhase expected) {
        if (!owned(plan) || !matches(plan, state.snapshot(), expected)) {
            throw new IllegalStateException("Automatic maintenance cancelled or superseded at "
                    + expected);
        }
    }

    private boolean matches(Plan plan, MaintenanceSnapshot snapshot, MaintenancePhase expected) {
        return owned(plan) && !plan.operationId.isBlank()
                && plan.operationId.equals(snapshot.operationId())
                && snapshot.phase() == expected
                && snapshot.policy() == MaintenancePolicy.STRICT;
    }

    private boolean owned(Plan plan) {
        return !closed && plan != null && current.get() == plan;
    }

    private void notifyOperator(Plan plan, String message) {
        try {
            onMain.accept(() -> {
                if (owned(plan)) {
                    plan.notice.accept(message);
                    audit.accept(message);
                }
            });
        } catch (RuntimeException failure) {
            audit.accept("Could not deliver maintenance notification: " + failure.getMessage());
        }
    }

    private static String abbreviate(String message) {
        return message.length() > 400 ? message.substring(0, 400) : message;
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException
                || error instanceof java.util.concurrent.ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error;
    }

    @Override
    public void close() {
        closed = true;
        current.set(null);
    }
}
