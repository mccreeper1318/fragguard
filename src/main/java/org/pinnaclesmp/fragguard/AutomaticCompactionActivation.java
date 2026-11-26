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

/**
 * #206: stopped-server SQLite activation. This is deliberately invoked BEFORE
 * Database.init(); it never opens a writable SQLite connection or touches
 * Minecraft world files.
 *
 * Every physical step is an individual atomic rename followed by an fsynced
 * directory. The durable journal records progress AFTER each move; on crash,
 * the retry reconciles actual files against the authenticated shutdown receipt
 * rather than trusting the journal's last completed step. No backup is removed.
 */
final class AutomaticCompactionActivation {
    private static final String FORMAT = "fragguard-auto-activation-1";
    private static final long MAX_JOURNAL_BYTES = 16 * 1024L;
    private static final List<String> SIDECARS = List.of("-wal", "-shm");

    enum Step {
        PREPARED, ORIGINAL_BACKED_UP, WAL_BACKED_UP, SHM_BACKED_UP,
        CANDIDATE_INSTALLED, VERIFIED
    }

    @FunctionalInterface interface MoveHook {
        void afterMove(Step step) throws IOException;
    }

    private AutomaticCompactionActivation() { }

    static Path journal(Path root, MaintenanceSnapshot state) {
        return CompactionActivation.receiptPath(root, state).resolveSibling(
                state.candidateFile().replaceFirst("\\.db$",
                        ".automatic-activation.properties"));
    }

    static boolean hasJournal(Path root, MaintenanceSnapshot state) {
        return Files.exists(journal(root, state), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * This runs while the state store opens, before any SQLite access. A
     * partial automatic swap is allowed to proceed ONLY with a persisted,
     * operation-bound journal and unmodified approved shutdown receipt.
     * A missing, forged or modified journal fails closed.
     */
    static void requireResumable(Path root, MaintenanceSnapshot state) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        if (state.phase() != MaintenancePhase.READY_TO_SWAP) {
            throw new IOException("Automatic activation requires approved READY maintenance");
        }
        if (Files.isSymbolicLink(base) || Files.isSymbolicLink(base.resolve("compaction-candidates"))
                || !Files.isDirectory(base.resolve("compaction-candidates"),
                        LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe FragGuard activation directory");
        }
        readJournal(base, state);
        // Do not open a writer or attempt recovery here. File topology is
        // reconciled later, on startup, by activateBeforeDatabaseOpen().
    }

    static void activateBeforeDatabaseOpen(Path root, MaintenanceSnapshot state)
            throws Exception {
        activateBeforeDatabaseOpen(root, state, ignored -> { });
    }

    /**
     * Package-private crash hook models power loss immediately AFTER each
     * atomic rename and BEFORE the durable journal step can be saved.
     */
    static void activateBeforeDatabaseOpen(Path root, MaintenanceSnapshot state,
                                           MoveHook hook) throws Exception {
        Objects.requireNonNull(hook);
        if (state.phase() != MaintenancePhase.READY_TO_SWAP) {
            throw new IOException("Automatic activation may only start from READY_TO_SWAP");
        }
        Path base = root.toAbsolutePath().normalize();
        Path candidate = base.resolve("compaction-candidates").resolve(state.candidateFile());
        Path installed = base.resolve("fragguard.db");
        Path backup = base.resolve(CompactionActivation.backupName(state));

        CompactionReplacementPolicy.ApprovedEvidence approval =
                CompactionReplacementPolicy.approvedEvidence(base, state);
        Properties receipt = CompactionActivation.read(
                CompactionActivation.receiptPath(base, state));
        CompactionActivation.validateReceipt(base, state, approval, receipt);

        Step recorded;
        if (hasJournal(base, state)) {
            recorded = readJournal(base, state);
        } else {
            // A legacy/manual swap, with an authenticated backup and no
            // candidate left to move, is handled by the normal verifier.
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                    && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                CompactionActivation.requirePreparedActivation(base, state);
                return;
            }
            // Initial transaction: refuse stale backups, conflicting sidecars,
            // changed originals or candidate before creating a journal.
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Reserved original backup already exists; "
                        + "never overwrite a previous recovery file");
            }
            for (String suffix : SIDECARS) {
                Path backupSidecar = sibling(backup, suffix);
                if (Files.exists(backupSidecar, LinkOption.NOFOLLOW_LINKS)
                        || Files.exists(sibling(candidate, suffix), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Untrusted backup/candidate sidecar before activation");
                }
            }
            requireExactFile(installed, number(receipt, "original_bytes"),
                    receipt.getProperty("original_sha256"));
            requireExactFile(candidate, number(receipt, "candidate_bytes"),
                    receipt.getProperty("candidate_sha256"));
            checkUnmovedSidecars(installed, receipt);
            publishJournal(base, state, Step.PREPARED, receipt, false);
            recorded = Step.PREPARED;
        }

        // Recheck the exact current source of truth before EVERY filesystem
        // change. With a partial move, either the old or new location may
        // hold that component, never both.
        moveOriginal(installed, backup, candidate, receipt, hook);
        advance(base, state, recorded, Step.ORIGINAL_BACKED_UP, receipt);
        moveSidecar(installed, backup, "-wal", receipt, hook, Step.WAL_BACKED_UP);
        advance(base, state, recorded, Step.WAL_BACKED_UP, receipt);
        moveSidecar(installed, backup, "-shm", receipt, hook, Step.SHM_BACKED_UP);
        advance(base, state, recorded, Step.SHM_BACKED_UP, receipt);

        // A candidate is a standalone VACUUM image: no old WAL/SHM may be
        // paired with it. Every original sidecar is in the reserved backup.
        CompactionActivation.checkBackupSidecars(base, state, receipt);
        for (String suffix : SIDECARS) {
            if (Files.exists(sibling(installed, suffix), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(sibling(candidate, suffix), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Unmatched live/candidate SQLite sidecar; "
                        + "preserve original backup and stop");
            }
        }
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(installed, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Untrusted original remains at live path before install");
            }
            requireExactFile(candidate, number(receipt, "candidate_bytes"),
                    receipt.getProperty("candidate_sha256"));
            atomicMove(candidate, installed);
            hook.afterMove(Step.CANDIDATE_INSTALLED);
        }
        requireExactFile(installed, number(receipt, "candidate_bytes"),
                receipt.getProperty("candidate_sha256"));
        requireExactFile(backup, number(receipt, "original_bytes"),
                receipt.getProperty("original_sha256"));
        advance(base, state, recorded, Step.CANDIDATE_INSTALLED, receipt);
        // The caller MUST run CompactionActivation.verifyInstalled() and then
        // Database.init() before the persisted maintenance phase may reach IDLE.
    }

