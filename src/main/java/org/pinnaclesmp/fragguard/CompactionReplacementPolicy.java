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
 * STRICT first performs its expensive full source/candidate comparison while
 * Paper is still running, guarded by a dedicated SQLite data_version watcher.
 * A later INSERT, UPDATE, DELETE or changed source/candidate file invalidates
 * that proof. Only a clean, checkpointed stopped writer can complete the short
 * final approval (#198). Offline activation still rehashes the actual files.
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
            "STRICT performs a full background source/candidate comparison before shutdown. "
            + "A SQLite write-version watcher must remain unchanged up to the pre-stop gate. "
            + "After the checkpoint, source/candidate file identity and zero drained writes "
            + "must still match. If any evidence changes, approval is refused. Never stop "
            + "Paper until the full precheck completes. A valid shutdown receipt and offline "
            + "activation checks remain mandatory.";

    /** A READY snapshot cannot reopen the source writer until #168 validates activation. */
    static boolean blocksWriterStartup(MaintenanceSnapshot state) {
        return state.phase() == MaintenancePhase.READY_TO_SWAP
                || state.phase() == MaintenancePhase.ACTIVATING;
    }

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
    // Transient, process-bound proof only: never recover it after a crash or reload.
    // The read-only watcher sees commits made by the separate live SQLite writer.
    private StrictProbe pendingStrictProof;
    private volatile boolean strictValidatedBeforeStop;
    private volatile StrictFinalProof finalizedStrictProof;
    private volatile boolean closed;

    record StrictFinalProof(String originalSha256, String candidateSha256,
                            FileStamp sourceStamp, FileStamp candidateStamp) { }
    record FileStamp(long bytes, java.nio.file.attribute.FileTime modified,
                     Object fileKey) { }
    private record StrictProbe(String operationId, Connection watcher, long dataVersion,
                               FileStamp sourceStamp, FileStamp candidateStamp,
                               String sourceSha256, String candidateSha256) { }

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
            // This can take many minutes on a 40M+ row history. It must happen
            // BEFORE a host with a short stop grace starts terminating Paper.
            if (pendingStrictProof != null) {
                throw new IOException("A STRICT precheck is already pending; do not "
                        + "prepare a second source comparison");
            }
            StrictProbe probe = runOnlineStrictPrecheck(state, verified);
            try {
                Properties pending = policyProperties(state, verified, "PENDING_FINAL_SHUTDOWN");
                pending.setProperty("consent_at_millis", "0");
                pending.setProperty("consent_operator_uuid", "");
                Path target = pendingFile(directory, state);
                persist(target, pending);
                pendingStrictProof = probe;
                strictValidatedBeforeStop = false;
                return new Decision(state.policy(), "PENDING_FINAL_SHUTDOWN", target,
                        state.operationId(), lower, upper, 0, "", STRICT_WARNING);
            } catch (Throwable error) {
                probe.watcher().close();
                throw error;
            }
        });
    }

    /**
     * #198: check the in-memory watcher BEFORE Database.shutdown() invokes
     * wal_checkpoint(TRUNCATE). SQLite can increment PRAGMA data_version on a
     * checkpoint with ZERO row changes, so treating a checkpoint-only change
     * as an application write would reject even a perfect clean stop.
     *
     * A later external/queued write must also modify source file identity;
     * the caller requires zero drained writes and confirms a clean checkpoint
     * before finalize. We still rehash the source in offline activation.
     */
    CompletableFuture<Boolean> validateBeforeStoppingWriter() {
        return run(() -> {
            strictValidatedBeforeStop = false;
            MaintenanceSnapshot state = verifiedState();
            StrictProbe probe = pendingStrictProof;
            if (probe == null || !state.operationId().equals(probe.operationId())) {
                throw new IOException("STRICT online precheck not complete in this process");
            }
            if (readDataVersion(probe.watcher()) != probe.dataVersion()
                    || !stamp(source).equals(probe.sourceStamp())
                    || !stamp(candidatePath(state)).equals(probe.candidateStamp())
                    || walHasFrames()) {
                throw new IOException("SQLite source or compacted copy changed since the "
                        + "online STRICT comparison. Stop without replacing files.");
            }
            strictValidatedBeforeStop = true;
            return true;
        });
    }

    /**
     * Called only after a clean writer stop and WAL checkpoint, with zero
     * accepted writes drained DURING shutdown. No expensive SQLite scan or
     * multi-gigabyte SHA-256 may run inside Paper's stop-grace interval.
     */
    CompletableFuture<Decision> finalizeStrictAfterShutdown(boolean writerStopped,
            boolean walCheckpointCompleted, boolean allWritesDrained) {
        return run(() -> {
            MaintenanceSnapshot state = verifiedState();
            if (state.policy() != MaintenancePolicy.STRICT) {
                throw new IllegalStateException("Strict finalization requires STRICT policy");
            }
            if (!writerStopped || !walCheckpointCompleted || !allWritesDrained
                    || !strictValidatedBeforeStop) {
                throw new IllegalStateException("Strict mode requires a validated online "
                        + "pre-stop source, fully stopped writer, clean checkpoint "
                        + "and zero untracked writes during shutdown");
            }
            StrictProbe probe = pendingStrictProof;
            if (probe == null || !state.operationId().equals(probe.operationId())) {
                throw new IOException("Missing successful online STRICT precheck. "
                        + "Do not perform file replacement; a force-killed server "
                        + "must never reuse a prior process's uncommitted proof.");
            }
            if (walHasFrames()) {
                throw new IOException("Nonempty source WAL after shutdown; STRICT refused");
            }
            // Empty checkpoints can advance SQLite data_version while leaving
            // the main database bytes unchanged. Physical identity + zero
            // drained writes are authoritative here; the version was checked
            // immediately before stopping the writer.
            if (!stamp(source).equals(probe.sourceStamp())
                    || !stamp(candidatePath(state)).equals(probe.candidateStamp())) {
                throw new IOException("Source changed since online STRICT precheck, or "
                        + "candidate was altered; original and candidate preserved.");
            }
            Properties verified = read(verifiedFile(directory, state));
            Properties pending = read(pendingFile(directory, state));
            validatePolicy(pending, state, verified, "PENDING_FINAL_SHUTDOWN");
            if (!probe.candidateSha256().equals(verified.getProperty("candidate_sha256"))
                    || !OnlineDatabaseCompaction.sourceIdentity(source).equals(
                        verified.getProperty("source_identity_sha256"))) {
                throw new IOException("STRICT precheck lost its candidate or source provenance");
            }
            probe.watcher().close();
            pendingStrictProof = null;
            Properties approved = policyProperties(state, verified, "APPROVED");
            approved.setProperty("consent_at_millis", "0");
            approved.setProperty("consent_operator_uuid", "");
            approved.setProperty("strict_source_final_sha256", probe.sourceSha256());
            approved.setProperty("strict_source_checked_at_millis",
                    Long.toString(System.currentTimeMillis()));
            approved.setProperty("strict_source_schema_sha256",
                    verified.getProperty("schema_sha256"));
            Path target = approvedFile(directory, state);
            persist(target, approved);
            await(maintenance.markReady());
            finalizedStrictProof = new StrictFinalProof(
                    probe.sourceSha256(), probe.candidateSha256(),
                    probe.sourceStamp(), probe.candidateStamp());
            return new Decision(state.policy(), "APPROVED", target,
                    state.operationId(), number(verified, "invoked_at_millis"),
                    number(verified, "completed_at_millis"), 0, "", STRICT_WARNING);
        });
    }

    StrictFinalProof finalizedStrictProof() {
        StrictFinalProof proof = finalizedStrictProof;
        if (proof == null || maintenance.snapshot().phase() != MaintenancePhase.READY_TO_SWAP) {
            throw new IllegalStateException("No confirmed STRICT shutdown proof");
        }
        return proof;
    }

    /**
     * Online read-only comparison is safe to perform at length while the host
     * is running. Any committed write on a DIFFERENT SQLite connection after
     * the monitor baseline changes PRAGMA data_version and invalidates the
     * precheck. A complete logical scan catches preexisting divergence.
     *
     * A TRUNCATE checkpoint is required before file hashing: otherwise the
     * main DB hash could exclude uncheckpointed WAL frames, and shutdown could
     * change the main DB even with no later commits.
     */
    private StrictProbe runOnlineStrictPrecheck(MaintenanceSnapshot state,
                                                 Properties verified) throws Exception {
        checkpointForPrecheck();
        Connection watcher = DriverManager.getConnection("jdbc:sqlite:"
                + source.toUri() + "?mode=ro");
        try {
            long baseline = readDataVersion(watcher);
            FileStamp initial = stamp(source);
            String originalSha = OnlineDatabaseCompaction.fileSha256(source);
            DatabaseDeepDiagnostics.Report report;
            try (DatabaseDeepDiagnostics scan = new DatabaseDeepDiagnostics(root)) {
                report = scan.run(21_600).get(21_630, TimeUnit.SECONDS);
            }
            if (!report.complete() || !report.passed()
                    || report.schemaVersion() != Database.SCHEMA_VERSION
                    || !report.schemaSha256().equals(verified.getProperty("schema_sha256"))
                    || report.tables().size() != 4) {
                throw new IOException("Original database did not pass online STRICT comparison");
            }
            for (Map.Entry<String, DatabaseDeepDiagnostics.TableDigest> entry :
                    report.tables().entrySet()) {
                String prefix = entry.getKey() + ".";
                DatabaseDeepDiagnostics.TableDigest digest = entry.getValue();
                if (!Long.toString(digest.rows()).equals(verified.getProperty(prefix + "rows"))
                        || !Long.toString(digest.blobBytes()).equals(
                            verified.getProperty(prefix + "blob_bytes"))
                        || !digest.sha256().equals(verified.getProperty(prefix + "sha256"))) {
                    throw new IOException("Source differs from copied snapshot: "
                            + entry.getKey() + "; STRICT refused, no data was replaced.");
                }
            }
            String secondSha = OnlineDatabaseCompaction.fileSha256(source);
            if (!originalSha.equals(secondSha) || !initial.equals(stamp(source))
                    || baseline != readDataVersion(watcher) || walHasFrames()) {
                throw new IOException("Source was modified during online STRICT comparison. "
                        + "No shutdown approval granted; preserve all files.");
            }
            // Includes a fresh candidate SHA-256 and sidecar check, while
            // Paper remains running and is not subject to its stop timeout.
            checkCandidate(state, verified);
            FileStamp candidateStamp = stamp(candidatePath(state));
            if (baseline != readDataVersion(watcher) || walHasFrames()
                    || !initial.equals(stamp(source))) {
                throw new IOException("SQLite history changed before STRICT precheck completed.");
            }
            return new StrictProbe(state.operationId(), watcher, baseline, initial,
                    candidateStamp, originalSha, verified.getProperty("candidate_sha256"));
        } catch (Throwable failure) {
            watcher.close();
            throw failure;
        }
    }

    private void checkpointForPrecheck() throws Exception {
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + source);
             Statement statement = writer.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 3000");
            try (ResultSet rows = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                if (!rows.next() || rows.getInt(1) != 0) {
                    throw new IOException("STRICT online source checkpoint is busy; "
                            + "retry after other readers close, never force approval");
                }
            }
        }
        if (walHasFrames()) {
            throw new IOException("STRICT source WAL still contains frames before precheck");
        }
    }

    private Path candidatePath(MaintenanceSnapshot state) {
        return directory.resolve(state.candidateFile());
    }

    private boolean walHasFrames() throws IOException {
        Path wal = source.resolveSibling("fragguard.db-wal");
        return Files.exists(wal, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(wal, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(wal) != 0);
    }

    static FileStamp stamp(Path file) throws IOException {
        BasicFileAttributes data = Files.readAttributes(file,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!data.isRegularFile()) throw new IOException("Unsafe STRICT source/candidate path");
        return new FileStamp(data.size(), data.lastModifiedTime(), data.fileKey());
    }

    private static long readDataVersion(Connection watcher) throws java.sql.SQLException {
        try (Statement statement = watcher.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA data_version")) {
            if (!rows.next()) throw new java.sql.SQLException("No SQLite data_version reading");
            return rows.getLong(1);
        }
    }

    /**
     * Policy and manifest evidence are independent of which path contains
     * the installed candidate. This is essential after an administrator
     * manually renames the copy: the former source path now names the copy,
     * and the original has moved to a separate backup.
     *
     * This does NOT itself approve a swap: #168 independently checks the
     * stopped-writer receipt, original backup, candidate bytes and WAL/SHM.
     */
    record ApprovedEvidence(Properties verified, Properties policy,
                            Path verifiedFile, Path policyFile) { }

    static ApprovedEvidence approvedEvidence(Path root, MaintenanceSnapshot state)
            throws IOException {
        if (state.phase() != MaintenancePhase.VERIFYING
                && state.phase() != MaintenancePhase.READY_TO_SWAP
                && state.phase() != MaintenancePhase.ACTIVATING) {
            throw new IOException("Replacement policy requires a verified maintenance phase");
        }
        Path base = root.toAbsolutePath().normalize();
        Path dir = base.resolve("compaction-candidates");
        if (Files.isSymbolicLink(base) || Files.isSymbolicLink(dir)
                || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe or absent compaction manifest directory");
        }
        Path verifiedPath = verifiedFile(dir, state);
        Path policyPath = approvedFile(dir, state);
        Properties verified = read(verifiedPath);
        if (!"fragguard-compaction-verified-1".equals(verified.getProperty("format"))
                || !"true".equals(verified.getProperty("verified"))
                || !"false".equals(verified.getProperty("replacement_approved"))
                || !state.operationId().equals(verified.getProperty("maintenance_operation_id"))
                || !state.candidateFile().equals(verified.getProperty("candidate"))
                || number(verified, "invoked_at_millis") != state.snapshotCutoffMillis()
                || number(verified, "completed_at_millis") < state.snapshotCutoffMillis()
                || number(verified, "sqlite_schema_version") != Database.SCHEMA_VERSION
                || !verified.getProperty("candidate_sha256", "").matches("[a-f0-9]{64}")) {
            throw new IOException("Verified candidate evidence no longer matches maintenance");
        }
        Properties policy = read(policyPath);
        validatePolicy(policy, state, verified, "APPROVED");
        if (state.policy() == MaintenancePolicy.STRICT) {
            if (!policy.getProperty("strict_source_final_sha256", "")
                    .matches("[a-f0-9]{64}")) {
                throw new IOException("Strict source shutdown fingerprint is missing");
            }
        } else if (!digestText(SNAPSHOT_ACKNOWLEDGEMENT).equals(
                    policy.getProperty("consent_statement_sha256", ""))
                || !state.operatorUuid().equals(policy.getProperty("consent_operator_uuid"))
                || number(policy, "consent_at_millis")
                    < number(verified, "completed_at_millis")) {
            throw new IOException("Signed snapshot-loss acknowledgement is missing or invalid");
        }
        return new ApprovedEvidence(verified, policy, verifiedPath, policyPath);
    }

    boolean hasPendingStrict() {
        MaintenanceSnapshot state = maintenance.snapshot();
        return state.phase() == MaintenancePhase.VERIFYING
                && state.policy() == MaintenancePolicy.STRICT
                && !state.candidateFile().isBlank()
                && Files.isRegularFile(pendingFile(directory, state), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Shared fail-closed READY gate for the coordinator, including after a
     * restart. This does not itself prove manual activation: #168 must also
     * validate the backup, matched sidecars and installed DB after swapping.
     */
    static void requireApproved(Path root, MaintenanceSnapshot state) throws IOException {
        requireApproved(root, state, true);
    }

    /**
     * A STRICT clean shutdown may use the in-process write-version watcher +
     * cached, fully scanned fingerprints. Only the authenticated receipt path
     * may defer another full source hash to offline activation/startup.
     */
    static void requireApprovedForShutdownReceipt(Path root, MaintenanceSnapshot state)
            throws IOException {
        if (state.policy() != MaintenancePolicy.STRICT
                || state.phase() != MaintenancePhase.READY_TO_SWAP) {
            throw new IOException("Fast receipt proof only allowed for sealed STRICT shutdown");
        }
        requireApproved(root, state, false);
    }

    private static void requireApproved(Path root, MaintenanceSnapshot state,
                                        boolean verifySourceFingerprint) throws IOException {
        if (state.phase() != MaintenancePhase.VERIFYING
                && state.phase() != MaintenancePhase.READY_TO_SWAP) {
            throw new IOException("Cannot promote maintenance outside verification");
        }
        Path base = root.toAbsolutePath().normalize();
        Path dir = base.resolve("compaction-candidates");
        Properties verified = read(verifiedFile(dir, state));
        if (!"fragguard-compaction-verified-1".equals(verified.getProperty("format"))
                || !"true".equals(verified.getProperty("verified"))
                || !"false".equals(verified.getProperty("replacement_approved"))
                || !state.operationId().equals(verified.getProperty("maintenance_operation_id"))
                || !state.candidateFile().equals(verified.getProperty("candidate"))
                || number(verified, "invoked_at_millis") != state.snapshotCutoffMillis()
                || number(verified, "sqlite_schema_version") != Database.SCHEMA_VERSION) {
            throw new IOException("Recorded verified candidate no longer matches maintenance");
        }
        Properties policy = read(approvedFile(dir, state));
        validatePolicy(policy, state, verified, "APPROVED");
        Path original = base.resolve("fragguard.db");
        if (!Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)
                || !OnlineDatabaseCompaction.sourceIdentity(original).equals(
                        verified.getProperty("source_identity_sha256"))) {
            throw new IOException("Original database file identity has changed; #168 must "
                    + "verify manual activation before running FragGuard");
        }
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
        } else {
            // #168 has not yet implemented authenticated activation of a
            // manually installed candidate. A missing candidate on startup
            // cannot be silently accepted as a successful file swap.
            throw new IOException("Compaction candidate is missing; do not start until "
                    + "manual activation is verified (#168)");
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
            // A strict SHA-256 source comparison is done after clean shutdown.
            // Recheck after restart, before allowing the live writer to open.
            // This is intentionally NOT done by markReady() on the state
            // worker (state is still VERIFYING at that point). Issue #168
            // will move offline activation checks into its dedicated startup
            // worker; no stale source may be silently accepted meanwhile.
            if (verifySourceFingerprint && state.phase() == MaintenancePhase.READY_TO_SWAP
                    && !finalSha.equals(OnlineDatabaseCompaction.fileSha256(source))) {
                throw new IOException("Final source changed after STRICT approval; "
                        + "refuse activation and preserve original and candidate");
            }
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
        props.setProperty("candidate_verified", "true");
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
                || !"true".equals(props.getProperty("candidate_verified"))
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
        } finally {
            if (pendingStrictProof != null) {
                try {
                    pendingStrictProof.watcher().close();
                } catch (java.sql.SQLException failure) {
                    throw new IllegalStateException("Could not close STRICT data-version watcher",
                            failure);
                } finally {
                    pendingStrictProof = null;
                }
            }
        }
    }
}
