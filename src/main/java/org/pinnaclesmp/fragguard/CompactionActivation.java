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
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Issue #168: host-neutral, MANUAL SQLite candidate activation.
 *
 * Nothing in this class renames, deletes, repairs or opens the production DB
 * for writing. On a normal, checkpointed shutdown it seals a receipt tying
 * the approved candidate to the original and its matching WAL/SHM sidecars.
 * On startup it refuses to open FragGuard's writer until the operator has:
 *
 * 1. Stopped Paper; 2. renamed the original DB and any sidecars to the
 *    exact per-operation backup name; 3. renamed the candidate into place;
 * 4. restarted Paper with the original intact as a separate backup.
 *
 * Every path and byte fingerprint is checked, and the installed candidate
 * receives full read-only integrity, schema, index, history and undo checks
 * before Database.init() may run. The old DB and backup are never removed.
 */
final class CompactionActivation {
    private static final String FORMAT = "fragguard-shutdown-activation-1";
    private static final long MAX_RECEIPT_BYTES = 16 * 1024L;
    private static final int SCAN_TIMEOUT_SECONDS = 21_600;
    private static final List<String> SIDEcars = List.of("-wal", "-shm");

    record ShutdownReceipt(Path path, Path backup, String originalSha256,
                           String candidateSha256) { }

    record Activated(Path installed, Path backup, String candidateSha256,
                     long verifiedRows, String policy) { }

    private CompactionActivation() { }

    static String backupName(MaintenanceSnapshot state) {
        String candidate = state.candidateFile();
        if (!candidate.matches("fragguard-compact-[a-zA-Z0-9_-]+\\.db")) {
            throw new IllegalArgumentException("Maintenance has no safe candidate filename");
        }
        return "fragguard-original-" + candidate.substring("fragguard-compact-".length());
    }

    static Path receiptPath(Path root, MaintenanceSnapshot state) {
        return root.toAbsolutePath().normalize()
                .resolve("compaction-candidates")
                .resolve(state.candidateFile().replaceFirst("\\.db$", ".shutdown.properties"));
    }

    static ShutdownReceipt recordCleanShutdown(Path rootDirectory,
                                               MaintenanceSnapshot state,
                                               boolean cleanShutdown)
            throws IOException {
        return recordCleanShutdown(rootDirectory, state, cleanShutdown, null);
    }