    static List<String> operatorInstructions(MaintenanceSnapshot state) {
        return List.of(
            "FragGuard backs up and activates the verified SQLite database automatically.",
            "After approval, Paper stops gracefully. Wait for the clean shutdown receipt in "
                    + "the server console. FragGuard does not move DB files while Paper runs.",
            "If your host automatically restarts Paper, FragGuard completes activation "
                    + "before opening its SQLite writer. If it stays stopped, press Start "
                    + "ONCE in the hosting panel. No SSH or file manager is needed.",
            "Do NOT rename, copy, delete or mix fragguard.db, -wal, -shm or the candidate.",
            "If activation fails, the server stays restricted. Preserve every original, "
                    + "matching sidecar, candidate and proof. A qualified operator may "
                    + "inspect the console and use the documented manual recovery fallback.",
            "STRICT is the default no-loss policy; Snapshot loss is never automatic. "
                    + "Minecraft worlds are not rolled back. Staff world edits during "
                    + "frozen maintenance are intentionally unlogged."
        );
    }

    static void recordVerifiedBeforeDatabaseOpen(Path root, MaintenanceSnapshot state)
            throws IOException {
        Path base = root.toAbsolutePath().normalize();
        if (hasJournal(base, state)) {
            Step recorded = readJournal(base, state);
            if (recorded.ordinal() < Step.CANDIDATE_INSTALLED.ordinal()) {
                throw new IOException("Automatic SQLite candidate was not fully installed");
            }
            publishJournal(base, state, Step.VERIFIED,
                    CompactionActivation.read(CompactionActivation.receiptPath(base, state)),
                    true);
        }
    }

