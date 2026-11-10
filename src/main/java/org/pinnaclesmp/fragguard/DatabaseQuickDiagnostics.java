package org.pinnaclesmp.fragguard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Non-destructive SQLite diagnostics on a separate read-only connection.
 *
 * No operation runs on the Paper main thread or FragGuard's database writer.
 * Exact counts and quick_check are deliberately opt-in: both may scan large
 * databases. Only one request can run at a time and it has a time limit.
 */
final class DatabaseQuickDiagnostics implements AutoCloseable {
    enum Mode { OVERVIEW, COUNTS, QUICK_CHECK }

    record Report(
            Mode mode,
            Instant measuredAt,
            Path databasePath,
            Long mainBytes,
            Long walBytes,
            Long shmBytes,
            Long hostUsableBytes,
            long pageCount,
            long pageSize,
            long freelistCount,
            long recyclableBytes,
            int schemaVersion,
            Long historyRows,
            Long rollbackJobs,
            String quickCheck,
            DatabaseHealth health,
            int retentionDays,
            int rollbackRetentionDays) {

        Report {
            Objects.requireNonNull(mode);
            Objects.requireNonNull(measuredAt);
            Objects.requireNonNull(databasePath);
            Objects.requireNonNull(health);
            if (pageCount < 0 || pageSize <= 0 || freelistCount < 0
                    || freelistCount > pageCount || recyclableBytes < 0) {
                throw new IllegalArgumentException("Invalid SQLite page statistics");
            }
        }

        List<String> lines() {
            List<String> out = new ArrayList<>();
            out.add("Database: " + databasePath.toAbsolutePath().normalize());
            out.add("Measured: " + measuredAt + " (point-in-time; database may keep changing)");
            out.add("SQLite schema: v" + schemaVersion
                    + " (FragGuard expects v" + Database.SCHEMA_VERSION + ")");
            out.add("Files: main " + size(mainBytes) + ", WAL " + size(walBytes)
                    + ", SHM " + size(shmBytes));
            out.add("Pages: " + pageCount + " x " + size(pageSize)
                    + " | Free-list pages: " + freelistCount);
            out.add("Reusable DB pages: about " + size(recyclableBytes)
                    + " (NOT a guaranteed VACUUM INTO saving)");
            out.add("Filesystem usable space: " + size(hostUsableBytes)
                    + " (hosting quota and native SQLite temp space unknown)");
            out.add("History rows: " + (historyRows == null
                    ? "not scanned; use /fg diagnostics counts" : historyRows));
            out.add("Rollback jobs: " + (rollbackJobs == null
                    ? "not scanned; use /fg diagnostics counts" : rollbackJobs));
            out.add("Quick integrity check: " + (quickCheck == null
                    ? "not run; use /fg diagnostics check" : quickCheck));
            out.add("Retention: history " + retentionDays + "d | rollback jobs "
                    + rollbackRetentionDays + "d");
            out.add("Storage: " + (health.healthy() ? "OK" : "DEGRADED")
                    + " | Pending writes " + health.queuedWrites()
                    + "/" + health.writeCapacity()
                    + " | Operations " + health.queuedOperations()
                    + "/" + health.operationCapacity()
                    + " | Dropped " + health.droppedWrites());
            if (!health.lastError().isBlank()) {
                out.add("Storage error: " + health.lastError().substring(
                        0, Math.min(160, health.lastError().length())));
            }
            return List.copyOf(out);
        }

        private static String size(Long value) {
            return value == null ? "unknown" : MigrationStoragePolicy.formatBytes(value);
        }
    }

    private final Path databaseFile;
    private final ExecutorService worker;
    private final ScheduledExecutorService watchdog;
    private final AtomicReference<Request> active = new AtomicReference<>();
    private volatile boolean closed;

    DatabaseQuickDiagnostics(Path databaseFile) {
        this.databaseFile = Objects.requireNonNull(databaseFile).toAbsolutePath().normalize();
        this.worker = Executors.newSingleThreadExecutor(daemon("FragGuard-Quick-Diagnostics"));
        this.watchdog = Executors.newSingleThreadScheduledExecutor(
                daemon("FragGuard-Quick-Diagnostics-Timeout"));
    }