    /**
     * #198: STRICT and Snapshot carry process-bound, fully fingerprinted
     * source/candidate evidence from online prechecks. They are issued only
     * after a clean stopped writer, checkpoint, and unchanged SQLite write
     * version/file identities. Do NOT rehash 30GiB in the host's stop grace.
     * Offline activation independently rechecks every SHA-256 before opening
     * FragGuard's next writer.
     */
    static ShutdownReceipt recordCleanShutdown(Path rootDirectory,
                                               MaintenanceSnapshot state,
                                               boolean cleanShutdown,
                                               CompactionReplacementPolicy.StrictFinalProof strictProof)
            throws IOException {
        Path root = rootDirectory.toAbsolutePath().normalize();
        if (!cleanShutdown || state.phase() != MaintenancePhase.READY_TO_SWAP) {
            throw new IOException("No shutdown receipt: complete a successful SQLite "
                    + "writer stop and WAL checkpoint with an APPROVED candidate");
        }
        safeRoot(root);
        // This gate still sees the original at its expected pathname and the
        // verified candidate in the candidates folder. It must pass BEFORE any
        // manually moved file can be considered legitimate.
        if (strictProof == null) {
            CompactionReplacementPolicy.requireApproved(root, state);
        } else {
            CompactionReplacementPolicy.requireApprovedForShutdownReceipt(root, state);
        }
        var approval = CompactionReplacementPolicy.approvedEvidence(root, state);
        Path candidate = root.resolve("compaction-candidates").resolve(state.candidateFile());
        Path original = root.resolve("fragguard.db");
        Path backup = root.resolve(backupName(state));
        Path receipt = receiptPath(root, state);
        regular(original);
        regular(candidate);
        if (Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Existing shutdown receipt/backup requires manual review; "
                    + "never overwrite a preserved database");
        }
        for (String suffix : SIDEcars) {
            if (Files.exists(backup.resolveSibling(backup.getFileName() + suffix),
                    LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(candidate.resolveSibling(candidate.getFileName() + suffix),
                    LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Conflicting backup/candidate SQLite sidecar; preserve files");
            }
        }
        if (strictProof != null
                && (!strictProof.sourceStamp().equals(
                        CompactionReplacementPolicy.stamp(original))
                    || !strictProof.candidateStamp().equals(
                        CompactionReplacementPolicy.stamp(candidate)))) {
            throw new IOException("Source or candidate changed since online fingerprinting; "
                    + "no shutdown receipt created");
        }
        Path wal = root.resolve("fragguard.db-wal");
        if (Files.exists(wal, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(wal, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(wal) != 0)) {
            throw new IOException("SQLite WAL is nonempty after shutdown/checkpoint. "
                    + "Do not rename or separate DB/WAL/SHM; investigate checkpoint first");
        }

        String sourceHash = strictProof == null
                ? OnlineDatabaseCompaction.fileSha256(original) : strictProof.originalSha256();
        String candidateHash = strictProof == null
                ? OnlineDatabaseCompaction.fileSha256(candidate) : strictProof.candidateSha256();
        if (strictProof != null
                && (!sourceHash.matches("[a-f0-9]{64}")
                    || !candidateHash.matches("[a-f0-9]{64}")
                    || state.policy() != strictProof.policy())) {
            throw new IOException("Missing or mismatched authenticated online file fingerprints");
        }
        if (!candidateHash.equals(approval.verified().getProperty("candidate_sha256"))) {
            throw new IOException("Compaction candidate changed after full verification");
        }
        if (state.policy() == MaintenancePolicy.STRICT
                && !sourceHash.equals(
                        approval.policy().getProperty("strict_source_final_sha256"))) {
            throw new IOException("STRICT source changed since final shutdown comparison");
        }
        if (!OnlineDatabaseCompaction.sourceIdentity(original).equals(
                approval.verified().getProperty("source_identity_sha256"))) {
            throw new IOException("Original DB identity changed after compaction");
        }

        Properties out = new Properties();
        out.setProperty("format", FORMAT);
        out.setProperty("maintenance_operation_id", state.operationId());
        out.setProperty("policy", state.policy().name());
        out.setProperty("candidate", state.candidateFile());
        out.setProperty("backup_file", backup.getFileName().toString());
        out.setProperty("manifest", state.manifestFile());
        out.setProperty("original_sha256", sourceHash);
        out.setProperty("original_bytes", Long.toString(Files.size(original)));
        out.setProperty("source_identity_sha256",
                approval.verified().getProperty("source_identity_sha256"));
        out.setProperty("candidate_sha256", candidateHash);
        out.setProperty("candidate_bytes", Long.toString(Files.size(candidate)));
        out.setProperty("verified_manifest_sha256",
                OnlineDatabaseCompaction.fileSha256(approval.verifiedFile()));
        out.setProperty("approved_policy_sha256",
                OnlineDatabaseCompaction.fileSha256(approval.policyFile()));
        out.setProperty("snapshot_invoked_at_millis",
                approval.verified().getProperty("invoked_at_millis"));
        out.setProperty("snapshot_completed_at_millis",
                approval.verified().getProperty("completed_at_millis"));
        out.setProperty("checkpoint_confirmed", "true");
        out.setProperty("writer_stopped_and_drained", "true");
        out.setProperty("shutdown_at_millis", Long.toString(System.currentTimeMillis()));
        for (String suffix : SIDEcars) {
            String key = suffix.substring(1);
            Path sidecar = root.resolve("fragguard.db" + suffix);
            boolean present = Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS);
            if (present) regular(sidecar);
            out.setProperty(key + "_present", Boolean.toString(present));
            out.setProperty(key + "_bytes", present
                    ? Long.toString(Files.size(sidecar)) : "0");
            out.setProperty(key + "_sha256", present
                    ? OnlineDatabaseCompaction.fileSha256(sidecar) : "");
        }
        persist(receipt, out);
        return new ShutdownReceipt(receipt, backup, sourceHash, candidateHash);
    }

    /**
     * Lightweight early gate used by MaintenanceCoordinator.open when the
     * candidate is no longer at its original path. Heavy hashes and complete
     * SQLite verification follow in verifyInstalled() BEFORE Database.init().
     */
    static void requirePreparedActivation(Path root, MaintenanceSnapshot state)
            throws IOException {
        Path base = root.toAbsolutePath().normalize();
        safeRoot(base);
        if (state.phase() != MaintenancePhase.READY_TO_SWAP) {
            throw new IOException("No approved compaction is ready for activation");
        }
        var approval = CompactionReplacementPolicy.approvedEvidence(base, state);
        Properties receipt = read(receiptPath(base, state));
        validateReceipt(base, state, approval, receipt);
        Path candidate = base.resolve("compaction-candidates").resolve(state.candidateFile());
        Path backup = base.resolve(backupName(state));
        Path installed = base.resolve("fragguard.db");
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(installed, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(backup) || Files.isSymbolicLink(installed)) {
            throw new IOException("Manual replacement is incomplete or ambiguous. Stop Paper; "
                    + "preserve every DB and sidecar; rename original to " + backupName(state)
                    + " and move only the verified candidate to fragguard.db");
        }
        // No new WAL/SHM may accompany a newly installed, standalone VACUUM
        // candidate; never copy the original's WAL onto the new candidate.
        for (String suffix : SIDEcars) {
            if (Files.exists(installed.resolveSibling("fragguard.db" + suffix),
                    LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(candidate.resolveSibling(state.candidateFile() + suffix),
                    LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Unexpected active/candidate SQLite " + suffix
                        + " sidecar. Never mix original WAL/SHM with the compacted DB");
            }
        }
        checkBackupSidecars(base, state, receipt);
    }

    /**
     * Startup after a crash DURING Database.init() is different from the
     * initial manual swap: SQLite may already have changed journal_mode to
     * WAL and created NEW sidecars. The durable ACTIVATING phase proves the
     * first startup finished a no-WAL byte-for-byte candidate validation
     * before permitting those changes. Never accept an ACTIVATING phase
     * without its intact original backup and shutdown receipt.
     */
    static void requireRecoverableActivation(Path root, MaintenanceSnapshot state)
            throws IOException {
        Path base = root.toAbsolutePath().normalize();
        safeRoot(base);
        if (state.phase() != MaintenancePhase.ACTIVATING) {
            throw new IOException("No durable in-progress activation to recover");
        }
        var approved = CompactionReplacementPolicy.approvedEvidence(base, state);
        Properties receipt = read(receiptPath(base, state));
        validateReceipt(base, state, approved, receipt);
        Path candidate = base.resolve("compaction-candidates").resolve(state.candidateFile());
        Path originalBackup = base.resolve(backupName(state));
        Path installed = base.resolve("fragguard.db");
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unexpected pre-activation candidate in an ACTIVATING operation");
        }
        regular(originalBackup);
        regular(installed);
        checkBackupSidecars(base, state, receipt);
        for (String suffix : SIDEcars) {
            // Unlike the first startup, these can be legitimate NEW sidecars
            // written by DB.init(). They must be safe regular files and the
            // full read-only verification must validate their logical rows.
            Path sidecar = base.resolve("fragguard.db" + suffix);
            if (Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) regular(sidecar);
        }
    }

    static Activated verifyRecovering(Path root, MaintenanceSnapshot state)
            throws Exception {
        Path base = root.toAbsolutePath().normalize();
        requireRecoverableActivation(base, state);
        var approval = CompactionReplacementPolicy.approvedEvidence(base, state);
        Properties receipt = read(receiptPath(base, state));
        Path installed = base.resolve("fragguard.db");
        Path backup = base.resolve(backupName(state));
        if (Files.size(backup) != number(receipt, "original_bytes")
                || !OnlineDatabaseCompaction.fileSha256(backup).equals(
                        receipt.getProperty("original_sha256"))) {
            throw new IOException("Original backup changed during interrupted activation");
        }
        String originalHash = approval.policy().getProperty("strict_source_final_sha256", "");
        if (state.policy() == MaintenancePolicy.STRICT
                && !originalHash.equals(receipt.getProperty("original_sha256"))) {
            throw new IOException("Recovered STRICT original backup no longer matches approval");
        }

        String before = OnlineDatabaseCompaction.fileSha256(installed);
        Path wal = base.resolve("fragguard.db-wal");
        boolean walPresentBefore = Files.exists(wal, LinkOption.NOFOLLOW_LINKS);
        long walBytesBefore = walPresentBefore ? Files.size(wal) : 0L;
        String walShaBefore = walPresentBefore
                ? OnlineDatabaseCompaction.fileSha256(wal) : "";

        // A WAL-mode header changes the *file* SHA without changing any
        // retained rows. The logical schema+table hashes MUST still equal
        // the verified, original VACUUM snapshot; otherwise refuse recovery.
        DatabaseDeepDiagnostics.Report report;
        try (DatabaseDeepDiagnostics diagnostic = new DatabaseDeepDiagnostics(base, installed)) {
            report = diagnostic.run(SCAN_TIMEOUT_SECONDS)
                    .get(SCAN_TIMEOUT_SECONDS + 60L, TimeUnit.SECONDS);
        }
        if (!report.complete() || !report.passed()
                || report.schemaVersion() != Database.SCHEMA_VERSION
                || report.tables().size() != 4
                || !report.schemaSha256().equals(
                        approval.verified().getProperty("schema_sha256"))) {
            throw new IOException("Interrupted activation failed full SQLite schema or integrity checks");
        }
        for (var entry : report.tables().entrySet()) {
            String key = entry.getKey() + ".";
            var digest = entry.getValue();
            if (!Long.toString(digest.rows()).equals(
                    approval.verified().getProperty(key + "rows"))
                    || !Long.toString(digest.blobBytes()).equals(
                            approval.verified().getProperty(key + "blob_bytes"))
                    || !digest.sha256().equals(
                            approval.verified().getProperty(key + "sha256"))) {
                throw new IOException("Interrupted activation has different saved history "
                        + "or rollback/undo records: " + entry.getKey());
            }
        }
        requireRecoverableActivation(base, state);
        if (!before.equals(OnlineDatabaseCompaction.fileSha256(installed))) {
            throw new IOException("Installed SQLite database changed during activation recovery");
        }
        // sqlite-jdbc can create a NEW empty WAL/SHM and update SHM read
        // marks even for mode=ro / query_only scans. SHM is transient
        // reader bookkeeping, not a content fingerprint. Reject changes
        // to committed WAL frames instead of demanding identical sidecar
        // existence and mutable SHM bytes around a read-only inspection.
        boolean walPresentAfter = Files.exists(wal, LinkOption.NOFOLLOW_LINKS);
        long walBytesAfter = walPresentAfter ? Files.size(wal) : 0L;
        if (walBytesBefore > 32L) {
            // A nonempty WAL with frames is part of the *new* installed
            // candidate's logical state. A read-only scan must not alter it.
            if (!walPresentAfter || walBytesAfter != walBytesBefore
                    || !walShaBefore.equals(OnlineDatabaseCompaction.fileSha256(wal))) {
                throw new IOException("Committed WAL frames changed during activation verification");
            }
        } else if (walBytesAfter > 32L) {
            // No WAL frames existed before this scan; a new frame is an
            // unexpected writer, not normal read-only sidecar creation.
            throw new IOException("Unexpected SQLite WAL frames appeared during recovery scan");
        }
        // Require safe file types after the scan, including reader-created
        // shared-memory sidecars, but do not fingerprint mutable -shm data.
        requireRecoverableActivation(base, state);
        if (!receipt.getProperty("original_sha256").equals(
                OnlineDatabaseCompaction.fileSha256(backup))) {
            throw new IOException("Original recovery backup was modified during verification");
        }
        return new Activated(installed, backup,
                approval.verified().getProperty("candidate_sha256"),
                report.scannedRows(), state.policy().name());
    }

    static Activated verifyInstalled(Path root, MaintenanceSnapshot state)
            throws Exception {
        Path base = root.toAbsolutePath().normalize();
        requirePreparedActivation(base, state);
        var approval = CompactionReplacementPolicy.approvedEvidence(base, state);
        Properties receipt = read(receiptPath(base, state));
        Path installed = base.resolve("fragguard.db");
        Path backup = base.resolve(backupName(state));

        if (Files.size(backup) != number(receipt, "original_bytes")
                || !OnlineDatabaseCompaction.fileSha256(backup).equals(
                    receipt.getProperty("original_sha256"))) {
            throw new IOException("Preserved original database is missing, modified or not "
                    + "the checkpointed source. Never discard backup/WAL/SHM");
        }
        if (state.policy() == MaintenancePolicy.STRICT
                && !receipt.getProperty("original_sha256").equals(
                    approval.policy().getProperty("strict_source_final_sha256"))) {
            throw new IOException("STRICT backup differs from the final approved source");
        }
        String expected = approval.verified().getProperty("candidate_sha256");
        if (Files.size(installed) != number(receipt, "candidate_bytes")
                || !OnlineDatabaseCompaction.fileSha256(installed).equals(expected)) {
            throw new IOException("Installed fragguard.db is not the verified compacted candidate");
        }

        // Full read-only SQLite verification before ANY writable connection.
        // This is intentionally a startup gate, never Paper's live tick path.
        DatabaseDeepDiagnostics.Report scan;
        try (DatabaseDeepDiagnostics diagnostic = new DatabaseDeepDiagnostics(base, installed)) {
            scan = diagnostic.run(SCAN_TIMEOUT_SECONDS)
                    .get(SCAN_TIMEOUT_SECONDS + 60L, TimeUnit.SECONDS);
        }
        if (!scan.complete() || !scan.passed()
                || scan.schemaVersion() != Database.SCHEMA_VERSION
                || scan.tables().size() != 4
                || !scan.schemaSha256().equals(
                        approval.verified().getProperty("schema_sha256"))) {
            throw new IOException("Installed compacted DB failed full SQLite integrity, "
                    + "schema/index, rollback and history verification");
        }
        for (var entry : scan.tables().entrySet()) {
            String prefix = entry.getKey() + ".";
            var found = entry.getValue();
            if (!Long.toString(found.rows()).equals(
                    approval.verified().getProperty(prefix + "rows"))
                    || !Long.toString(found.blobBytes()).equals(
                    approval.verified().getProperty(prefix + "blob_bytes"))
                    || !found.sha256().equals(
                    approval.verified().getProperty(prefix + "sha256"))) {
                throw new IOException("Compacted SQLite history / rollback snapshot differs: "
                        + entry.getKey());
            }
        }
        // Detect concurrent edits or a missing/added sidecar during the scan.
        requirePreparedActivation(base, state);
        if (!expected.equals(OnlineDatabaseCompaction.fileSha256(installed))
                || !receipt.getProperty("original_sha256").equals(
                    OnlineDatabaseCompaction.fileSha256(backup))) {
            throw new IOException("Installed candidate or original backup changed during verification");
        }
        return new Activated(installed, backup, expected, scan.scannedRows(),
                state.policy().name());
    }

    static List<String> manualInstructions(Path root, MaintenanceSnapshot state) {
        Path base = root.toAbsolutePath().normalize();
        String source = "plugins/FragGuard/fragguard.db";
        String backup = "plugins/FragGuard/" + backupName(state);
        String candidate = "plugins/FragGuard/compaction-candidates/" + state.candidateFile();
        String receipt = "plugins/FragGuard/compaction-candidates/"
                + receiptPath(base, state).getFileName();
        return List.of(
            "1. Stop the Paper server completely; wait for the FragGuard clean-shutdown "
                    + "checkpoint/activation receipt. NEVER move files while Paper is running.",
            "2. Using only your hosting file manager, rename " + source + " to " + backup
                    + " (do not delete or overwrite the original).",
            "3. If " + source + "-wal or " + source + "-shm exist, rename each alongside "
                    + "the matching backup to " + backup + "-wal / " + backup
                    + "-shm. Keep original DB and its sidecars together; do not reuse "
                    + "them with the compacted copy.",
            "4. Rename " + candidate + " to " + source
                    + ". This candidate must be moved, not simply duplicated; "
                    + "leave the .manifest, .policy.properties, .snapshot.properties "
                    + "and " + receipt + " untouched.",
            "5. Confirm there is no " + source
                    + "-wal or -shm belonging to the old database next to the new DB. "
                    + "Do not delete or try to repair conflicting sidecars.",
            "6. Start Paper normally. FragGuard checks the installed candidate, "
                    + "original backup, sidecars, manifests and complete history/undo data "
                    + "BEFORE opening its writer. If any check fails, stop and review "
                    + "files without deleting or renaming anything further.",
            "The Minecraft world is NOT rolled back. Snapshot policy may lose "
                    + "FragGuard changes after the actual SQLite copy; STRICT requires "
                    + "the stopped writer's final source to match exactly."
        );
    }

    private static void validateReceipt(Path root, MaintenanceSnapshot state,
                                        CompactionReplacementPolicy.ApprovedEvidence approval,
                                        Properties receipt) throws IOException {
        if (!FORMAT.equals(receipt.getProperty("format"))
                || !state.operationId().equals(receipt.getProperty("maintenance_operation_id"))
                || !state.policy().name().equals(receipt.getProperty("policy"))
                || !state.candidateFile().equals(receipt.getProperty("candidate"))
                || !backupName(state).equals(receipt.getProperty("backup_file"))
                || !state.manifestFile().equals(receipt.getProperty("manifest"))
                || !"true".equals(receipt.getProperty("checkpoint_confirmed"))
                || !"true".equals(receipt.getProperty("writer_stopped_and_drained"))
                || !approval.verified().getProperty("candidate_sha256").equals(
                        receipt.getProperty("candidate_sha256"))
                || !approval.verified().getProperty("source_identity_sha256").equals(
                        receipt.getProperty("source_identity_sha256"))
                || !approval.verified().getProperty("invoked_at_millis").equals(
                        receipt.getProperty("snapshot_invoked_at_millis"))
                || !approval.verified().getProperty("completed_at_millis").equals(
                        receipt.getProperty("snapshot_completed_at_millis"))
                || !OnlineDatabaseCompaction.fileSha256(approval.verifiedFile()).equals(
                        receipt.getProperty("verified_manifest_sha256"))
                || !OnlineDatabaseCompaction.fileSha256(approval.policyFile()).equals(
                        receipt.getProperty("approved_policy_sha256"))
                || !receipt.getProperty("original_sha256", "").matches("[a-f0-9]{64}")
                || number(receipt, "original_bytes") <= 0
                || number(receipt, "candidate_bytes") <= 0
                || number(receipt, "shutdown_at_millis")
                        < number(receipt, "snapshot_completed_at_millis")) {
            throw new IOException("Compaction shutdown provenance receipt is missing, "
                    + "stale or mismatched. Do not activate database files");
        }
        if (state.policy() == MaintenancePolicy.STRICT
                && !receipt.getProperty("original_sha256").equals(
                        approval.policy().getProperty("strict_source_final_sha256"))) {
            throw new IOException("STRICT shutdown receipt does not match source approval");
        }
    }

    private static void checkBackupSidecars(Path root, MaintenanceSnapshot state,
                                            Properties receipt) throws IOException {
        Path backup = root.resolve(backupName(state));
        for (String suffix : SIDEcars) {
            String key = suffix.substring(1);
            String present = receipt.getProperty(key + "_present", "");
            if (!"true".equals(present) && !"false".equals(present)) {
                throw new IOException("Unknown WAL/SHM presence in checkpoint receipt");
            }
            Path sidecar = backup.resolveSibling(backup.getFileName() + suffix);
            boolean exists = Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS);
            if (exists != "true".equals(present)) {
                throw new IOException("Missing or unexpected original backup " + suffix
                        + " sidecar; stop and preserve all files");
            }
            if (exists) {
                regular(sidecar);
                if (Files.size(sidecar) != number(receipt, key + "_bytes")
                        || !OnlineDatabaseCompaction.fileSha256(sidecar).equals(
                                receipt.getProperty(key + "_sha256"))) {
                    throw new IOException("Incorrect/mismatched original " + suffix
                            + " backup sidecar. Preserve matched DB and sidecars");
                }
            } else if (number(receipt, key + "_bytes") != 0
                    || !receipt.getProperty(key + "_sha256", "").isEmpty()) {
                throw new IOException("Unexpected missing sidecar evidence");
            }
        }
    }

