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
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Issue #167: approve replacement policy separately from candidate verification.
 *
 * STRICT needs a complete, clean source-database shutdown and a full logical
 * comparison to the immutable VACUUM INTO candidate. Online comparison is not
 * proof of final parity: a later normal INSERT, rollback or retention DELETE
 * invalidates it. This service exposes the offline finalization hook for #168.
 *
 * MAINTENANCE_SNAPSHOT requires an operator's affirmative acknowledgement
 * that *all* FragGuard DB changes after the actual SQLite snapshot (which
 * lies between the recorded invocation and completion) may be discarded.
 * It does NOT roll back Minecraft worlds or protect against WAL/SHM mixing.
 *
 * Only an approved, fsynced file bound to the verified candidate may transition
 * the coordinator to READY_TO_SWAP. The full manual activation and startup
 * provenance verification remain the responsibility of issue #168.
 */
final class CompactionReplacementPolicy implements AutoCloseable {
    static final String SNAPSHOT_ACKNOWLEDGEMENT =
            "I ACCEPT LOSS OF FRAGGUARD HISTORY, ROLLBACK AND UNDO RECORDS, "
            + "AND RETENTION UPDATES AFTER THE SQLITE SNAPSHOT; "
            + "THE MINECRAFT WORLD WILL NOT BE ROLLED BACK.";
    static final String SNAPSHOT_WARNING =
            "Maintenance Snapshot Mode can discard ALL FragGuard database changes after "
            + "the actual SQLite copy, including history, rollback/undo jobs and retention "
            + "changes. The exact SQLite snapshot time is not exposed by JDBC: it is between "
            + "VACUUM invocation and completion, NOT the maintenance-start time. OPs, "
            + "redstone, entities and plugins may keep changing the world and its history. "
            + "The Minecraft world is NEVER rolled back with the database. Saved world "
            + "state and substituted history can disagree. Acknowledgement does NOT "
            + "authorize mixing mismatched WAL/SHM files or bypassing startup checks.";
    static final String STRICT_WARNING =
            "STRICT replacement refuses approval while writes can still occur. "
            + "After the database writer stops and a clean SQLite checkpoint finishes, "
            + "a full scan of the final original and verified snapshot must match. "
            + "A stale candidate is rejected. The file swap itself still requires "
            + "the independent offline activation checks from #168.";

    record Decision(MaintenancePolicy policy, String status, Path policyFile,
                    String operationId, long snapshotLowerBoundMillis,
                    long snapshotUpperBoundMillis, long consentMillis,
                    String consentOperatorUuid, String detail) {
        boolean approved() { return "APPROVED".equals(status); }
    }

    private static final String FORMAT = "fragguard-replacement-policy-1";
    private final Path root;
    private final Path source;
    private final Path directory;
    private final MaintenanceCoordinator maintenance;
    private final ExecutorService worker;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean closed;