    CompletableFuture<Report> run(Mode mode, DatabaseHealth health, int retentionDays,
                                   int rollbackRetentionDays, int timeoutSeconds) {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(health);
        CompletableFuture<Report> response = new CompletableFuture<>();
        Request request = new Request(response);
        if (closed) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("FragGuard diagnostics are stopped"));
        }
        if (!active.compareAndSet(null, request)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Another database diagnostic is already running"));
        }
        if (closed) {
            active.compareAndSet(request, null);
            return CompletableFuture.failedFuture(
                    new IllegalStateException("FragGuard diagnostics are stopped"));
        }
        try {
            int seconds = Math.max(1, timeoutSeconds);
            request.workerTask = worker.submit(() -> {
                try {
                    Report report = inspect(mode, health,
                            Math.max(1, retentionDays), Math.max(1, rollbackRetentionDays), request);
                    response.complete(report);
                } catch (Exception exception) {
                    response.completeExceptionally(exception);
                } finally {
                    active.compareAndSet(request, null);
                    ScheduledFuture<?> alarm = request.alarm;
                    if (alarm != null) alarm.cancel(false);
                }
            });
            request.alarm = watchdog.schedule(() -> {
                if (!response.isDone()) {
                    response.completeExceptionally(new TimeoutException(
                            "Database diagnostics exceeded " + seconds
                                    + "s. Try again or use the lightweight overview."));
                    request.cancel();
                }
            }, seconds, TimeUnit.SECONDS);
            response.whenComplete((value, failure) -> {
                if (response.isCancelled()) request.cancel();
            });
        } catch (RuntimeException exception) {
            response.completeExceptionally(exception);
            request.cancel();
            active.compareAndSet(request, null);
        }
        return response;
    }

    private Report inspect(Mode mode, DatabaseHealth health, int retentionDays,
                           int rollbackRetentionDays, Request request)
            throws SQLException, IOException {
        if (!Files.isRegularFile(databaseFile)) {
            throw new IOException("Active FragGuard database is missing or not a regular file");
        }
        // SQLite URI read-only mode prevents accidental DB creation and writes.
        // query_only is an additional per-connection write guard.
        String url = "jdbc:sqlite:" + databaseFile.toUri() + "?mode=ro";
        try (Connection connection = DriverManager.getConnection(url)) {
            try (Statement setup = connection.createStatement()) {
                setup.execute("PRAGMA query_only = ON");
            }
            long pageCount = pragma(connection, "page_count", request);
            long pageSize = pragma(connection, "page_size", request);
            long freelistCount = pragma(connection, "freelist_count", request);
            int schemaVersion = Math.toIntExact(pragma(connection, "user_version", request));
            Long historyRows = null;
            Long rollbackJobs = null;
            String quickCheck = null;
            if (mode == Mode.COUNTS) {
                historyRows = scalar(connection, "SELECT COUNT(*) FROM block_changes", request);
                rollbackJobs = scalar(connection, "SELECT COUNT(*) FROM rollback_jobs", request);
            } else if (mode == Mode.QUICK_CHECK) {
                try (Statement statement = connection.createStatement()) {
                    request.statement = statement;
                    try (ResultSet rows = statement.executeQuery("PRAGMA quick_check(1)")) {
                        quickCheck = rows.next() ? rows.getString(1) : "no result";
                    } finally {
                        request.statement = null;
                    }
                }
            }
            Long usable;
            try {
                usable = Files.getFileStore(databaseFile).getUsableSpace();
            } catch (IOException | SecurityException exception) {
                usable = null; // Do not mistake unavailable quota signal for zero bytes.
            }
            return new Report(mode, Instant.now(), databaseFile, size(databaseFile),
                    size(databaseFile.resolveSibling(databaseFile.getFileName() + "-wal")),
                    size(databaseFile.resolveSibling(databaseFile.getFileName() + "-shm")),
                    usable, pageCount, pageSize, freelistCount,
                    safeMultiply(pageSize, freelistCount),
                    schemaVersion, historyRows, rollbackJobs, quickCheck,
                    health, retentionDays, rollbackRetentionDays);
        }
    }

    private static long pragma(Connection connection, String name, Request request)
            throws SQLException {
        // All PRAGMAs here are read-only, fixed names (no user-supplied SQL).
        return scalar(connection, "PRAGMA " + name, request);
    }

    private static long scalar(Connection connection, String sql, Request request) throws SQLException {
        if (Thread.currentThread().isInterrupted()) {
            throw new SQLException("Database diagnostic cancelled");
        }
        try (Statement statement = connection.createStatement()) {
            request.statement = statement;
            try (ResultSet rows = statement.executeQuery(sql)) {
                if (!rows.next()) throw new SQLException("No diagnostic result for " + sql);
                return rows.getLong(1);
            } finally {
                request.statement = null;
            }
        }
    }

    private static Long size(Path file) {
        try {
            if (!Files.exists(file)) return 0L;
            return Files.isRegularFile(file) ? Files.size(file) : null;
        } catch (IOException | SecurityException exception) {
            return null;
        }
    }

    private static long safeMultiply(long a, long b) {
        return b != 0 && a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b;
    }

    private static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void close() {
        closed = true;
        Request request = active.get();
        if (request != null) {
            request.response.completeExceptionally(
                    new IllegalStateException("Diagnostics cancelled by server shutdown"));
            request.cancel();
        }
        watchdog.shutdownNow();
        worker.shutdownNow();
        try {
            worker.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Request {
        private final CompletableFuture<Report> response;
        private volatile Statement statement;
        private volatile Future<?> workerTask;
        private volatile ScheduledFuture<?> alarm;

        private Request(CompletableFuture<Report> response) {
            this.response = response;
        }

        void cancel() {
            Statement inFlight = statement;
            if (inFlight != null) {
                try {
                    inFlight.cancel();
                } catch (SQLException ignored) {
                    // Best effort; interrupt worker as well.
                }
            }
            Future<?> task = workerTask;
            if (task != null) task.cancel(true);
        }
    }
}