    private static void safeRoot(Path root) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root.resolve("compaction-candidates"))
                || !Files.isDirectory(root.resolve("compaction-candidates"),
                        LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe FragGuard root or candidate directory");
        }
    }

    private static void regular(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file)) {
            throw new IOException("Unsafe SQLite file or sidecar: " + file.getFileName());
        }
    }

    private static long number(Properties props, String key) throws IOException {
        try {
            return Long.parseLong(props.getProperty(key, ""));
        } catch (NumberFormatException bad) {
            throw new IOException("Invalid or missing activation receipt field " + key, bad);
        }
    }

    private static Properties read(Path path) throws IOException {
        regular(path);
        if (Files.size(path) > MAX_RECEIPT_BYTES) {
            throw new IOException("Activation receipt is unexpectedly large");
        }
        Properties p = new Properties();
        try (var input = Files.newInputStream(path)) {
            p.load(input);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Malformed activation receipt", invalid);
        }
        return p;
    }

    private static void persist(Path path, Properties receipt) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path.getParent())) {
            throw new IOException("Activation receipt already exists or directory is unsafe");
        }
        Path temp = Files.createTempFile(path.getParent(), ".fg-shutdown-receipt-", ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                OutputStream output = java.nio.channels.Channels.newOutputStream(channel);
                receipt.store(output, "FragGuard clean shutdown activation proof; do not edit/delete");
                output.flush();
                channel.force(true);
            }
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Cannot atomically persist shutdown activation receipt", unsupported);
            }
            moved = true;
            try (FileChannel dir = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
                dir.force(true);
            }
        } finally {
            if (!moved) Files.deleteIfExists(temp);
        }
    }
}
