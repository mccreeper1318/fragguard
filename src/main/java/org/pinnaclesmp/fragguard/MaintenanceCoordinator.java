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

/**
 * Thread-safe, durable maintenance lifecycle. State-changing filesystem I/O
 * runs on a single background worker; clients see only committed transitions.
 * Countdown/UI, admission control, and VACUUM execution are separate issues.
 */
final class MaintenanceCoordinator implements AutoCloseable {
    private final MaintenanceStateStore store;
    private final ExecutorService executor;
    private volatile MaintenanceSnapshot current;
    private volatile boolean closed;

    private MaintenanceCoordinator(MaintenanceStateStore store, MaintenanceSnapshot initial) {
        this.store = store;
        this.current = initial;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "FragGuard-Maintenance-State");
            thread.setDaemon(true);
            return thread;
        });
    }

    static MaintenanceCoordinator open(Path dataDirectory) throws IOException {
        MaintenanceStateStore store = new MaintenanceStateStore(dataDirectory);
        MaintenanceSnapshot loaded = store.load();
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
        return new MaintenanceCoordinator(store, recovered);
    }

    MaintenanceSnapshot snapshot() {
        return current;
    }

    boolean blocksNewJobs() {
        return current.blocksNewJobs();
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

    CompletableFuture<MaintenanceSnapshot> beginCompaction(String outputFile) {
        return commit(existing -> existing.withCandidate(outputFile,
                "Creating a compacted candidate; original database remains active"));
    }

    /**
     * Must only be supplied from an actual consistent SQLite snapshot by the
     * future compaction worker (#165), not from maintenance start/countdown.
     */
    CompletableFuture<MaintenanceSnapshot> recordSnapshotCutoff(long cutoffMillis) {
        return commit(existing -> existing.withCutoff(cutoffMillis,
                "Consistent compacted snapshot captured"));
    }

    CompletableFuture<MaintenanceSnapshot> beginVerification() {
        return advance(MaintenancePhase.VERIFYING,
                "Checking compacted database before any manual replacement");
    }

    CompletableFuture<MaintenanceSnapshot> recordVerifiedManifest(String manifestFile) {
        return commit(existing -> existing.withManifest(manifestFile,
                "Verification report recorded for compacted candidate"));
    }

    CompletableFuture<MaintenanceSnapshot> markReady() {
        return advance(MaintenancePhase.READY_TO_SWAP,
                "Verified compacted candidate ready; stop Paper before manual file replacement");
    }

    CompletableFuture<MaintenanceSnapshot> fail(String reason) {
        return advance(MaintenancePhase.FAILED, reason);
    }

    CompletableFuture<MaintenanceSnapshot> cancel(String reason) {
        return advance(MaintenancePhase.CANCELLED, reason);
    }

    /**
     * Only the later startup-activation verifier (#168) may call this after
     * proving the intended candidate was installed. Never auto-clear on boot.
     */
    CompletableFuture<MaintenanceSnapshot> finishVerifiedActivation() {
        return commit(existing -> {
            if (existing.phase() != MaintenancePhase.READY_TO_SWAP) {
                throw new IllegalStateException("Cannot finish an unverified maintenance operation");
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
            if (closed) {
                result.completeExceptionally(new IllegalStateException("Maintenance state worker is stopped"));
                return result;
            }
            try {
                executor.execute(() -> {
                    try {
                        MaintenanceSnapshot next = Objects.requireNonNull(update.apply(current));
                        // Never publish in memory until durable write completes.
                        store.save(next);
                        current = next;
                        result.complete(next);
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
