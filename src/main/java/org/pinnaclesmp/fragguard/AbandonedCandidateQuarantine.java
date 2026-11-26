package org.pinnaclesmp.fragguard;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

/**
 * #207: preserve an abandoned candidate as a whole directory, never as a
 * repaired/approved SQLite copy. This is allowed ONLY before a new countdown
 * in a durable non-running maintenance phase. Existing verified completion
 * archives are not moved or deleted. Quota limits stop unbounded accumulation.
 */
final class AbandonedCandidateQuarantine {
    private static final int MAX_ARCHIVES = 16;
    private static final int MAX_ENTRIES = 512;

    private AbandonedCandidateQuarantine() { }

    static Path archiveIfAbandoned(Path rootDirectory, MaintenanceSnapshot state,
                                   UUID operator) throws IOException {
        Objects.requireNonNull(operator);
        if (!MaintenanceUiActions.canSchedule(state)) {
            throw new IOException("Cannot move candidate artifacts during active maintenance");
        }
        Path root = rootDirectory.toAbsolutePath().normalize();
        Path active = root.resolve("compaction-candidates");
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe FragGuard data directory");
        }
        if (!Files.exists(active, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.isSymbolicLink(active)
                || !Files.isDirectory(active, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe abandoned candidate directory; no automatic recovery");
        }
        int files = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(active)) {
            for (Path file : entries) {
                if (++files > MAX_ENTRIES
                        || Files.isSymbolicLink(file)
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unknown/unsafe candidate entries; "
                            + "preserve files and inspect before retrying");
                }
            }
        }
        if (files == 0) return null;
        Path archiveRoot = root.resolve("compaction-candidates-old");
        if (!Files.exists(archiveRoot, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(archiveRoot);
            fsync(root);
        }
        if (Files.isSymbolicLink(archiveRoot)
                || !Files.isDirectory(archiveRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe completed/abandoned archive directory");
        }
        int archived = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(archiveRoot)) {
            for (Path file : entries) {
                if (++archived >= MAX_ARCHIVES) {
                    throw new IOException("Candidate archive limit reached: preserve old files; "
                            + "independently archive known-safe copies before retrying");
                }
            }
        }
        Path destination = archiveRoot.resolve("abandoned-" + operator + "-"
                + UUID.randomUUID());
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Abandoned candidate archive collision");
        }
        try {
            Files.move(active, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Candidate directory cannot be moved atomically; "
                    + "preserve all data for manual review", unsupported);
        }
        fsync(root);
        fsync(archiveRoot);
        // Never automatically remove a recovered/abandoned candidate or
        // claim its provenance makes it safe for activation.
        return destination;
    }

    private static void fsync(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
