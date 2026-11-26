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
import java.util.Properties;

/**
 * #205: small durable, operation-bound proof of the completed #203/#204
 * barriers. This is METADATA ONLY, outside the historical SQLite source.
 * A copy, its manifest and the STRICT precheck must all reference this exact
 * frozen operation. The logical history/BLOB comparison is still mandatory.
 */
final class FrozenMaintenanceProof {
    private static final String FILE = "maintenance-freeze.properties";
    private static final String FORMAT = "fragguard-frozen-source-1";

    record Seal(String operationId, String sourceIdentity, long fileBytes,
                long fileModifiedMillis, long historyGeneration, long sqlGeneration,
                long sealedAtMillis, String sha256) {
        String diagnostic() {
            return "freezeOperation=" + operationId + ", lastAuthorizedBlockGeneration="
                    + historyGeneration + ", lastAuthorizedSqlGeneration=" + sqlGeneration
                    + ", loggingDrained=true, cleanupDrained=true";
        }
    }

    private FrozenMaintenanceProof() { }

    static Path file(Path root) {
        return root.toAbsolutePath().normalize().resolve(FILE);
    }

    /** Called only after the ordered SQLite worker has completed its query_only seal. */
    static Seal persist(Path root, MaintenanceSnapshot state, Database database) throws IOException {
        if (state.phase() != MaintenancePhase.COUNTDOWN
                || !database.maintenanceFullyFrozen()
                || !database.backgroundMutationsPausedForMaintenance()
                || database.health().queuedWrites() != 0
                || database.inFlightWrites() != 0) {
            throw new IOException("Cannot attest an undrained maintenance write/cleanup freeze");
        }
        return persistSnapshot(root, state,
                database.acceptedHistoryGeneration(), database.acceptedSqlMutationGeneration());
    }

    /** Package-private to allow isolated SQLite maintenance fixtures to model a sealed writer. */
    static Seal persistSnapshot(Path root, MaintenanceSnapshot state,
                                long historyGeneration, long sqlGeneration) throws IOException {
        if (state.phase() != MaintenancePhase.COUNTDOWN
                && state.phase() != MaintenancePhase.ACTIVE) {
            throw new IOException("Freeze certificate requires the same pending maintenance operation");
        }
        if (state.operationId().isBlank() || historyGeneration < 0 || sqlGeneration < 0) {
            throw new IOException("Invalid freeze operation/write generation");
        }
        Path rootPath = root.toAbsolutePath().normalize();
        Path source = rootPath.resolve("fragguard.db");
        if (Files.isSymbolicLink(rootPath)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe or absent frozen SQLite source");
        }
        if (Files.exists(file(rootPath), LinkOption.NOFOLLOW_LINKS)) {
            try {
                Properties old = load(file(rootPath));
                if (state.operationId().equals(old.getProperty("maintenance_operation_id"))) {
                    throw new IOException("This maintenance operation already has a freeze proof");
                }
            } catch (IOException unreadableOld) {
                // Never replace a corrupt proof: first resolve/recover it.
                throw unreadableOld;
            }
        }
        Properties p = new Properties();
        p.setProperty("format", FORMAT);
        p.setProperty("maintenance_operation_id", state.operationId());
        p.setProperty("source_identity_sha256", OnlineDatabaseCompaction.sourceIdentity(source));
        p.setProperty("source_bytes", Long.toString(Files.size(source)));
        p.setProperty("source_modified_millis",
                Long.toString(Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS).toMillis()));
        p.setProperty("accepted_history_generation", Long.toString(historyGeneration));
        p.setProperty("accepted_sql_mutation_generation", Long.toString(sqlGeneration));
        p.setProperty("sealed_at_millis", Long.toString(System.currentTimeMillis()));
        p.setProperty("sqlite_writer_sealed", "true");
        p.setProperty("background_cleanup_drained", "true");
        Path dest = file(rootPath);
        Path tmp = Files.createTempFile(rootPath, ".fg-freeze-", ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                OutputStream out = java.nio.channels.Channels.newOutputStream(channel);
                p.store(out, "FragGuard frozen SQLite operation; source/history files unchanged");
                out.flush();
                channel.force(true);
            }
            try {
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                throw new IOException("Atomic maintenance freeze proof storage unavailable", error);
            }
            moved = true;
            try (FileChannel directory = FileChannel.open(rootPath, StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally {
            if (!moved) Files.deleteIfExists(tmp);
        }
        return read(rootPath, state);
    }

    static Seal read(Path root, MaintenanceSnapshot state) throws IOException {
        Path path = file(root);
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing durable #203/#204 freeze proof; STRICT compaction refused");
        }
        Properties p = load(path);
        if (!FORMAT.equals(p.getProperty("format"))
                || !state.operationId().equals(p.getProperty("maintenance_operation_id"))
                || state.operationId().isBlank()
                || !"true".equals(p.getProperty("sqlite_writer_sealed"))
                || !"true".equals(p.getProperty("background_cleanup_drained"))
                || !p.getProperty("source_identity_sha256", "").matches("[a-f0-9]{64}")) {
            throw new IOException("Frozen SQLite proof does not match this maintenance operation");
        }
        long bytes = requiredLong(p, "source_bytes");
        long modified = requiredLong(p, "source_modified_millis");
        long writes = requiredLong(p, "accepted_history_generation");
        long sql = requiredLong(p, "accepted_sql_mutation_generation");
        long sealed = requiredLong(p, "sealed_at_millis");
        if (bytes <= 0 || modified <= 0 || sealed <= 0 || writes < 0 || sql < 0) {
            throw new IOException("Invalid sealed source identity or write generations");
        }
        return new Seal(state.operationId(), p.getProperty("source_identity_sha256"),
                bytes, modified, writes, sql, sealed,
                OnlineDatabaseCompaction.fileSha256(path));
    }

    static void requireSourceUnchanged(Path root, Seal proof) throws IOException {
        Path source = root.toAbsolutePath().normalize().resolve("fragguard.db");
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || !OnlineDatabaseCompaction.sourceIdentity(source).equals(proof.sourceIdentity())
                || Files.size(source) != proof.fileBytes()
                || Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS).toMillis()
                    != proof.fileModifiedMillis()) {
            throw new IOException("SQLite source changed after sealed maintenance drain ("
                    + proof.diagnostic() + "); no compaction/activation allowed");
        }
        Path wal = root.toAbsolutePath().normalize().resolve("fragguard.db-wal");
        if (Files.exists(wal, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(wal, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(wal) != 0)) {
            throw new IOException("Frozen SQLite WAL acquired frames after checkpoint ("
                    + proof.diagnostic() + "); STRICT refused");
        }
    }

    private static Properties load(Path file) throws IOException {
        Properties p = new Properties();
        try (var in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IllegalArgumentException bad) {
            throw new IOException("Corrupt frozen maintenance proof", bad);
        }
        return p;
    }

    private static long requiredLong(Properties p, String key) throws IOException {
        try {
            return Long.parseLong(p.getProperty(key, ""));
        } catch (NumberFormatException error) {
            throw new IOException("Missing or invalid freeze proof field: " + key, error);
        }
    }
}