    CompactionReplacementPolicy(Path root, MaintenanceCoordinator maintenance) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        this.source = this.root.resolve("fragguard.db");
        this.directory = this.root.resolve("compaction-candidates");
        this.maintenance = Objects.requireNonNull(maintenance);
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "FragGuard-Replacement-Policy");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * May run while Paper is online. Snapshot mode can be approved after
     * deliberate consent; strict mode only records a PENDING shutdown plan.
     * Neither mode performs a database swap.
     */
    CompletableFuture<Decision> prepare(UUID operator, String acknowledgement) {
        return run(() -> {
            MaintenanceSnapshot state = verifiedState();
            if (!state.operatorUuid().equals(Objects.requireNonNull(operator).toString())) {
                throw new IllegalStateException("Only the operator who scheduled this maintenance "
                        + "may approve a database replacement");
            }
            Properties verified = readVerified(state);
            long lower = number(verified, "invoked_at_millis");
            long upper = number(verified, "completed_at_millis");
            if (lower <= 0 || upper < lower
                    || lower != state.snapshotCutoffMillis()) {
                throw new IOException("SQLite snapshot window is not authenticated");
            }
            if (state.policy() == MaintenancePolicy.MAINTENANCE_SNAPSHOT) {
                if (!SNAPSHOT_ACKNOWLEDGEMENT.equals(acknowledgement)) {
                    throw new IllegalArgumentException("Explicit post-snapshot history and "
                            + "undo-loss acknowledgement is required");
                }
                long now = System.currentTimeMillis();
                if (now < upper) throw new IOException("Consent cannot predate completed snapshot");
                Properties approval = policyProperties(state, verified, "APPROVED");
                approval.setProperty("consent_at_millis", Long.toString(now));
                approval.setProperty("consent_operator_uuid", operator.toString());
                approval.setProperty("consent_statement_sha256",
                        digestText(SNAPSHOT_ACKNOWLEDGEMENT));
                approval.setProperty("warning", "World is not rolled back; post-snapshot "
                        + "history and undo data may be lost");
                Path target = approvedFile(directory, state);
                persist(target, approval);
                await(maintenance.markReady());
                return new Decision(state.policy(), "APPROVED", target, state.operationId(),
                        lower, upper, now, operator.toString(), SNAPSHOT_WARNING);
            }
            if (acknowledgement != null && !acknowledgement.isBlank()) {
                throw new IllegalArgumentException("STRICT does not accept snapshot-loss consent");
            }
            Properties pending = policyProperties(state, verified, "PENDING_FINAL_SHUTDOWN");
            pending.setProperty("consent_at_millis", "0");
            pending.setProperty("consent_operator_uuid", "");
            Path target = pendingFile(directory, state);
            persist(target, pending);
            return new Decision(state.policy(), "PENDING_FINAL_SHUTDOWN", target,
                    state.operationId(), lower, upper, 0, "", STRICT_WARNING);
        });
    }

    /**
     * Called ONLY after a clean final database shutdown, not during live
     * maintenance. #168 will wire this into its guarded shutdown/activation
     * flow. No boolean alone certifies a shutdown: the source is scanned in
     * its final, checkpointed, read-only state, then re-fingerprinted.
     */
    CompletableFuture<Decision> finalizeStrictAfterShutdown(boolean writerStopped,
            boolean walCheckpointCompleted, boolean allWritesDrained) {
        return run(() -> {
            MaintenanceSnapshot state = verifiedState();
            if (state.policy() != MaintenancePolicy.STRICT) {
                throw new IllegalStateException("Strict finalization requires STRICT policy");
            }
            if (!writerStopped || !walCheckpointCompleted || !allWritesDrained) {
                throw new IllegalStateException("Strict mode requires a fully stopped writer, "
                        + "clean checkpoint and drained database writes");
            }
            if (Files.exists(source.resolveSibling("fragguard.db-wal"), LinkOption.NOFOLLOW_LINKS)
                    && Files.size(source.resolveSibling("fragguard.db-wal")) != 0) {
                throw new IOException("Nonempty source WAL after shutdown; strict proof refused");
            }
            Properties verified = readVerified(state);
            Properties pending = read(pendingFile(directory, state));
            validatePolicy(pending, state, verified, "PENDING_FINAL_SHUTDOWN");
            // #168 must ensure no external writer is allowed between this scan,
            // shutdown and actual swap; a later startup must recheck the source
            // provenance and candidate hashes before activating.
            String before = OnlineDatabaseCompaction.fileSha256(source);
            DatabaseDeepDiagnostics.Report report;
            try (DatabaseDeepDiagnostics scan = new DatabaseDeepDiagnostics(root)) {
                report = scan.run(21_600).get(21_630, TimeUnit.SECONDS);
            }
            if (!report.complete() || !report.passed()
                    || report.schemaVersion() != Database.SCHEMA_VERSION
                    || !report.schemaSha256().equals(verified.getProperty("schema_sha256"))
                    || report.tables().size() != 4) {
                throw new IOException("Original database failed final strict source validation");
            }
            for (Map.Entry<String, DatabaseDeepDiagnostics.TableDigest> entry :
                    report.tables().entrySet()) {
                String prefix = entry.getKey() + ".";
                DatabaseDeepDiagnostics.TableDigest digest = entry.getValue();
                if (!Long.toString(digest.rows()).equals(verified.getProperty(prefix + "rows"))
                        || !Long.toString(digest.blobBytes()).equals(
                                verified.getProperty(prefix + "blob_bytes"))
                        || !digest.sha256().equals(verified.getProperty(prefix + "sha256"))) {
                    throw new IOException("Final source differs from compacted snapshot: "
                            + entry.getKey() + "; STRICT replacement refused");
                }
            }
            String after = OnlineDatabaseCompaction.fileSha256(source);
            if (!before.equals(after)) {
                throw new IOException("Source database changed during strict shutdown validation");
            }
            // Recheck the copy: source and copy both have to remain intact.
            checkCandidate(state, verified);
            Properties approved = policyProperties(state, verified, "APPROVED");
            approved.setProperty("consent_at_millis", "0");
            approved.setProperty("consent_operator_uuid", "");
            approved.setProperty("strict_source_final_sha256", after);
            approved.setProperty("strict_source_checked_at_millis",
                    Long.toString(System.currentTimeMillis()));
            approved.setProperty("strict_source_schema_sha256", report.schemaSha256());
            Path target = approvedFile(directory, state);
            persist(target, approved);
            await(maintenance.markReady());
            return new Decision(state.policy(), "APPROVED", target,
                    state.operationId(), number(verified, "invoked_at_millis"),
                    number(verified, "completed_at_millis"), 0, "", STRICT_WARNING);
        });
    }

    /**
     * Shared fail-closed READY gate for the coordinator, including after a
     * restart. This does not itself prove manual activation: #168 must also
     * validate the backup, matched sidecars and installed DB after swapping.
     */
    static void requireApproved(Path root, MaintenanceSnapshot state) throws IOException {
        if (state.phase() != MaintenancePhase.VERIFYING
                && state.phase() != MaintenancePhase.READY_TO_SWAP) {
            throw new IOException("Cannot promote maintenance outside verification");
        }
        Path base = root.toAbsolutePath().normalize();
        Path dir = base.resolve("compaction-candidates");
        Properties verified = read(verifiedFile(dir, state));
        Properties policy = read(approvedFile(dir, state));
        validatePolicy(policy, state, verified, "APPROVED");
        Path candidate = dir.resolve(state.candidateFile());
        // The full SHA-256 checks happen in the approval worker, not on
        // MaintenanceCoordinator's state thread: a 57-million-row SQLite file
        // must not stall all lifecycle transitions. #168 rehashes candidate and
        // backup during offline activation.
        if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            for (String suffix : List.of("-wal", "-shm")) {
                if (Files.exists(candidate.resolveSibling(state.candidateFile() + suffix),
                        LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unmatched candidate WAL/SHM sidecar");
                }
            }
            if (Files.size(candidate) != number(verified, "candidate_bytes")) {
                throw new IOException("Compacted file size no longer matches verification");
            }
        } else if (state.phase() != MaintenancePhase.READY_TO_SWAP) {
            throw new IOException("Compaction candidate disappeared before approval");
        }
        if (state.policy() == MaintenancePolicy.STRICT) {
            String finalSha = policy.getProperty("strict_source_final_sha256", "");
            if (!finalSha.matches("[a-f0-9]{64}")) {
                throw new IOException("Strict replacement requires shutdown-attested source digest");
            }
            Path source = base.resolve("fragguard.db");
            // When the server is still awaiting manual activation, the original
            // must be byte-identical to the final shutdown proof. During later
            // activation #168 checks the renamed backup's provenance instead.
            // A strict SHA-256 source comparison is made in the approval
            // worker after complete shutdown; #168 repeats it at activation.
            // Never hash multi-gigabyte SQLite files on the state worker.
        } else if (!digestText(SNAPSHOT_ACKNOWLEDGEMENT).equals(
                policy.getProperty("consent_statement_sha256", ""))
                || !state.operatorUuid().equals(policy.getProperty("consent_operator_uuid"))
                || number(policy, "consent_at_millis") < number(verified, "completed_at_millis")) {
            throw new IOException("Snapshot-loss consent is missing or invalid");
        }
    }

    private MaintenanceSnapshot verifiedState() {
        MaintenanceSnapshot state = maintenance.snapshot();
        if (state.phase() != MaintenancePhase.VERIFYING || state.manifestFile().isBlank()
                || state.candidateFile().isBlank()) {
            throw new IllegalStateException("First fully verify the candidate under issue #166");
        }
        return state;
    }

    private Properties readVerified(MaintenanceSnapshot state) throws IOException {
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Source/candidate directory is unsafe");
        }
        Properties verified = read(verifiedFile(directory, state));
        if (!"fragguard-compaction-verified-1".equals(verified.getProperty("format"))
                || !"true".equals(verified.getProperty("verified"))
                || !"false".equals(verified.getProperty("replacement_approved"))
                || !state.operationId().equals(verified.getProperty("maintenance_operation_id"))
                || !state.candidateFile().equals(verified.getProperty("candidate"))
                || !OnlineDatabaseCompaction.sourceIdentity(source).equals(
                        verified.getProperty("source_identity_sha256"))
                || number(verified, "invoked_at_millis") != state.snapshotCutoffMillis()
                || number(verified, "sqlite_schema_version") != Database.SCHEMA_VERSION) {
            throw new IOException("Verified candidate is not bound to current maintenance operation");
        }
        checkCandidate(state, verified);
        return verified;
    }

    private static void checkCandidate(MaintenanceSnapshot state, Properties verified)
            throws IOException {
        Path directory = Path.of(verified.getProperty("__private_directory"));
        checkCandidate(state, verified, directory.getParent());
    }

    private static void checkCandidate(MaintenanceSnapshot state, Properties verified,
                                       Path root) throws IOException {
        Path dir = root.resolve("compaction-candidates");
        Path candidate = dir.resolve(state.candidateFile());
        for (String suffix : List.of("-wal", "-shm")) {
            if (Files.exists(candidate.resolveSibling(state.candidateFile() + suffix),
                    LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Unmatched SQLite candidate sidecar prevents replacement");
            }
        }
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                || Files.size(candidate) != number(verified, "candidate_bytes")
                || !OnlineDatabaseCompaction.fileSha256(candidate).equals(
                        verified.getProperty("candidate_sha256"))) {
            throw new IOException("Verified compaction candidate changed or disappeared");
        }
    }

    private static Properties policyProperties(MaintenanceSnapshot state, Properties verified,
                                               String status) throws IOException {
        Properties props = new Properties();
        props.setProperty("format", FORMAT);
        props.setProperty("status", status);
        props.setProperty("maintenance_operation_id", state.operationId());
        props.setProperty("candidate", state.candidateFile());
        props.setProperty("manifest", state.manifestFile());
        props.setProperty("policy", state.policy().name());
        props.setProperty("source_identity_sha256", verified.getProperty("source_identity_sha256"));
        props.setProperty("candidate_sha256", verified.getProperty("candidate_sha256"));
        props.setProperty("verified_manifest_sha256",
                OnlineDatabaseCompaction.fileSha256(verifiedFile(
                        // The verified filename comes from persisted state only.
                        Path.of(verified.getProperty("__private_directory")), state)));
        props.setProperty("snapshot_invoked_at_millis",
                verified.getProperty("invoked_at_millis"));
        props.setProperty("snapshot_completed_at_millis",
                verified.getProperty("completed_at_millis"));
        props.setProperty("snapshot_time_kind", "BETWEEN_INVOCATION_AND_COMPLETION");
        props.setProperty("source_replacement_approved", "true");
        return props;
    }

    private static void validatePolicy(Properties props, MaintenanceSnapshot state,
                                       Properties verified, String expectedStatus)
            throws IOException {
        if (!FORMAT.equals(props.getProperty("format"))
                || !expectedStatus.equals(props.getProperty("status"))
                || !state.operationId().equals(props.getProperty("maintenance_operation_id"))
                || !state.candidateFile().equals(props.getProperty("candidate"))
                || !state.manifestFile().equals(props.getProperty("manifest"))
                || !state.policy().name().equals(props.getProperty("policy"))
                || !"true".equals(props.getProperty("source_replacement_approved"))
                || !"BETWEEN_INVOCATION_AND_COMPLETION".equals(
                        props.getProperty("snapshot_time_kind"))
                || !verified.getProperty("candidate_sha256").equals(
                        props.getProperty("candidate_sha256"))
                || !verified.getProperty("source_identity_sha256").equals(
                        props.getProperty("source_identity_sha256"))
                || !verified.getProperty("invoked_at_millis").equals(
                        props.getProperty("snapshot_invoked_at_millis"))
                || !verified.getProperty("completed_at_millis").equals(
                        props.getProperty("snapshot_completed_at_millis"))
                || !OnlineDatabaseCompaction.fileSha256(verifiedFile(
                        Path.of(verified.getProperty("__private_directory")), state))
                        .equals(props.getProperty("verified_manifest_sha256"))) {
            throw new IOException("Replacement policy evidence is stale, modified or mismatched");
        }
    }

    private static Path verifiedFile(Path directory, MaintenanceSnapshot state) {
        return directory.resolve(state.manifestFile());
    }
    private static Path pendingFile(Path directory, MaintenanceSnapshot state) {
        return directory.resolve(state.candidateFile().replaceFirst("\\.db$", ".policy-pending.properties"));
    }
    private static Path approvedFile(Path directory, MaintenanceSnapshot state) {
        return directory.resolve(state.candidateFile().replaceFirst("\\.db$", ".policy.properties"));
    }

    private static Properties read(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) > 16L * 1024) {
            throw new IOException("Policy/verification manifest missing or unsafe");
        }
        Properties properties = new Properties();
        try (var in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IllegalArgumentException corrupted) {
            throw new IOException("Malformed policy/verification manifest", corrupted);
        }
        properties.setProperty("__private_directory", file.getParent().toString());
        return properties;
    }

    private static void persist(Path file, Properties props) throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file.getParent())) {
            throw new IOException("Replacement policy file already exists or directory unsafe");
        }
        props.remove("__private_directory");
        Path tmp = Files.createTempFile(file.getParent(), ".fg-policy-", ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                OutputStream out = java.nio.channels.Channels.newOutputStream(channel);
                props.store(out, "FragGuard replacement policy: offline activation still requires #168");
                out.flush();
                channel.force(true);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException noAtomic) {
                throw new IOException("Atomic policy persistence unsupported", noAtomic);
            }
            moved = true;
            try (FileChannel channel = FileChannel.open(file.getParent(), StandardOpenOption.READ)) {
                channel.force(true);
            }
        } finally {
            if (!moved) Files.deleteIfExists(tmp);
        }
    }

    private static long number(Properties p, String key) throws IOException {
        try {
            return Long.parseLong(p.getProperty(key, ""));
        } catch (NumberFormatException ex) {
            throw new IOException("Missing or invalid policy timestamp/size: " + key, ex);
        }
    }

    private static String digestText(String text) {
        try {
            var hash = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    hash.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private <T> CompletableFuture<T> run(ThrowingSupplier<T> operation) {
        if (closed) return CompletableFuture.failedFuture(
                new IllegalStateException("Policy service has stopped"));
        if (!busy.compareAndSet(false, true)) return CompletableFuture.failedFuture(
                new IllegalStateException("Another replacement policy operation is running"));
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                T result = null;
                Throwable failure = null;
                try {
                    result = operation.get();
                } catch (Throwable error) {
                    failure = error;
                } finally {
                    busy.set(false);
                }
                if (failure == null) future.complete(result);
                else future.completeExceptionally(failure);
            });
        } catch (RuntimeException rejected) {
            busy.set(false);
            future.completeExceptionally(rejected);
        }
        return future;
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException error) {
            if (error.getCause() instanceof Exception exception) throw exception;
            throw new IllegalStateException("Could not persist approved policy", error.getCause());
        }
    }

    @FunctionalInterface private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @Override public void close() {
        closed = true;
        worker.shutdown();
        try {
            if (!worker.awaitTermination(15, TimeUnit.SECONDS)) {
                worker.shutdownNow();
                if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Replacement policy worker still active");
                }
            }
        } catch (InterruptedException ex) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during policy shutdown", ex);
        }
    }
}
