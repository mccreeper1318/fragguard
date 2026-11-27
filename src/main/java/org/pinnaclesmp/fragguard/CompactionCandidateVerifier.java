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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Issue #166: independently verifies a VACUUM INTO candidate against evidence
 * captured immediately after SQLite finished copying one consistent snapshot.
 *
 * The original database remains online and may change: never compare against
 * its later mutable counts or infer an exact snapshot timestamp. The source
 * fingerprint identifies the same source file, while the candidate SHA-256
 * captured during #165 binds every copied SQLite byte (including BLOBs, undo
 * records and indexes) to the copy as originally produced. A full read-only
 * integrity, FK, schema and per-table deep scan provides independent checks.
 *
 * Producing a verified manifest does NOT approve a replacement: #167 must
 * enforce the strict/snapshot-loss policy before READY_TO_SWAP is permitted.
 */
final class CompactionCandidateVerifier implements AutoCloseable {
    record Result(Path candidate, Path manifest, String candidateSha256,
                  String schemaSha256, long scannedRows,
                  Map<String, DatabaseDeepDiagnostics.TableDigest> tables) {
        Result {
            tables = Map.copyOf(tables);
        }
    }

    record Progress(boolean running, String stage, long recordsScanned, String detail) { }

    private static final int DEEP_SCAN_TIMEOUT_SECONDS = 21_600;
    private final Path root;
    private final Path source;
    private final Path candidateDir;
    private final MaintenanceCoordinator maintenance;
    private final ExecutorService worker;
    private final AtomicReference<Attempt> active = new AtomicReference<>();
    private volatile Progress latest = new Progress(false, "idle", 0, "No candidate verified");
    private volatile boolean closed;

