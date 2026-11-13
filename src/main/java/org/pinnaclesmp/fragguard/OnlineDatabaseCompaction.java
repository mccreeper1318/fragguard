package org.pinnaclesmp.fragguard;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Creates ONE unverified, coherent SQLite VACUUM INTO candidate while the
 * Paper server and FragGuard's normal writer remain online.
 *
 * Does not run SQL on the writer, modify the source schema, create a full
 * input clone, install a candidate, or mark it READY. Full source/candidate
 * validation (#166), replacement policy (#167), and swap/restart (#168)
 * are separate mandatory gates.
 */
final class OnlineDatabaseCompaction implements AutoCloseable {
    record Progress(boolean running, String stage, String candidateFile,
                    long startedMillis, long lowerBoundCutoffMillis,
                    long candidateBytes, String detail) {
        Progress {
            Objects.requireNonNull(stage);
            Objects.requireNonNull(candidateFile);
            Objects.requireNonNull(detail);
        }
    }

    /**
     * Candidate-derived row IDs are tied to the exact copied WAL snapshot;
     * the wall-clock interval is deliberately NOT presented as a precise
     * SQLite commit cutoff. A future strict replacement must compare the
     * source against this candidate, not trust a guessed wall clock.
     */
    record Result(Path candidate, Path snapshotEvidence, String sourceIdentitySha256,
                  long invocationMillis, long completedMillis, long candidateBytes,
                  int schemaVersion, long lastIncludedHistoryId, long lastIncludedJobId,
                  long lastIncludedJobUpdateMillis) {
        Result {
            Objects.requireNonNull(candidate);
            Objects.requireNonNull(snapshotEvidence);
            Objects.requireNonNull(sourceIdentitySha256);
        }
    }

    private static final String SNAPSHOT_SUFFIX = ".snapshot.properties";

    private final Path sourceFile;
    private final MaintenanceCoordinator maintenance;
    private final CompactionStoragePreflight storage;
    private final ExecutorService worker;
    private final ExecutorService interrupter;
    private final AtomicReference<Job> active = new AtomicReference<>();
    private volatile Progress latest = new Progress(false, "idle", "", 0, 0, 0,
            "No online compaction has been started");
    private volatile boolean closed;

    OnlineDatabaseCompaction(Path dataDirectory, MaintenanceCoordinator maintenance,
                             CompactionStoragePreflight storage) {
        this.sourceFile = Objects.requireNonNull(dataDirectory).toAbsolutePath()
                .normalize().resolve("fragguard.db");
        this.maintenance = Objects.requireNonNull(maintenance);
        this.storage = Objects.requireNonNull(storage);
        worker = Executors.newSingleThreadExecutor(task -> daemon("FragGuard-Vacuum-Into", task));
        interrupter = Executors.newSingleThreadExecutor(task ->
                daemon("FragGuard-Vacuum-Cancel", task));
    }

    Progress progress() {
        Job job = active.get();
        return job == null ? latest : job.progress;
    }

    /**
     * Must be called only with a freshly approved #164 preflight. The durable
     * state must already be ACTIVE. A second start always fails before any
     * additional candidate can be allocated.
     */
    CompletableFuture<Result> start(CompactionStoragePreflight.Report approved) {
        Objects.requireNonNull(approved);
        if (closed) return CompletableFuture.failedFuture(
                new IllegalStateException("Compaction worker is stopped"));
        if (!approved.mayReserve()) return CompletableFuture.failedFuture(
                new IllegalStateException("Compaction requires an accepted space preflight"));
        if (maintenance.snapshot().phase() != MaintenancePhase.ACTIVE) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Activate maintenance before starting database compaction"));
        }
        Job job = new Job();
        if (!active.compareAndSet(null, job)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Another database compaction is already running"));
        }
        try {
            worker.execute(() -> {
                Result result = null;
                Throwable error = null;
                try {
                    result = perform(job, approved);
                } catch (Throwable failure) {
                    error = failure;
                } finally {
                    // The result is only observable when the SQLite connection,
                    // reservation and any state transitions have finished.
                    Progress finished = job.progress;
                    latest = new Progress(false, finished.stage(), finished.candidateFile(),
                            finished.startedMillis(), finished.lowerBoundCutoffMillis(),
                            finished.candidateBytes(), finished.detail());
                    active.compareAndSet(job, null);
                }
                if (error == null) job.response.complete(result);
                else job.response.completeExceptionally(error);
            });
        } catch (RejectedExecutionException failure) {
            active.compareAndSet(job, null);
            job.response.completeExceptionally(failure);
        }
        job.response.whenComplete((value, error) -> {
            if (job.response.isCancelled()) requestCancellation(job);
        });
        return job.response;
    }

    private Result perform(Job job, CompactionStoragePreflight.Report approved)
            throws Exception {
        boolean beganMaintenance = false;
        CompactionStoragePreflight.Reservation reservation = null;
        try {
            job.check();
            job.update("reserving", "", 0, 0,
                    "Reserving a new unique candidate; source and WAL/SHM remain intact");
            reservation = storage.reserve(approved);
            Path candidate = reservation.candidate();
            if (!candidate.getParent().equals(sourceFile.getParent().resolve("compaction-candidates"))
                    || !Files.isRegularFile(sourceFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(candidate.getParent())) {
                throw new IOException("Unsafe compaction source or destination");
            }
            job.update("committing state", candidate.getFileName().toString(), 0, 0,
                    "Persisting COMPACTING before any candidate database is created");
            await(maintenance.beginCompaction(candidate.getFileName().toString()));
            beganMaintenance = true;
            job.check();

            // Claim the output filename with CREATE_NEW before SQLite opens it;
            // VACUUM INTO accepts a pre-existing EMPTY file. A symlink or
            // user backup cannot be substituted/overwritten by this operation.
            Files.createFile(candidate);
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(candidate) != 0) {
                throw new IOException("Compaction output must be a fresh empty regular file");
            }
            String sourceIdentity = sourceIdentity(sourceFile);
            long invocationMillis;
            long completedMillis;
            // SQLite's mode=ro can run VACUUM INTO and read the coherent WAL
            // snapshot. Do NOT set PRAGMA query_only=ON: that causes the
            // SQLite engine to reject VACUUM INTO even for a read-only source.
            String jdbcUrl = "jdbc:sqlite:" + sourceFile.toUri() + "?mode=ro";
            try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
                try (Statement settings = connection.createStatement()) {
                    settings.execute("PRAGMA busy_timeout = 15000");
                }
                job.check();
                try (PreparedStatement vacuum = connection.prepareStatement("VACUUM INTO ?")) {
                    vacuum.setString(1, candidate.toString());
                    job.statement = vacuum;
                    // A conservative LOWER BOUND of the real snapshot instant.
                    // The SQLite read transaction starts during execute(); its
                    // exact wall-clock instant is not exposed by JDBC.
                    invocationMillis = System.currentTimeMillis();
                    job.update("vacuuming", candidate.getFileName().toString(),
                            invocationMillis, 0, "SQLite copying one consistent WAL snapshot");
                    vacuum.execute();
                    completedMillis = System.currentTimeMillis();
                } finally {
                    job.statement = null;
                }
            }
            job.check();
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(candidate) == 0) {
                throw new IOException("SQLite did not create a nonempty compaction candidate");
            }
            job.update("capturing snapshot evidence", candidate.getFileName().toString(),
                    invocationMillis, Files.size(candidate),
                    "Reading source identity and row high-water marks from copied SQLite snapshot");
            // All row identity/counter facts come from the COMPLETED CANDIDATE.
            // Do not read the mutable live source now and invent a cutoff.
            Result evidence = readCandidateEvidence(candidate, sourceIdentity,
                    invocationMillis, completedMillis);
            job.check();
            // SQLite VACUUM INTO does not promise an fsync of its output.
            // Flush the candidate and its evidence before publishing VERIFYING;
            // a power loss must never turn an unflushed file into a ready copy.
            try (FileChannel output = FileChannel.open(candidate, StandardOpenOption.WRITE)) {
                output.force(true);
            }
            job.check();
            String operationId = maintenance.snapshot().operationId();
            if (operationId.isBlank()) {
                throw new IOException("Cannot bind candidate evidence to maintenance operation");
            }
            saveEvidence(evidence, operationId);
            job.check();
            // Persist only a lower-bound timestamp; exact copy identity is in
            // the candidate evidence file for downstream verification (#166).
            await(maintenance.recordSnapshotCutoff(invocationMillis));
            job.check();
            await(maintenance.beginVerification());
            job.update("awaiting full verification", candidate.getFileName().toString(),
                    invocationMillis, evidence.candidateBytes(),
                    "UNVERIFIED candidate. #166 must check all contents before READY_TO_SWAP");
            return evidence;
        } catch (Throwable failure) {
            job.update(job.cancelled ? "cancelled" : "failed",
                    job.progress.candidateFile(), job.progress.lowerBoundCutoffMillis(),
                    job.progress.candidateBytes(),
                    job.cancelled
                            ? "Cancellation requested; source preserved, partial candidate unverified"
                            : (failure instanceof SQLException sql
                                && MigrationStoragePolicy.isSqliteFull(sql)
                                    ? CompactionStoragePreflight.sqliteFullMessage(sql)
                                    : "Compaction failed; source preserved, candidate remains unverified"));
            if (beganMaintenance) {
                try {
                    MaintenancePhase phase = maintenance.snapshot().phase();
                    if (phase == MaintenancePhase.COMPACTING || phase == MaintenancePhase.VERIFYING) {
                        if (job.cancelled) {
                            await(maintenance.cancel("Compaction cancelled; source kept; "
                                    + "any candidate is unverified and requires staff review"));
                        } else {
                            await(maintenance.fail("VACUUM INTO failed; source kept; "
                                    + "candidate is unverified; staff review required"));
                        }
                    }
                } catch (Exception stateFailure) {
                    failure.addSuppressed(stateFailure);
                }
            }
            if (failure instanceof Exception exception) throw exception;
            throw new IllegalStateException("Compaction failed unexpectedly", failure);
        } finally {
            job.statement = null;
            if (reservation != null) {
                try {
                    reservation.close();
                } catch (IOException closeFailure) {
                    // Closing a reservation never deletes candidate bytes. If
                    // marker cleanup fails, leave the state fail-closed.
                    job.update("reservation needs review", job.progress.candidateFile(),
                            job.progress.lowerBoundCutoffMillis(), job.progress.candidateBytes(),
                            "Candidate reservation could not be safely released");
                    throw closeFailure;
                }
            }
        }
    }

    private static Result readCandidateEvidence(Path candidate, String sourceIdentity,
                                                 long invoked, long finished)
            throws SQLException, IOException {
        try (Connection copied = DriverManager.getConnection(
                "jdbc:sqlite:" + candidate.toUri() + "?mode=ro")) {
            try (Statement statement = copied.createStatement()) {
                statement.execute("PRAGMA query_only = ON");
            }
            int schema = Math.toIntExact(pragma(copied, "user_version"));
            long historyId = scalar(copied,
                    "SELECT COALESCE(MAX(id),0) FROM block_changes");
            long jobId = scalar(copied,
                    "SELECT COALESCE(MAX(id),0) FROM rollback_jobs");
            long jobUpdate = scalar(copied,
                    "SELECT COALESCE(MAX(updated_at),0) FROM rollback_jobs");
            return new Result(candidate,
                    candidate.resolveSibling(candidate.getFileName() + SNAPSHOT_SUFFIX),
                    sourceIdentity, invoked, finished, Files.size(candidate),
                    schema, historyId, jobId, jobUpdate);
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("Candidate snapshot metadata is incomplete");
            return result.getLong(1);
        }
    }

    private static long pragma(Connection connection, String name) throws SQLException {
        return scalar(connection, "PRAGMA " + name);
    }

    private static String sourceIdentity(Path source) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(source,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("Compaction source is not a regular file");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Never write the sensitive absolute path into a public report.
            String identity = source.toAbsolutePath().normalize() + "|"
                    + String.valueOf(attributes.fileKey()) + "|"
                    + attributes.creationTime();
            return HexFormat.of().formatHex(digest.digest(
                    identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static void saveEvidence(Result result, String operationId) throws IOException {
        Path evidence = result.snapshotEvidence();
        Properties values = new Properties();
        values.setProperty("format", "fragguard-vacuum-snapshot-1");
        values.setProperty("verified", "false");
        values.setProperty("maintenance_operation_id", operationId);
        values.setProperty("candidate", result.candidate().getFileName().toString());
        values.setProperty("source_identity_sha256", result.sourceIdentitySha256());
        values.setProperty("sqlite_snapshot_time", "between_invocation_and_completion");
        values.setProperty("invoked_at_millis", Long.toString(result.invocationMillis()));
        values.setProperty("completed_at_millis", Long.toString(result.completedMillis()));
        values.setProperty("candidate_bytes", Long.toString(result.candidateBytes()));
        values.setProperty("sqlite_schema_version", Integer.toString(result.schemaVersion()));
        values.setProperty("included_max_history_id", Long.toString(result.lastIncludedHistoryId()));
        values.setProperty("included_max_rollback_job_id", Long.toString(result.lastIncludedJobId()));
        values.setProperty("included_max_job_updated_at", Long.toString(result.lastIncludedJobUpdateMillis()));
        // Use CREATE_NEW so an interrupted/reused candidate's metadata is
        // never silently rewritten. The final rename is atomic, no REPLACE.
        Path temp = Files.createTempFile(evidence.getParent(), "fg-evidence-", ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                OutputStream output = java.nio.channels.Channels.newOutputStream(channel);
                values.store(output, "Unverified VACUUM INTO source snapshot, not a #166 manifest");
                output.flush();
                channel.force(true);
            }
            try {
                Files.move(temp, evidence, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Atomic compaction evidence persistence is unavailable", exception);
            }
            moved = true;
            // Candidate directory rename durability is independent of the
            // state store in the parent data directory. Fail closed if this
            // host cannot persist the snapshot evidence directory update.
            try (FileChannel directory = FileChannel.open(evidence.getParent(),
                    StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally {
            // Never remove a partially written candidate or marker.
            // A failed temp metadata write is not a verified snapshot.
            if (!moved) Files.deleteIfExists(temp);
        }
    }

    boolean cancel() {
        Job job = active.get();
        if (job == null) return false;
        requestCancellation(job);
        return true;
    }

    private void requestCancellation(Job job) {
        job.cancelled = true;
        PreparedStatement statement = job.statement;
        if (statement != null) {
            try {
                interrupter.execute(() -> {
                    try {
                        statement.cancel();
                    } catch (SQLException ignored) {
                        // Worker still checks cancelled after statement exits.
                    }
                });
            } catch (RejectedExecutionException ignored) {
                // Shutdown may already have stopped the cancellation worker.
            }
        }
    }

    private static <T> T await(CompletableFuture<T> task) throws Exception {
        try {
            return task.get();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Exception checked) throw checked;
            throw new IllegalStateException("Failed to commit maintenance state", exception.getCause());
        }
    }

    private static Thread daemon(String name, Runnable action) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        return thread;
    }

    @Override
    public void close() {
        closed = true;
        Job job = active.get();
        if (job != null) requestCancellation(job);
        worker.shutdown();
        try {
            if (!worker.awaitTermination(15, TimeUnit.SECONDS)) {
                // The source and any candidate are never promoted here; a
                // restarted server recovers a COMPACTING stage as FAILED.
                worker.shutdownNow();
                if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("SQLite compaction is still stopping; "
                            + "preserve the source DB, WAL/SHM and candidate until process exit");
                }
            }
        } catch (InterruptedException failure) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during SQLite compaction shutdown", failure);
        } finally {
            interrupter.shutdownNow();
        }
    }

    private static final class Job {
        final CompletableFuture<Result> response = new CompletableFuture<>();
        volatile boolean cancelled;
        volatile PreparedStatement statement;
        volatile Progress progress = new Progress(true, "starting", "", System.currentTimeMillis(),
                0, 0, "Preparing a read-only SQLite VACUUM INTO snapshot");

        void check() {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Compaction cancelled; candidate is unverified");
            }
        }

        void update(String stage, String candidateName, long cutoff, long bytes, String detail) {
            progress = new Progress(true, stage, candidateName,
                    progress.startedMillis(), cutoff, bytes, detail);
        }
    }
}
