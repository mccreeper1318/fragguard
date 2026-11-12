package org.pinnaclesmp.fragguard;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Storage guardrail for future online VACUUM INTO (#165).
 *
 * A filesystem FileStore signal is NOT a hosting account quota, and Java's
 * temp directory does NOT identify SQLite's native temp VFS. All three are
 * separate pieces of evidence. Estimates are conservative planning signals,
 * not guarantees against SQLITE_FULL while the live database keeps growing.
 *
 * Inspection is read-only, asynchronous and never uses FragGuard's writer.
 * Candidate reservations are a separate, explicit operation; inspection never
 * creates or removes database, WAL, SHM, backup, or candidate files.
 */
final class CompactionStoragePreflight implements AutoCloseable {
    private static final long MIB = 1024L * 1024L;
    private static final long MIN_NATIVE_WORK = 256L * MIB;
    private static final long MIN_WAL_GROWTH = 128L * MIB;
    private static final long MIN_VERIFICATION = 64L * MIB;
    private static final long MIN_OUTPUT = 64L * MIB;
    private static final String RESERVATION_FILE = ".fg-compaction-reservation";
    private static final long MAX_RESERVATION_AGE_MILLIS = 30_000L;

    enum Decision { READY, ACKNOWLEDGEMENT_REQUIRED, READY_WITH_ACKNOWLEDGEMENT, REFUSED }

    /**
     * Operator-confirmed current FREE capacities, not purchased quota sizes.
     * Null means unknown, zero is an explicitly known empty quota. The caller
     * must not reinterpret a Java FileStore signal as account or SQLite temp.
     */
    record Evidence(Long accountFreeBytes, Long sqliteNativeTempFreeBytes,
                    long safetyMarginMiB, boolean acknowledgeUnknown) {
        Evidence {
            if (accountFreeBytes != null && accountFreeBytes < 0
                    || sqliteNativeTempFreeBytes != null && sqliteNativeTempFreeBytes < 0
                    || safetyMarginMiB < 0) {
                throw new IllegalArgumentException("Free-space evidence must not be negative");
            }
        }
    }

    /** Measured values from an immutable point-in-time SQLite read connection. */
    record Measurement(Path database, Long mainBytes, Long walBytes, Long shmBytes,
                       long pageSize, long pageCount, long freeListPages,
                       Long filesystemFreeBytes, Long sqliteTempFilesystemFreeBytes,
                       int existingCandidateFiles, boolean safePaths, Instant measuredAt) {
        Measurement {
            Objects.requireNonNull(database);
            Objects.requireNonNull(measuredAt);
            if (mainBytes == null || mainBytes < 0 || walBytes == null || walBytes < 0
                    || shmBytes == null || shmBytes < 0 || pageSize <= 0 || pageCount < 0
                    || freeListPages < 0 || freeListPages > pageCount
                    || existingCandidateFiles < 0) {
                throw new IllegalArgumentException("Invalid SQLite storage measurements");
            }
            if (filesystemFreeBytes != null && filesystemFreeBytes < 0
                    || sqliteTempFilesystemFreeBytes != null && sqliteTempFilesystemFreeBytes < 0) {
                throw new IllegalArgumentException("Filesystem free bytes must be nonnegative");
            }
        }
    }

    /**
     * All required capacities are INCREMENTAL free bytes. Source main/WAL/SHM
     * and all existing backups stay allocated and are never overwritten.
     */
    record Report(Measurement snapshot, Evidence evidence, long estimatedOutputBytes,
                  long extraWalGrowthBytes, long nativeWorkingBytes,
                  long verificationAllowanceBytes, long safetyBytes,
                  long requiredDataFreeBytes, long requiredNativeTempFreeBytes,
                  Decision decision, List<String> reasons) {
        Report {
            Objects.requireNonNull(snapshot);
            Objects.requireNonNull(evidence);
            Objects.requireNonNull(decision);
            reasons = List.copyOf(reasons);
        }

        boolean mayReserve() {
            return decision == Decision.READY || decision == Decision.READY_WITH_ACKNOWLEDGEMENT;
        }

        List<String> lines() {
            List<String> out = new ArrayList<>();
            out.add("Compaction preflight: " + decision + " (planning estimate, NOT a disk guarantee)");
            out.add("Measured: " + snapshot.measuredAt() + " | Source main "
                    + size(snapshot.mainBytes()) + ", WAL " + size(snapshot.walBytes())
                    + ", SHM " + size(snapshot.shmBytes()) + " (all preserved)");
            out.add("Estimated NEW compacted output: " + size(estimatedOutputBytes)
                    + " | Extra WAL growth allowance: " + size(extraWalGrowthBytes));
            out.add("SQLite working allowance: " + size(nativeWorkingBytes)
                    + " | Verification allowance: " + size(verificationAllowanceBytes)
                    + " | Safety margin: " + size(safetyBytes));
            out.add("Additional FREE data/account headroom estimated: "
                    + size(requiredDataFreeBytes));
            out.add("Additional FREE SQLite native temporary headroom estimated: "
                    + size(requiredNativeTempFreeBytes));
            out.add("Data filesystem usable: " + size(snapshot.filesystemFreeBytes())
                    + " (filesystem ONLY; may not reflect hosting quota)");
            out.add("Operator-confirmed account free: " + size(evidence.accountFreeBytes())
                    + " (unknown unless explicitly supplied)");
            out.add("Operator-confirmed SQLite native temp free: "
                    + size(evidence.sqliteNativeTempFreeBytes())
                    + " (unknown unless explicitly supplied)");
            out.add("Java temp filesystem usable: " + size(snapshot.sqliteTempFilesystemFreeBytes())
                    + " (ADVISORY ONLY, not proof of SQLite VFS temp location)");
            out.add("Existing candidate/reservation files: " + snapshot.existingCandidateFiles());
            for (String reason : reasons) out.add("Preflight: " + reason);
            out.add("Unknown evidence requires explicit operator acknowledgement; "
                    + "known shortages are never overridden.");
            out.add("Existing source, WAL/SHM, backups and unverified candidates are never pruned.");
            return List.copyOf(out);
        }

        private static String size(Long bytes) {
            return bytes == null ? "unknown" : MigrationStoragePolicy.formatBytes(bytes);
        }
    }

    private final Path dataDirectory;
    private final Path databaseFile;
    private final Path candidatesDirectory;
    private final ExecutorService worker;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean closed;

    CompactionStoragePreflight(Path dataDirectory) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory).toAbsolutePath().normalize();
        this.databaseFile = this.dataDirectory.resolve("fragguard.db");
        this.candidatesDirectory = this.dataDirectory.resolve("compaction-candidates");
        worker = Executors.newSingleThreadExecutor(action -> {
            Thread thread = new Thread(action, "FragGuard-Compaction-Preflight");
            thread.setDaemon(true);
            return thread;
        });
    }

    CompletableFuture<Report> run(Evidence evidence) {
        Objects.requireNonNull(evidence);
        if (closed) return CompletableFuture.failedFuture(
                new IllegalStateException("Compaction preflight has stopped"));
        if (!busy.compareAndSet(false, true)) return CompletableFuture.failedFuture(
                new IllegalStateException("Another compaction preflight is in progress"));
        CompletableFuture<Report> result = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                Report report = null;
                Throwable failure = null;
                try {
                    if (closed) throw new IllegalStateException("Compaction preflight has stopped");
                    report = evaluate(measure(), evidence);
                } catch (Throwable error) {
                    failure = error;
                } finally {
                    // Release the single-inspection slot before waking callers:
                    // an immediate second preflight must not spuriously report
                    // "already running" after the first future completes.
                    busy.set(false);
                }
                if (failure == null) result.complete(report);
                else result.completeExceptionally(failure);
            });
        } catch (RejectedExecutionException ex) {
            busy.set(false);
            result.completeExceptionally(ex);
        }
        return result;
    }

    /**
     * Performs no writes; safe to call from a separate maintenance worker,
     * but never synchronously on the Paper main thread.
     */
    Measurement measure() throws IOException, SQLException {
        if (!isDirectory(dataDirectory) || !isRegular(databaseFile)) {
            throw new IOException("FragGuard data folder or active database is unsafe/missing");
        }
        boolean safe = !Files.isSymbolicLink(dataDirectory)
                && (!Files.exists(candidatesDirectory, LinkOption.NOFOLLOW_LINKS)
                    || isDirectory(candidatesDirectory));
        int candidates = 0;
        if (Files.exists(candidatesDirectory, LinkOption.NOFOLLOW_LINKS)
                && isDirectory(candidatesDirectory)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(candidatesDirectory)) {
                for (Path ignored : files) {
                    candidates++;
                    if (candidates == Integer.MAX_VALUE) break;
                }
            }
        }
        long size = Files.size(databaseFile);
        Long walSize = sidecarSize("-wal");
        Long shmSize = sidecarSize("-shm");
        if (walSize == null || shmSize == null) safe = false;
        Long available;
        try {
            available = Files.getFileStore(dataDirectory).getUsableSpace();
        } catch (IOException | SecurityException failure) {
            available = null;
        }
        Long javaTempFree = null;
        try {
            Path javaTemp = Path.of(System.getProperty("java.io.tmpdir", ""));
            if (isDirectory(javaTemp)) {
                javaTempFree = Files.getFileStore(javaTemp).getUsableSpace();
            }
        } catch (IOException | RuntimeException ignored) {
            // Java's temp filesystem is advisory, never evidence of SQLite's temp VFS.
        }
        String url = "jdbc:sqlite:" + databaseFile.toUri() + "?mode=ro";
        try (Connection connection = DriverManager.getConnection(url)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only = ON");
            }
            long pageCount = pragma(connection, "page_count");
            long pageSize = pragma(connection, "page_size");
            long freelist = pragma(connection, "freelist_count");
            return new Measurement(databaseFile, size, walSize, shmSize,
                    pageSize, pageCount, freelist, available, javaTempFree,
                    candidates, safe, Instant.now());
        }
    }

    private static long pragma(Connection c, String name) throws SQLException {
        try (Statement statement = c.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA " + name)) {
            if (!rows.next()) throw new SQLException("Unable to read SQLite page statistics");
            return rows.getLong(1);
        }
    }

    private Long sidecarSize(String suffix) throws IOException {
        Path path = databaseFile.resolveSibling(databaseFile.getFileName() + suffix);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return 0L;
        return isRegular(path) ? Files.size(path) : null;
    }

    /**
     * Calculation deliberately reserves more than an estimated compacted
     * output: additional WAL growth, temp working pages, validation and
     * margin. This does not imply a hard upper limit on online WAL growth.
     */
    static Report evaluate(Measurement measured, Evidence evidence) {
        Objects.requireNonNull(measured);
        Objects.requireNonNull(evidence);
        long liveAllocated = saturatingMultiply(measured.pageCount(), measured.pageSize());
        long logical = MigrationStoragePolicy.logicalDatabaseBytes(
                measured.pageSize(), measured.pageCount(), measured.freeListPages());
        long output = Math.max(MIN_OUTPUT, Math.max(measured.mainBytes(),
                Math.max(logical, liveAllocated)));
        long walGrowth = Math.max(MIN_WAL_GROWTH, measured.walBytes());
        long nativeWork = Math.max(MIN_NATIVE_WORK, output / 4);
        long verification = Math.max(MIN_VERIFICATION, output / 16);
        long safety = Math.max(MigrationStoragePolicy.mibToBytes(evidence.safetyMarginMiB()),
                Math.max(MIN_VERIFICATION, output / 8));
        long requiredData = add(output, walGrowth, nativeWork, verification, safety);
        long requiredTemp = add(nativeWork, safety);
        List<String> reasons = new ArrayList<>();
        boolean refusal = false;
        boolean unknown = false;
        if (!measured.safePaths()) {
            reasons.add("Data/candidate directory or SQLite sidecar is unsafe");
            refusal = true;
        }
        if (measured.existingCandidateFiles() > 0) {
            reasons.add("Existing candidate/reservation detected; review it manually before another run");
            refusal = true;
        }
        if (measured.filesystemFreeBytes() != null) {
            if (measured.filesystemFreeBytes() < requiredData) {
                reasons.add("Data filesystem free space below estimated required headroom");
                refusal = true;
            }
        } else {
            reasons.add("Data filesystem free space is unknown");
            unknown = true;
        }
        if (evidence.accountFreeBytes() == null) {
            reasons.add("Hosting account/container quota is unknown");
            unknown = true;
        } else if (evidence.accountFreeBytes() < requiredData) {
            reasons.add("Operator-confirmed account free space is insufficient");
            refusal = true;
        }
        if (evidence.sqliteNativeTempFreeBytes() == null) {
            reasons.add("Actual SQLite native temp filesystem free space is unknown");
            unknown = true;
        } else if (evidence.sqliteNativeTempFreeBytes() < requiredTemp) {
            reasons.add("Operator-confirmed SQLite native temp free space is insufficient");
            refusal = true;
        }
        Decision decision = refusal ? Decision.REFUSED
                : unknown ? (evidence.acknowledgeUnknown()
                    ? Decision.READY_WITH_ACKNOWLEDGEMENT : Decision.ACKNOWLEDGEMENT_REQUIRED)
                : Decision.READY;
        if (unknown && evidence.acknowledgeUnknown() && !refusal) {
            reasons.add("Operator explicitly acknowledges unknown headroom; SQLITE_FULL can still occur");
        }
        return new Report(measured, evidence, output, walGrowth, nativeWork,
                verification, safety, requiredData, requiredTemp, decision, reasons);
    }

    /**
     * Future #165 worker calls this only after a fresh approved preflight.
     * The single CREATE_NEW marker is cross-process exclusive. A stale marker
     * or ANY unverified candidate blocks another compaction; nothing is
     * automatically discarded. Reserved filenames are internal UUIDs, not
     * supplied by commands, users, database content, or external paths.
     */
    Reservation reserve(Report approved) throws IOException {
        Objects.requireNonNull(approved);
        if (closed) throw new IOException("Compaction preflight has stopped");
        if (!approved.mayReserve()
                || !databaseFile.equals(approved.snapshot().database())
                || Instant.now().toEpochMilli() - approved.snapshot().measuredAt().toEpochMilli()
                        > MAX_RESERVATION_AGE_MILLIS
                || approved.snapshot().measuredAt().isAfter(Instant.now())) {
            throw new IOException("A fresh approved preflight is required before reserving a candidate");
        }
        if (!isDirectory(dataDirectory) || !isRegular(databaseFile)
                || Files.isSymbolicLink(dataDirectory)) {
            throw new IOException("Unsafe FragGuard active database or data directory");
        }
        if (!Files.exists(candidatesDirectory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(candidatesDirectory);
        }
        if (!isDirectory(candidatesDirectory)) {
            throw new IOException("Compaction candidate directory is unsafe");
        }
        // Never use a candidate directory already containing unknown or stale
        // files. A failed/interrupted prior candidate must be reviewed.
        try (DirectoryStream<Path> files = Files.newDirectoryStream(candidatesDirectory)) {
            if (files.iterator().hasNext()) {
                throw new IOException("Unverified compaction candidate or reservation already exists");
            }
        }
        Path marker = candidatesDirectory.resolve(RESERVATION_FILE);
        Path candidate = candidatesDirectory.resolve("fragguard-"
                + UUID.randomUUID() + ".candidate.db");
        // The atomic fixed-name marker rejects a simultaneous reservation in
        // this process or another process using the same server data folder.
        Files.writeString(marker, candidate.getFileName().toString() + "\n",
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new Reservation(marker, candidate);
    }

    final class Reservation implements AutoCloseable {
        private final Path marker;
        private final Path candidate;
        private boolean closed;

        private Reservation(Path marker, Path candidate) {
            this.marker = marker;
            this.candidate = candidate;
        }

        Path candidate() {
            return candidate;
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            // An incomplete or completed candidate is NEVER automatically
            // removed. Keep its marker too so another run cannot silently
            // collide with, or overwrite, an unverified database copy.
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return;
            if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    && Files.readString(marker).equals(candidate.getFileName() + "\n")) {
                Files.delete(marker);
            }
        }
    }

    static String sqliteFullMessage(SQLException error) {
        if (MigrationStoragePolicy.isSqliteFull(error)) {
            return "SQLite reported SQLITE_FULL. The database, WAL/SHM and every existing "
                    + "candidate/backup must be preserved. The failing filesystem or "
                    + "account quota may be unknown; never treat preflight as a guarantee.";
        }
        return "SQLite operation failed. Preserve the original, WAL/SHM and any partial "
                + "candidate for administrator investigation; do not auto-promote it.";
    }

    private static boolean isDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean isRegular(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static long add(long... values) {
        long sum = 0;
        for (long value : values) {
            if (value > Long.MAX_VALUE - sum) return Long.MAX_VALUE;
            sum += value;
        }
        return sum;
    }

    private static long saturatingMultiply(long a, long b) {
        return a > 0 && b > Long.MAX_VALUE / a ? Long.MAX_VALUE : a * b;
    }

    @Override
    public void close() {
        closed = true;
        worker.shutdownNow();
    }
}