    CompactionCandidateVerifier(Path dataDirectory, MaintenanceCoordinator maintenance) {
        root = Objects.requireNonNull(dataDirectory).toAbsolutePath().normalize();
        source = root.resolve("fragguard.db");
        candidateDir = root.resolve("compaction-candidates");
        this.maintenance = Objects.requireNonNull(maintenance);
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "FragGuard-Candidate-Verification");
            thread.setDaemon(true);
            return thread;
        });
    }

    Progress progress() {
        Attempt request = active.get();
        if (request == null) return latest;
        DatabaseDeepDiagnostics scan = request.scan;
        long rows = scan == null ? 0 : scan.progress().recordsScanned();
        return new Progress(true, request.stage, rows, request.detail);
    }

    CompletableFuture<Result> verify(OnlineDatabaseCompaction.Result candidate) {
        Objects.requireNonNull(candidate);
        if (closed) return CompletableFuture.failedFuture(
                new IllegalStateException("Candidate verifier is stopped"));
        Attempt request = new Attempt();
        if (!active.compareAndSet(null, request)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Candidate verification already running"));
        }
        try {
            worker.execute(() -> {
                Result answer = null;
                Throwable failure = null;
                try {
                    answer = verifyCandidate(request, candidate);
                } catch (Throwable problem) {
                    failure = problem;
                    try {
                        MaintenanceSnapshot state = maintenance.snapshot();
                        if (state.phase() == MaintenancePhase.VERIFYING
                                && state.candidateFile().equals(candidate.candidate().getFileName().toString())) {
                            await(request.cancelled
                                    ? maintenance.cancel("Candidate verification cancelled; original database preserved")
                                    : maintenance.fail("Candidate verification failed; original database preserved; "
                                            + "unverified candidate requires staff review"));
                        }
                    } catch (Exception stateError) {
                        failure.addSuppressed(stateError);
                    }
                } finally {
                    DatabaseDeepDiagnostics scan = request.scan;
                    long records = scan == null ? 0 : scan.progress().recordsScanned();
                    latest = new Progress(false, failure == null ? "verified" : "failed",
                            records, failure == null
                                    ? "Candidate verified; replacement policy still required (#167)"
                                    : "Verification did not approve candidate; original DB untouched");
                    active.compareAndSet(request, null);
                }
                if (failure == null) request.response.complete(answer);
                else request.response.completeExceptionally(failure);
            });
        } catch (RejectedExecutionException rejected) {
            active.compareAndSet(request, null);
            request.response.completeExceptionally(rejected);
        }
        request.response.whenComplete((value, error) -> {
            if (request.response.isCancelled()) cancel(request);
        });
        return request.response;
    }

    private Result verifyCandidate(Attempt request, OnlineDatabaseCompaction.Result result)
            throws Exception {
        request.check();
        Path candidate = result.candidate().toAbsolutePath().normalize();
        Path evidence = result.snapshotEvidence().toAbsolutePath().normalize();
        if (!candidate.getParent().equals(candidateDir)
                || Files.isSymbolicLink(root) || Files.isSymbolicLink(candidateDir)
                || !Files.isDirectory(candidateDir, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                || !evidence.equals(candidate.resolveSibling(candidate.getFileName() + ".snapshot.properties"))
                || !Files.isRegularFile(evidence, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe or missing compaction candidate/evidence");
        }
        ensureNoSidecars(candidate);
        MaintenanceSnapshot status = maintenance.snapshot();
        if (status.phase() != MaintenancePhase.VERIFYING
                || !status.candidateFile().equals(candidate.getFileName().toString())
                || status.operationId().isBlank()
                || !status.manifestFile().isBlank()) {
            throw new IllegalStateException("Verification requires this active, unapproved candidate");
        }

        Properties proof = new Properties();
        try (var input = Files.newInputStream(evidence)) {
            proof.load(input);
        }
        if (!"fragguard-vacuum-snapshot-1".equals(proof.getProperty("format"))
                || !"false".equals(proof.getProperty("verified"))
                || !status.operationId().equals(proof.getProperty("maintenance_operation_id"))
                || !candidate.getFileName().toString().equals(proof.getProperty("candidate"))
                || !"between_invocation_and_completion".equals(proof.getProperty("sqlite_snapshot_time"))
                || !result.sourceIdentitySha256().equals(proof.getProperty("source_identity_sha256"))
                || !result.sourceIdentitySha256().equals(OnlineDatabaseCompaction.sourceIdentity(source))
                || number(proof, "invoked_at_millis") != result.invocationMillis()
                || number(proof, "completed_at_millis") != result.completedMillis()
                || number(proof, "candidate_bytes") != result.candidateBytes()
                || number(proof, "sqlite_schema_version") != result.schemaVersion()
                || number(proof, "included_max_history_id") != result.lastIncludedHistoryId()
                || number(proof, "included_max_rollback_job_id") != result.lastIncludedJobId()
                || number(proof, "included_max_job_updated_at") != result.lastIncludedJobUpdateMillis()
                || status.snapshotCutoffMillis() != result.invocationMillis()) {
            throw new IOException("Compaction source snapshot evidence does not match operation");
        }
        FrozenMaintenanceProof.Seal frozen = FrozenMaintenanceProof.read(root, status);
        if (!frozen.sha256().equals(proof.getProperty("freeze_proof_sha256"))
                || !Long.toString(frozen.historyGeneration()).equals(
                        proof.getProperty("freeze_history_generation"))
                || !Long.toString(frozen.sqlGeneration()).equals(
                        proof.getProperty("freeze_sql_generation"))) {
            throw new IOException("Candidate copy does not match the frozen operation proof");
        }
        String expectedSha = proof.getProperty("candidate_sha256", "");
        if (!expectedSha.matches("[a-f0-9]{64}") || Files.size(candidate) != result.candidateBytes()) {
            throw new IOException("Candidate snapshot fingerprint/size is missing or inconsistent");
        }
        request.stage = "checking original snapshot fingerprint";
        if (!expectedSha.equals(OnlineDatabaseCompaction.fileSha256(candidate))) {
            throw new IOException("Compaction candidate changed after the source snapshot was captured");
        }
        request.check();

        request.stage = "full SQLite and history verification";
        request.detail = "Read-only full integrity_check, foreign keys, schema, indexes and all BLOB digests";
        DatabaseDeepDiagnostics.Report report;
        try (DatabaseDeepDiagnostics deep = new DatabaseDeepDiagnostics(root, candidate)) {
            request.scan = deep;
            CompletableFuture<DatabaseDeepDiagnostics.Report> task = deep.run(DEEP_SCAN_TIMEOUT_SECONDS);
            while (true) {
                request.check();
                try {
                    report = task.get(250, TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException stillWorking) {
                    // Poll for operator cancellation without holding the main
                    // thread or SQLite writer. The scan owns its own snapshot.
                }
            }
        } finally {
            request.scan = null;
        }
        request.check();
        if (!report.complete() || !report.passed() || report.tables().size() != 4
                || report.schemaVersion() != Database.SCHEMA_VERSION
                || report.schemaVersion() != result.schemaVersion()) {
            throw new IOException("Full candidate integrity/schema/history checks did not pass");
        }

        // Compare the candidate-derived high-water evidence captured by #165.
        // The source is intentionally NOT rescanned now; it may have changed
        // after VACUUM INTO took its SQLite snapshot.
        request.stage = "matching snapshot identity and all table digests";
        try (Connection check = DriverManager.getConnection(
                "jdbc:sqlite:" + candidate.toUri() + "?mode=ro");
             Statement sql = check.createStatement()) {
            sql.execute("PRAGMA query_only = ON");
            if (scalar(sql, "SELECT COALESCE(MAX(id),0) FROM block_changes")
                        != result.lastIncludedHistoryId()
                    || scalar(sql, "SELECT COALESCE(MAX(id),0) FROM rollback_jobs")
                        != result.lastIncludedJobId()
                    || scalar(sql, "SELECT COALESCE(MAX(updated_at),0) FROM rollback_jobs")
                        != result.lastIncludedJobUpdateMillis()) {
                throw new IOException("Compacted snapshot row identities do not match captured evidence");
            }
        }
        request.check();
        ensureNoSidecars(candidate);
        if (Files.size(candidate) != result.candidateBytes()
                || !expectedSha.equals(OnlineDatabaseCompaction.fileSha256(candidate))) {
            throw new IOException("Candidate content changed while being fully verified");
        }
        request.check();

        // Never overwrite an existing manifest: a stale, interrupted or
        // operator-edited verification requires separate recovery (#168).
        Path manifest = candidate.resolveSibling(
                candidate.getFileName().toString().replaceFirst("\\.db$", ".manifest"));
        request.stage = "persisting verified candidate manifest";
        persistManifest(manifest, proof, expectedSha, report);
        request.check();
        await(maintenance.recordVerifiedManifest(manifest.getFileName().toString()));
        // Deliberately not calling markReady(). #167 must first establish a
        // replacement policy, including explicit post-snapshot loss consent.
        return new Result(candidate, manifest, expectedSha, report.schemaSha256(),
                report.scannedRows(), report.tables());
    }

    private static void ensureNoSidecars(Path candidate) throws IOException {
        for (String suffix : new String[]{"-wal", "-shm"}) {
            if (Files.exists(candidate.resolveSibling(candidate.getFileName() + suffix),
                    LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Candidate has unexpected SQLite WAL/SHM sidecar");
            }
        }
    }

    private static long number(Properties p, String key) throws IOException {
        try {
            return Long.parseLong(p.getProperty(key, ""));
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid candidate evidence number: " + key, invalid);
        }
    }

    private static long scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new SQLException("Candidate snapshot high-water mark missing");
            return rows.getLong(1);
        }
    }

    private static void persistManifest(Path manifest, Properties proof, String sha,
                                        DatabaseDeepDiagnostics.Report report) throws IOException {
        if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(manifest.getParent())) {
            throw new IOException("Existing/unsafe candidate manifest must not be overwritten");
        }
        Properties out = new Properties();
        out.setProperty("format", "fragguard-compaction-verified-1");
        out.setProperty("verified", "true");
        out.setProperty("replacement_approved", "false");
        for (String key : new String[]{"maintenance_operation_id", "candidate",
                "source_identity_sha256", "sqlite_snapshot_time",
                "invoked_at_millis", "completed_at_millis", "candidate_bytes",
                "sqlite_schema_version", "included_max_history_id",
                "included_max_rollback_job_id", "included_max_job_updated_at",
                "freeze_proof_sha256", "freeze_history_generation",
                "freeze_sql_generation"}) {
            out.setProperty(key, proof.getProperty(key));
        }
        out.setProperty("candidate_sha256", sha);
        out.setProperty("schema_sha256", report.schemaSha256());
        out.setProperty("verified_scanned_rows", Long.toString(report.scannedRows()));
        out.setProperty("verified_table_count", Integer.toString(report.tables().size()));
        for (var entry : report.tables().entrySet()) {
            String prefix = entry.getKey() + ".";
            out.setProperty(prefix + "rows", Long.toString(entry.getValue().rows()));
            out.setProperty(prefix + "blob_bytes", Long.toString(entry.getValue().blobBytes()));
            out.setProperty(prefix + "sha256", entry.getValue().sha256());
        }

        Path temp = Files.createTempFile(manifest.getParent(), ".fg-verified-", ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                OutputStream output = java.nio.channels.Channels.newOutputStream(channel);
                out.store(output, "Verified SQLite candidate; NOT approved for replacement (#167)");
                output.flush();
                channel.force(true);
            }
            try {
                Files.move(temp, manifest, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Atomic verified manifest persistence is unavailable", unsupported);
            }
            moved = true;
            try (FileChannel dir = FileChannel.open(manifest.getParent(), StandardOpenOption.READ)) {
                dir.force(true);
            }
        } finally {
            if (!moved) Files.deleteIfExists(temp);
        }
    }

    /** Completes only after the worker has stopped and committed its final state. */
    CompletableFuture<Void> whenStopped() {
        Attempt task = active.get();
        return task == null ? CompletableFuture.completedFuture(null)
                : task.response.handle((ignored, failure) -> null);
    }

    boolean cancel() {
        Attempt request = active.get();
        if (request == null) return false;
        cancel(request);
        return true;
    }

    private void cancel(Attempt request) {
        request.cancelled = true;
        DatabaseDeepDiagnostics deep = request.scan;
        if (deep != null) deep.cancel();
    }

    private static <T> T await(CompletableFuture<T> task) throws Exception {
        try {
            return task.get();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception checked) throw checked;
            throw new IllegalStateException("Maintenance state persistence failed", failure.getCause());
        }
    }

    @Override
    public void close() {
        closed = true;
        Attempt request = active.get();
        if (request != null) cancel(request);
        worker.shutdown();
        try {
            if (!worker.awaitTermination(15, TimeUnit.SECONDS)) {
                worker.shutdownNow();
                if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Candidate verification has not safely stopped");
                }
            }
        } catch (InterruptedException interrupted) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Candidate verification shutdown interrupted", interrupted);
        }
    }

    private static final class Attempt {
        final CompletableFuture<Result> response = new CompletableFuture<>();
        volatile boolean cancelled;
        volatile DatabaseDeepDiagnostics scan;
        volatile String stage = "queued";
        volatile String detail = "Candidate has not been validated";
        void check() {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Candidate verification was cancelled");
            }
        }
    }
}
