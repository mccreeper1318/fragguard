package org.pinnaclesmp.fragguard;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.function.Consumer;

/**
 * Thread-safe, durable maintenance lifecycle. State-changing filesystem I/O
 * runs on a single background worker; clients see only committed transitions.
 * Countdown/UI, admission control, and VACUUM execution are separate issues.
 */
final class MaintenanceCoordinator implements AutoCloseable {
    private final MaintenanceStateStore store;
    private final ExecutorService executor;
    private final Consumer<String> audit;
    private volatile MaintenanceSnapshot current;
    private volatile boolean closed;
    // A rename that could not be confirmed durable must never reopen the
    // rollback/undo gate or permit additional transitions in this process.
    private volatile boolean durabilityUncertain;

    private MaintenanceCoordinator(MaintenanceStateStore store, MaintenanceSnapshot initial,
                                   Consumer<String> audit) {
        this.store = store;
        this.audit = audit;
        this.current = initial;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "FragGuard-Maintenance-State");
            thread.setDaemon(true);
            return thread;
        });
    }

    static MaintenanceCoordinator open(Path dataDirectory) throws IOException {
        return open(dataDirectory, ignored -> { });
    }

    static MaintenanceCoordinator open(Path dataDirectory, Consumer<String> audit) throws IOException {
        return open(new MaintenanceStateStore(dataDirectory), audit);
    }

    // Package-private for simulating directory-sync failures in regression tests.
    static MaintenanceCoordinator open(MaintenanceStateStore store, Consumer<String> audit)
            throws IOException {
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(store, "store");
        MaintenanceSnapshot loaded = store.load();
        if (loaded.phase() == MaintenancePhase.IDLE) {
            // A host may have killed Paper after the durable IDLE commit
            // but before the completed candidate metadata was archived.
            // Archive only authenticated completed evidence; a failure to
            // archive must not prevent an otherwise healthy normal startup.
            try {
                AutomaticCompactionActivation.archiveCompletedEvidenceIfPresent(
                        store.directory());
            } catch (IOException archiveError) {
                safeLog(audit, "Completed compaction evidence archive not safe: "
                        + archiveError.getMessage()
                        + ". Preserve files; a later compaction preflight may be blocked.");
            }
        }
        if (loaded.phase() == MaintenancePhase.READY_TO_SWAP) {
            // During a crash between atomic renames, the original source may
            // temporarily be absent. An authenticated #206 journal permits
            // OFFLINE recovery, but never opens the SQLite writer here.
            if (AutomaticCompactionActivation.hasJournal(store.directory(), loaded)) {
                AutomaticCompactionActivation.requireResumable(store.directory(), loaded);
            } else {
                Path candidate = store.directory().resolve("compaction-candidates")
                        .resolve(loaded.candidateFile());
                if (java.nio.file.Files.isRegularFile(candidate,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    CompactionReplacementPolicy.requireApproved(store.directory(), loaded);
                } else {
                    // Compatibility for already-completed manual swaps;
                    // never treat missing originals as a valid new install.
                    CompactionActivation.requirePreparedActivation(store.directory(), loaded);
                }
            }
        } else if (loaded.phase() == MaintenancePhase.ACTIVATING) {
            // A previous startup may have created NEW WAL/SHM or changed the
            // candidate's journal header before the IDLE state was committed.
            // Do not mistake those fresh sidecars for the old DB's WAL.
            CompactionActivation.requireRecoverableActivation(store.directory(), loaded);
        }
        MaintenanceSnapshot recovered = switch (loaded.phase()) {
            case COUNTDOWN -> loaded.withPhase(MaintenancePhase.CANCELLED,
                    "Server restarted during countdown; maintenance was not activated. Schedule again.");
            case COMPACTING, VERIFYING -> loaded.withPhase(MaintenancePhase.FAILED,
                    "Server stopped during database maintenance. Candidate is unverified; "
                            + "preserve source and candidate and review before retrying.");
            default -> loaded; // ACTIVE/READY_TO_SWAP require deliberate follow-up.
        };
        if (recovered != loaded) {
            store.save(recovered);
        }
        safeLog(audit, "Maintenance startup state: " + recovered.phase()
                + " (operation " + recovered.operationId() + ")");
        return new MaintenanceCoordinator(store, recovered, audit);
    }

    MaintenanceSnapshot snapshot() {
        return current;
    }

    boolean blocksNewJobs() {
        return durabilityUncertain || current.blocksNewJobs();
    }

    CompletableFuture<MaintenanceSnapshot> schedule(UUID operator, long now, long deadline,
                                                    MaintenancePolicy policy) {
        return commit(existing -> {
            if (existing.phase() != MaintenancePhase.IDLE
                    && existing.phase() != MaintenancePhase.CANCELLED) {
                throw new IllegalStateException("Maintenance already scheduled or in progress");
            }
            return MaintenanceSnapshot.scheduled(operator, now, deadline, policy);
        });
    }

    CompletableFuture<MaintenanceSnapshot> activate() {
        return advance(MaintenancePhase.ACTIVE, "Maintenance active; regular access is restricted");
    }

    /**
     * Delayed freeze callbacks must never activate a different countdown.
     * Compare the operation on the serialized state worker, not only on the
     * calling thread before queuing this transition.
     */
    CompletableFuture<MaintenanceSnapshot> activateCountdownIfCurrent(String operationId) {
        Objects.requireNonNull(operationId, "operationId");
        return commit(existing -> existing.phase() == MaintenancePhase.COUNTDOWN
                && existing.operationId().equals(operationId)
                ? existing.withPhase(MaintenancePhase.ACTIVE,
                    "Maintenance active; regular access is restricted")
                : existing);
    }

    CompletableFuture<MaintenanceSnapshot> beginCompaction(String outputFile) {
        return commit(existing -> existing.withCandidate(outputFile,
                "Creating a compacted candidate; original database remains active"));
    }

    /**
     * Only the real VACUUM INTO worker (#165) may record its conservative
     * invocation-time LOWER BOUND after copying the SQLite snapshot. JDBC
     * cannot expose the precise internal WAL snapshot acquisition instant.
     * The candidate and its snapshot evidence identify the actual copied
     * rows; do not treat this timestamp as proof all later writes are present.
     */
    CompletableFuture<MaintenanceSnapshot> recordSnapshotCutoff(long cutoffMillis) {
        return commit(existing -> existing.withCutoff(cutoffMillis,
                "SQLite copy completed; conservative cutoff bound recorded. "
                        + "Candidate evidence identifies the actual snapshot."));
    }

    CompletableFuture<MaintenanceSnapshot> beginVerification() {
        return commit(existing -> {
            if (existing.snapshotCutoffMillis() <= 0 || existing.candidateFile().isEmpty()) {
                throw new IllegalStateException("Cannot verify before capturing a consistent SQLite snapshot");
            }
            return existing.withPhase(MaintenancePhase.VERIFYING,
                    "Checking compacted database before any manual replacement");
        });
    }

    CompletableFuture<MaintenanceSnapshot> recordVerifiedManifest(String manifestFile) {
        return commit(existing -> existing.withManifest(manifestFile,
                "Verification report recorded for compacted candidate"));
    }

    CompletableFuture<MaintenanceSnapshot> markReady() {
        return commit(existing -> {
            if (existing.phase() != MaintenancePhase.VERIFYING
                    || existing.manifestFile().isEmpty()) {
                throw new IllegalStateException("Cannot approve an unverified candidate");
            }
            try {
                // No administrative/rollback FORCE option may skip this
                // separate durable replacement-policy approval (#167).
                CompactionReplacementPolicy.requireApproved(store.directory(), existing);
            } catch (IOException badPolicy) {
                throw new IllegalStateException("Candidate replacement policy is missing or "
                        + "untrusted; do not swap any SQLite file", badPolicy);
            }
            return existing.withPhase(MaintenancePhase.READY_TO_SWAP,
                    "Verified snapshot and accepted replacement policy; "
                            + "stop Paper before manual activation (#168)");
        });
    }

    CompletableFuture<MaintenanceSnapshot> fail(String reason) {
        return advance(MaintenancePhase.FAILED, reason);
    }

    /**
     * #207: asynchronous VACUUM/verification/STRICT callbacks may not fail
     * another operation or a cancelled/ready-for-startup maintenance state.
     * Compare the operation and phase inside the serialized durable worker.
     */
    CompletableFuture<MaintenanceSnapshot> failCurrentWorkIfOperation(
            String operationId, String reason) {
        Objects.requireNonNull(operationId, "operationId");
        return commit(existing -> existing.operationId().equals(operationId)
                && (existing.phase() == MaintenancePhase.ACTIVE
                    || existing.phase() == MaintenancePhase.COMPACTING
                    || existing.phase() == MaintenancePhase.VERIFYING)
                ? existing.withPhase(MaintenancePhase.FAILED, reason)
                : existing);
    }

    /** Ignore a failed freeze from a previously cancelled maintenance operation. */
    CompletableFuture<MaintenanceSnapshot> failCountdownIfCurrent(
            String operationId, String reason) {
        Objects.requireNonNull(operationId, "operationId");
        return commit(existing -> existing.phase() == MaintenancePhase.COUNTDOWN
                && existing.operationId().equals(operationId)
                ? existing.withPhase(MaintenancePhase.FAILED, reason)
                : existing);
    }

    CompletableFuture<MaintenanceSnapshot> cancel(String reason) {
        return advance(MaintenancePhase.CANCELLED, reason);
    }

    /**
     * Durable checkpoint BEFORE Database.init() can flip journal_mode to WAL
     * or create fresh sidecars. A crash after this stage must be recoverable
     * without falsely requiring the pre-initialization file byte hash again.
     */
    CompletableFuture<MaintenanceSnapshot> beginActivation() {
        return commit(existing -> {
            if (existing.phase() != MaintenancePhase.READY_TO_SWAP) {
                throw new IllegalStateException("Manual activation is not ready");
            }
            try {
                CompactionActivation.requirePreparedActivation(store.directory(), existing);
            } catch (IOException evidenceFailure) {
                throw new IllegalStateException("Cannot persist activation without "
                        + "complete manual swap evidence", evidenceFailure);
            }
            return existing.withPhase(MaintenancePhase.ACTIVATING,
                    "Candidate verified before writer startup; crash recovery requires "
                    + "full logical SQLite validation and the intact original backup");
        });
    }

    /**
     * Only after the installed candidate was validated and the SQLite writer
     * successfully initialized may this durable state clear the join gate.
     * The ACTIVATING stage survives crashes without losing activation proof.
     */
    CompletableFuture<MaintenanceSnapshot> finishVerifiedActivation() {
        return commit(existing -> {
            if (existing.phase() != MaintenancePhase.ACTIVATING) {
                throw new IllegalStateException("Cannot finish activation without "
                        + "a persisted pre-initialization verification stage");
            }
            return existing.reset();
        });
    }

    CompletableFuture<MaintenanceSnapshot> clearCancelled() {
        return commit(existing -> {
            if (existing.phase() != MaintenancePhase.CANCELLED) {
                throw new IllegalStateException("Cancel maintenance before clearing its state");
            }
            return existing.reset();
        });
    }

    private CompletableFuture<MaintenanceSnapshot> advance(MaintenancePhase next, String message) {
        return commit(existing -> existing.withPhase(next, message));
    }

    private CompletableFuture<MaintenanceSnapshot> commit(UnaryOperator<MaintenanceSnapshot> update) {
        Objects.requireNonNull(update);
        CompletableFuture<MaintenanceSnapshot> result = new CompletableFuture<>();
        synchronized (this) {
            if (closed || durabilityUncertain) {
                result.completeExceptionally(new IllegalStateException(
                        durabilityUncertain
                                ? "Maintenance state durability is uncertain; administrator recovery is required"
                                : "Maintenance state worker is stopped"));
                return result;
            }
            try {
                executor.execute(() -> {
                    try {
                        if (durabilityUncertain) {
                            throw new IllegalStateException(
                                    "Maintenance state durability is uncertain; no further transitions are allowed");
                        }
                        MaintenanceSnapshot next = Objects.requireNonNull(update.apply(current));
                        // Stale asynchronous callbacks must not overwrite the new
                        // operation's state file or even update its revision.
                        if (next == current) {
                            result.complete(current);
                            return;
                        }
                        // Never publish in memory until durable write completes.
                        store.save(next);
                        current = next;
                        safeLog(audit, "Maintenance state " + next.operationId() + ": "
                                + next.phase() + " — " + next.detail());
                        result.complete(next);
                    } catch (MaintenanceStateStore.UncertainDurabilityException exception) {
                        // After the rename, disk state may be newer than the
                        // last acknowledged in-memory state. Fail closed.
                        durabilityUncertain = true;
                        safeLog(audit, "SEVERE: Maintenance state rename not confirmed durable. "
                                + "Blocking new rollback/undo jobs until recovery: " + exception.getMessage());
                        result.completeExceptionally(exception);
                    } catch (Exception exception) {
                        result.completeExceptionally(exception);
                    }
                });
            } catch (RejectedExecutionException exception) {
                result.completeExceptionally(exception);
            }
        }
        return result;
    }

    private static void safeLog(Consumer<String> logger, String message) {
        try {
            logger.accept(message);
        } catch (RuntimeException ignored) {
            // A logging failure must never contradict a successfully persisted transition.
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            executor.shutdown();
        }
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                throw new IllegalStateException("Pending maintenance state writes did not finish");
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Maintenance state shutdown interrupted", exception);
        }
    }
}