    private static void moveOriginal(Path source, Path backup, Path candidate,
                                     Properties receipt, MoveHook hook) throws IOException {
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            requireExactFile(backup, number(receipt, "original_bytes"),
                    receipt.getProperty("original_sha256"));
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                // The new candidate might already occupy the live pathname
                // after an interrupted final install. NEVER move it as old DB.
                if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Both original and backup exist; ambiguous activation");
                }
                requireExactFile(source, number(receipt, "candidate_bytes"),
                        receipt.getProperty("candidate_sha256"));
            }
            return;
        }
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing original backup with no candidate to activate");
        }
        requireExactFile(source, number(receipt, "original_bytes"),
                receipt.getProperty("original_sha256"));
        atomicMove(source, backup);
        hook.afterMove(Step.ORIGINAL_BACKED_UP);
    }

    private static void moveSidecar(Path installed, Path backup, String suffix,
                                    Properties receipt, MoveHook hook, Step stage)
            throws IOException {
        String name = suffix.substring(1);
        boolean required = presence(receipt, name);
        Path old = sibling(installed, suffix);
        Path saved = sibling(backup, suffix);
        if (!required) {
            if (Files.exists(old, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(saved, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Unexpected original " + suffix
                        + " after shutdown; refuse mixing SQLite sidecars");
            }
            return;
        }
        if (Files.exists(saved, LinkOption.NOFOLLOW_LINKS)) {
            requireExactFile(saved, number(receipt, name + "_bytes"),
                    receipt.getProperty(name + "_sha256"));
            if (Files.exists(old, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Duplicate old/new " + suffix + " backup sidecars");
            }
            return;
        }
        requireExactFile(old, number(receipt, name + "_bytes"),
                receipt.getProperty(name + "_sha256"));
        atomicMove(old, saved);
        hook.afterMove(stage);
    }

    private static void checkUnmovedSidecars(Path original, Properties receipt)
            throws IOException {
        for (String suffix : SIDECARS) {
            String name = suffix.substring(1);
            Path sidecar = sibling(original, suffix);
            if (presence(receipt, name)) {
                requireExactFile(sidecar, number(receipt, name + "_bytes"),
                        receipt.getProperty(name + "_sha256"));
            } else if (Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Unexpected post-checkpoint " + suffix);
            }
        }
    }

    private static void requireExactFile(Path path, long bytes, String sha) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)
                || Files.size(path) != bytes || !sha.matches("[a-f0-9]{64}")
                || !OnlineDatabaseCompaction.fileSha256(path).equals(sha)) {
            throw new IOException("Untrusted SQLite activation component: "
                    + path.getFileName() + "; no files were deleted");
        }
    }

    private static boolean presence(Properties p, String name) throws IOException {
        String value = p.getProperty(name + "_present", "");
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IOException("Missing shutdown receipt sidecar presence for " + name);
        }
        return "true".equals(value);
    }

    private static Path sibling(Path path, String suffix) {
        return path.resolveSibling(path.getFileName().toString() + suffix);
    }

    private static long number(Properties p, String name) throws IOException {
        try {
            return Long.parseLong(p.getProperty(name, ""));
        } catch (NumberFormatException ex) {
            throw new IOException("Invalid shutdown receipt field " + name, ex);
        }
    }

    private static void atomicMove(Path from, Path to) throws IOException {
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing SQLite rename over an existing recovery component "
                    + to.getFileName());
        }
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Hosting filesystem cannot atomically move SQLite files; "
                    + "preserve all components for manual recovery", unsupported);
        }
        fsyncDirectory(to.getParent());
    }

    private static void advance(Path root, MaintenanceSnapshot state, Step recorded,
                                Step next, Properties receipt) throws IOException {
        // Recorded journal may lag an actual completed rename after host kill.
        // It must never claim progress AHEAD of real verified filesystem state.
        if (recorded.ordinal() < next.ordinal()) {
            publishJournal(root, state, next, receipt, true);
        }
    }

    private static void publishJournal(Path root, MaintenanceSnapshot state,
                                       Step step, Properties receipt, boolean overwrite)
            throws IOException {
        Path path = journal(root, state);
        Properties out = new Properties();
        out.setProperty("format", FORMAT);
        out.setProperty("maintenance_operation_id", state.operationId());
        out.setProperty("candidate", state.candidateFile());
        out.setProperty("phase", step.name());
        out.setProperty("shutdown_receipt_sha256",
                OnlineDatabaseCompaction.fileSha256(
                        CompactionActivation.receiptPath(root, state)));
        if (overwrite) {
            Step previous = readJournal(root, state);
            if (step.ordinal() < previous.ordinal()) {
                throw new IOException("Automatic activation journal must not regress");
            }
        } else if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Automatic activation journal exists; never rewrite an unknown run");
        }
        Path temp = Files.createTempFile(path.getParent(), ".fg-auto-activation-", ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                OutputStream output = java.nio.channels.Channels.newOutputStream(channel);
                out.store(output, "FragGuard recoverable automatic activation; preserve backup");
                output.flush();
                channel.force(true);
            }
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE,
                        overwrite ? StandardCopyOption.REPLACE_EXISTING
                                : StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Durable automatic activation journal is unsupported",
                        unsupported);
            }
            moved = true;
            fsyncDirectory(path.getParent());
        } finally {
            if (!moved) Files.deleteIfExists(temp);
        }
    }

    private static Step readJournal(Path root, MaintenanceSnapshot state)
            throws IOException {
        Path path = journal(root, state);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)
                || Files.size(path) > MAX_JOURNAL_BYTES) {
            throw new IOException("Missing/unsafe automatic activation journal");
        }
        Properties p = new Properties();
        try (var in = Files.newInputStream(path)) {
            p.load(in);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Corrupt automatic activation journal", malformed);
        }
        if (!FORMAT.equals(p.getProperty("format"))
                || !state.operationId().equals(p.getProperty("maintenance_operation_id"))
                || !state.candidateFile().equals(p.getProperty("candidate"))
                || !OnlineDatabaseCompaction.fileSha256(
                        CompactionActivation.receiptPath(root, state))
                        .equals(p.getProperty("shutdown_receipt_sha256"))) {
            throw new IOException("Automatic activation journal does not match this "
                    + "approved candidate or clean shutdown receipt");
        }
        try {
            return Step.valueOf(p.getProperty("phase", ""));
        } catch (IllegalArgumentException missing) {
            throw new IOException("Invalid automatic activation journal phase", missing);
        }
    }

    private static void fsyncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
