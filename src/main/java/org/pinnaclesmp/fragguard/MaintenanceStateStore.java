package org.pinnaclesmp.fragguard;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/**
 * Atomic maintenance metadata outside fragguard.db. Never touches SQLite files.
 * Invalid/torn/unknown state is a hard error, not a reason to open the server
 * for normal maintenance operation or to discard an existing candidate.
 */
final class MaintenanceStateStore {
    private static final String FILE = "maintenance-state.properties";
    private static final String FORMAT = "1";
    private static final long MAX_BYTES = 16 * 1024;
    private static final List<String> KEYS = List.of(
            "version", "phase", "operationId", "operatorUuid", "requestedAtMillis",
            "countdownEndsAtMillis", "policy", "snapshotCutoffMillis",
            "candidateFile", "manifestFile", "detail", "revision");
    private final Path directory;
    private final Path stateFile;

    MaintenanceStateStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        this.stateFile = this.directory.resolve(FILE);
    }

    MaintenanceSnapshot load() throws IOException {
        if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            return MaintenanceSnapshot.idle();
        }
        if (Files.isSymbolicLink(stateFile) || !Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)
                || Files.size(stateFile) > MAX_BYTES) {
            throw new IOException("FragGuard maintenance state is not a safe regular file");
        }
        Properties p = new Properties();
        try (var in = Files.newInputStream(stateFile)) {
            p.load(in);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Malformed maintenance metadata; preserve the file for recovery", exception);
        }
        if (!FORMAT.equals(p.getProperty("version"))) {
            throw new IOException("Unsupported maintenance-state version; preserve the file for recovery");
        }
        if (!checksum(p).equals(p.getProperty("checksum"))) {
            throw new IOException("Maintenance-state checksum is missing or invalid; manual review required");
        }
        try {
            return new MaintenanceSnapshot(
                    MaintenancePhase.valueOf(required(p, "phase")),
                    required(p, "operationId"),
                    required(p, "operatorUuid"),
                    Long.parseLong(required(p, "requestedAtMillis")),
                    Long.parseLong(required(p, "countdownEndsAtMillis")),
                    MaintenancePolicy.valueOf(required(p, "policy")),
                    Long.parseLong(required(p, "snapshotCutoffMillis")),
                    required(p, "candidateFile"),
                    required(p, "manifestFile"),
                    required(p, "detail"),
                    Long.parseLong(required(p, "revision")));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid persisted maintenance state; manual review required", exception);
        }
    }

    void save(MaintenanceSnapshot snapshot) throws IOException {
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(stateFile)) {
            throw new IOException("Refusing to replace a symbolic-link maintenance state");
        }
        Properties p = new Properties();
        p.setProperty("version", FORMAT);
        p.setProperty("phase", snapshot.phase().name());
        p.setProperty("operationId", snapshot.operationId());
        p.setProperty("operatorUuid", snapshot.operatorUuid());
        p.setProperty("requestedAtMillis", Long.toString(snapshot.requestedAtMillis()));
        p.setProperty("countdownEndsAtMillis", Long.toString(snapshot.countdownEndsAtMillis()));
        p.setProperty("policy", snapshot.policy().name());
        p.setProperty("snapshotCutoffMillis", Long.toString(snapshot.snapshotCutoffMillis()));
        p.setProperty("candidateFile", snapshot.candidateFile());
        p.setProperty("manifestFile", snapshot.manifestFile());
        p.setProperty("detail", snapshot.detail());
        p.setProperty("revision", Long.toString(snapshot.revision()));
        p.setProperty("checksum", checksum(p));

        Path temp = Files.createTempFile(directory, "maintenance-state-", ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                OutputStream out = Channels.newOutputStream(channel);
                p.store(out, "FragGuard maintenance metadata; not a SQLite database");
                out.flush();
                channel.force(true);
            }
            try {
                Files.move(temp, stateFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                // Never fall back to a non-atomic overwrite of the only durable state.
                throw new IOException("Hosting filesystem does not support atomic maintenance-state updates", exception);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing maintenance field " + key);
        }
        return value;
    }

    private static String checksum(Properties p) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> keys = new ArrayList<>(KEYS);
            keys.sort(String::compareTo);
            for (String key : keys) {
                String value = required(p, key);
                byte[] bytes = (key + "=" + value + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IllegalArgumentException exception) {
            throw new IOException("Cannot validate maintenance-state checksum", exception);
        }
    }
}
