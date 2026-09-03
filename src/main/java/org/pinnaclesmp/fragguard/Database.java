package org.pinnaclesmp.fragguard;

import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

final class Database {
    static final int SCHEMA_VERSION = 3;
    static final long DEFAULT_MAX_ROLLBACK_SNAPSHOT_BYTES = 64L * 1024L * 1024L;
    private static final long WARNING_INTERVAL_MILLIS = 10_000L;
    private static final int COALESCE_CONFLICT_QUERY_BATCH_SIZE = 100;
    private static final String AREA_FILTER = """
            rollback_pending = 0
            AND world_uuid IN (?, ?)
            AND (world_uuid = ? OR world = ?)
            AND happened_at %s ?
            AND chunk_x BETWEEN ? AND ?
            AND chunk_z BETWEEN ? AND ?
            AND x BETWEEN ? AND ?
            AND z BETWEEN ? AND ?
            AND ((CAST(x AS INTEGER) - ?) * (CAST(x AS INTEGER) - ?)
               + (CAST(z AS INTEGER) - ?) * (CAST(z AS INTEGER) - ?)) <= ?
            """;

    private final JavaPlugin plugin;
    private final File databaseFile;
    private final String jdbcUrl;
    private final String coalesceSession = UUID.randomUUID().toString();
    private final ArrayBlockingQueue<PendingBlockChange> writeQueue;
    private final ArrayBlockingQueue<DatabaseOperation<?>> operationQueue;
    private final Map<CoalesceKey, PendingBlockChange> coalescedChanges = new HashMap<>();
    private final AtomicLong acceptedWriteSequence = new AtomicLong();
    private final AtomicLong completedWrites = new AtomicLong();
    private final AtomicLong droppedWrites = new AtomicLong();
    private final AtomicLong coalescedWrites = new AtomicLong();
    private final int writeCapacity;
    private final int operationCapacity;
    private final int batchSize;
    private final int pressureFlushThreshold;
    private final int queryTimeoutSeconds;

    private volatile Thread worker;
    private volatile boolean running;
    private volatile boolean healthy = true;
    private volatile boolean walCheckpointCompleted;
    private volatile boolean workerStopped = true;
    private volatile int activeWriteBatchSize;
    private volatile String lastError = "";
    private volatile long lastWarningAt;
    private volatile Statement activeStatement;
    private volatile CompletableFuture<?> activeQuery;
    private Connection connection;

    Database(JavaPlugin plugin) {
        this.plugin = plugin;
        this.databaseFile = new File(plugin.getDataFolder(), "fragguard.db");
        this.jdbcUrl = "jdbc:sqlite:" + databaseFile.getAbsolutePath();
        this.writeCapacity = Math.max(64, plugin.getConfig().getInt("database-write-queue-capacity", 20_000));
        this.operationCapacity = Math.max(8, plugin.getConfig().getInt("database-operation-queue-capacity", 256));
        this.batchSize = Math.min(writeCapacity,
                Math.max(1, plugin.getConfig().getInt("database-write-batch-size", 500)));
        this.pressureFlushThreshold = Math.min(batchSize, Math.max(1, (writeCapacity * 3) / 4));
        this.queryTimeoutSeconds = Math.max(1, plugin.getConfig().getInt("database-query-timeout-seconds", 15));
        this.writeQueue = new ArrayBlockingQueue<>(writeCapacity);
        this.operationQueue = new ArrayBlockingQueue<>(operationCapacity);
    }

    void init() throws SQLException, ClassNotFoundException {
        Class.forName("org.sqlite.JDBC");
        if (!plugin.getDataFolder().isDirectory() && !plugin.getDataFolder().mkdirs()) {
            throw new SQLException("Could not create FragGuard's data directory");
        }

        List<WorldIdentity> loadedWorlds = plugin.getServer().getWorlds().stream()
                .map(world -> new WorldIdentity(world.getUID().toString(), world.getName()))
                .toList();
        CompletableFuture<Void> started = new CompletableFuture<>();
        healthy = true;
        lastError = "";
        walCheckpointCompleted = false;
        workerStopped = false;
        activeWriteBatchSize = 0;
        running = true;
        worker = new Thread(() -> runWorker(started, loadedWorlds), "FragGuard-Database");
        worker.setDaemon(true);
        worker.start();

        try {
            started.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            running = false;
            throw new SQLException("Interrupted while starting FragGuard's database", exception);
        } catch (ExecutionException | TimeoutException exception) {
            running = false;
            Throwable cause = exception instanceof ExecutionException ? exception.getCause() : exception;
            throw new SQLException("Could not start FragGuard's database worker", cause);
        }
    }

    DatabaseShutdownSnapshot shutdown() {
        DatabaseShutdownSnapshot snapshot;
        synchronized (coalescedChanges) {
            running = false;
            snapshot = new DatabaseShutdownSnapshot(
                    new DatabaseHealth(writeQueue.size(), writeCapacity, operationQueue.size(), operationCapacity,
                            droppedWrites.get(), coalescedWrites.get(), healthy, lastError),
                    completedWrites.get(), activeWriteBatchSize);
        }

        Thread databaseWorker = worker;
        if (databaseWorker == null) {
            return snapshot;
        }
        databaseWorker.interrupt();
        try {
            long timeoutSeconds = Math.max(1, plugin.getConfig().getInt("database-shutdown-timeout-seconds", 15));
            databaseWorker.join(TimeUnit.SECONDS.toMillis(timeoutSeconds));
            if (databaseWorker.isAlive()) {
                cancelActiveStatement();
                databaseWorker.interrupt();
                plugin.getLogger().warning("FragGuard database worker did not finish before the shutdown timeout; "
                        + "waiting for cancellation.");

                long cancelTimeoutSeconds = Math.max(1,
                        plugin.getConfig().getInt("database-shutdown-cancel-timeout-seconds", 5));
                databaseWorker.join(TimeUnit.SECONDS.toMillis(cancelTimeoutSeconds));
                if (databaseWorker.isAlive()) {
                    int abandonedWrites = abandonQueuedWrites();
                    if (abandonedWrites > 0) {
                        droppedWrites.addAndGet(abandonedWrites);
                    }

                    IllegalStateException shutdownFailure = new IllegalStateException(
                            "FragGuard database worker did not stop after cancellation.");
                    DatabaseOperation<?> pending;
                    while ((pending = operationQueue.poll()) != null) {
                        pending.future.completeExceptionally(shutdownFailure);
                    }

                    healthy = false;
                    int inFlightWrites = activeWriteBatchSize;
                    String detail = "Database worker did not stop after cancellation; "
                            + abandonedWrites + " queued write"
                            + (abandonedWrites == 1 ? " was" : "s were") + " abandoned";
                    if (inFlightWrites > 0) {
                        detail += " and " + inFlightWrites + " in-flight write"
                                + (inFlightWrites == 1 ? " remains" : "s remain")
                                + " unconfirmed";
                    }
                    lastError = detail + ".";
                    plugin.getLogger().severe("FragGuard database shutdown could not confirm durability for all "
                            + "accepted work: " + lastError);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        return snapshot;
    }

    long completedWrites() {
        return completedWrites.get();
    }

    int inFlightWrites() {
        return activeWriteBatchSize;
    }

    boolean workerStopped() {
        Thread databaseWorker = worker;
        return workerStopped && (databaseWorker == null || !databaseWorker.isAlive());
    }

    boolean walCheckpointCompleted() {
        return walCheckpointCompleted;
    }

    void insertAsync(BlockChange change) {
        if (!running) {
            warnStorage("Cannot record block changes because FragGuard's database is not running.", null);
            droppedWrites.incrementAndGet();
            return;
        }

        String worldUuid = resolveWorldUuid(change.worldName());
        long enqueuedTick = plugin.getServer().getCurrentTick();
        long serverTick = change.serverTick() == BlockChange.UNSPECIFIED_SERVER_TICK
                ? enqueuedTick
                : change.serverTick();
        CoalesceKey key = new CoalesceKey(worldUuid, change.x(), change.y(), change.z(), serverTick);
        synchronized (coalescedChanges) {
            if (!running) {
                warnStorage("Cannot record block changes because FragGuard's database is not running.", null);
                droppedWrites.incrementAndGet();
                return;
            }

            PendingBlockChange existing = coalescedChanges.get(key);
            if (existing != null) {
                existing.change = merge(existing.change, change, serverTick);
                coalescedWrites.incrementAndGet();
                return;
            }

            long sequence = acceptedWriteSequence.get() + 1;
            PendingBlockChange pending = new PendingBlockChange(sequence, key, enqueuedTick, worldUuid, change);
            if (!writeQueue.offer(pending)) {
                droppedWrites.incrementAndGet();
                warnStorage("FragGuard database write queue is full (" + writeCapacity
                        + "); block changes are being dropped. Check storage health or increase database-write-queue-capacity.", null);
                return;
            }
            acceptedWriteSequence.incrementAndGet();
            coalescedChanges.put(key, pending);
        }

        int depth = writeQueue.size();
        if (depth >= Math.max(1, (writeCapacity * 3) / 4)) {
            warnStorage("FragGuard database write queue is " + depth + "/" + writeCapacity
                    + "; storage may not be keeping up with gameplay.", null);
        }
    }

    CompletableFuture<List<Long>> insertRequiredAsync(List<BlockChange> changes) {
        List<RequiredBlockChange> requiredChanges = changes.stream()
                .filter(change -> !sameState(change.beforeData(), change.beforeEntityData(),
                        change.afterData(), change.afterEntityData()))
                .map(change -> new RequiredBlockChange(resolveWorldUuid(change.worldName()), change))
                .toList();
        if (requiredChanges.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        return submit(databaseConnection -> inTransaction(databaseConnection, () -> {
            List<Long> insertedIds = new ArrayList<>(requiredChanges.size());
            try (PreparedStatement statement = databaseConnection.prepareStatement("""
                    INSERT INTO block_changes
                    (happened_at, actor_uuid, actor_name, world, x, y, z, action,
                     before_data, after_data, world_uuid, chunk_x, chunk_z,
                     before_entity_data, after_entity_data)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                for (RequiredBlockChange required : requiredChanges) {
                    BlockChange change = required.change();
                    statement.setLong(1, change.happenedAt());
                    statement.setString(2, change.actorUuid());
                    statement.setString(3, change.actorName());
                    statement.setString(4, change.worldName());
                    statement.setInt(5, change.x());
                    statement.setInt(6, change.y());
                    statement.setInt(7, change.z());
                    statement.setString(8, change.action().storageId());
                    statement.setString(9, change.beforeData());
                    statement.setString(10, change.afterData());
                    statement.setString(11, required.worldUuid());
                    statement.setInt(12, change.x() >> 4);
                    statement.setInt(13, change.z() >> 4);
                    statement.setBytes(14, change.beforeEntityData());
                    statement.setBytes(15, change.afterEntityData());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("SQLite did not return a required block-change ID");
                        }
                        insertedIds.add(keys.getLong(1));
                    }
                }
            }
            return List.copyOf(insertedIds);
        }), false);
    }

    CompletableFuture<List<Long>> insertPendingRollbackAuditsAsync(
            long jobId,
            boolean undo,
            List<RollbackPendingAudit> audits
    ) {
        List<ResolvedRollbackPendingAudit> savedAudits = audits.stream()
                .map(pending -> new ResolvedRollbackPendingAudit(
                        pending.sequence(), resolveWorldUuid(pending.change().worldName()), pending.change()))
                .toList();
        if (savedAudits.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        return submit(databaseConnection -> inTransaction(databaseConnection, () -> {
            List<Long> insertedIds = new ArrayList<>(savedAudits.size());
            try (PreparedStatement deletePrevious = databaseConnection.prepareStatement("""
                         DELETE FROM block_changes
                         WHERE id = (SELECT pending_audit_id FROM rollback_job_changes
                                     WHERE job_id = ? AND sequence = ?)
                         """);
                 PreparedStatement insert = databaseConnection.prepareStatement("""
                         INSERT INTO block_changes
                         (happened_at, actor_uuid, actor_name, world, x, y, z, action,
                          before_data, after_data, world_uuid, chunk_x, chunk_z,
                          before_entity_data, after_entity_data, rollback_pending)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                         """, Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement link = databaseConnection.prepareStatement("""
                         UPDATE rollback_job_changes
                         SET pending_audit_id = ?, pending_audit_undo = ?
                         WHERE job_id = ? AND sequence = ?
                           AND %s
                         """.formatted(undo ? "undone = 0" : "processed = 0"))) {
                for (ResolvedRollbackPendingAudit pending : savedAudits) {
                    BlockChange change = pending.change();
                    if (sameState(change.beforeData(), change.beforeEntityData(),
                            change.afterData(), change.afterEntityData())) {
                        throw new IllegalArgumentException("Pending rollback audits must describe a state change.");
                    }

                    deletePrevious.setLong(1, jobId);
                    deletePrevious.setInt(2, pending.sequence());
                    deletePrevious.executeUpdate();

                    insert.setLong(1, change.happenedAt());
                    insert.setString(2, change.actorUuid());
                    insert.setString(3, change.actorName());
                    insert.setString(4, change.worldName());
                    insert.setInt(5, change.x());
                    insert.setInt(6, change.y());
                    insert.setInt(7, change.z());
                    insert.setString(8, change.action().storageId());
                    insert.setString(9, change.beforeData());
                    insert.setString(10, change.afterData());
                    insert.setString(11, pending.worldUuid());
                    insert.setInt(12, change.x() >> 4);
                    insert.setInt(13, change.z() >> 4);
                    insert.setBytes(14, change.beforeEntityData());
                    insert.setBytes(15, change.afterEntityData());
                    insert.executeUpdate();

                    long auditId;
                    try (ResultSet keys = insert.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("SQLite did not return a pending rollback audit ID");
                        }
                        auditId = keys.getLong(1);
                    }

                    link.setLong(1, auditId);
                    link.setInt(2, undo ? 1 : 0);
                    link.setLong(3, jobId);
                    link.setInt(4, pending.sequence());
                    if (link.executeUpdate() != 1) {
                        throw new SQLException("Could not link pending rollback audit " + auditId
                                + " to job #" + jobId + " change " + pending.sequence() + ".");
                    }
                    insertedIds.add(auditId);
                }
            }
            return List.copyOf(insertedIds);
        }), false);
    }

    CompletableFuture<Void> deleteRequiredAsync(List<Long> ids) {
        List<Long> savedIds = List.copyOf(ids);
        if (savedIds.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return submit(databaseConnection -> inTransaction(databaseConnection, () -> {
            try (PreparedStatement clearLinks = databaseConnection.prepareStatement("""
                         UPDATE rollback_job_changes
                         SET pending_audit_id = NULL, pending_audit_undo = 0
                         WHERE pending_audit_id = ?
                         """);
                 PreparedStatement statement = databaseConnection.prepareStatement(
                         "DELETE FROM block_changes WHERE id = ?")) {
                for (long id : savedIds) {
                    clearLinks.setLong(1, id);
                    clearLinks.addBatch();
                    statement.setLong(1, id);
                    statement.addBatch();
                }
                clearLinks.executeBatch();
                statement.executeBatch();
            }
            return null;
        }), false);
    }

    DatabaseHealth health() {
        return new DatabaseHealth(writeQueue.size(), writeCapacity, operationQueue.size(), operationCapacity,
                droppedWrites.get(), coalescedWrites.get(), healthy, lastError);
    }

    CompletableFuture<Integer> cleanupOldRecordsAsync(int retentionDays) {
        return submit(databaseConnection -> {
            long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays);
            try (PreparedStatement statement = databaseConnection.prepareStatement(
                    "DELETE FROM block_changes WHERE happened_at < ? AND rollback_pending = 0")) {
                statement.setLong(1, cutoff);
                return statement.executeUpdate();
            }
        }, false);
    }

    CompletableFuture<LookupPage> lookupAsync(String worldName, int centerX, int centerZ, int radius,
                                              int page, int pageSize, int retentionDays) {
        String worldUuid = resolveWorldUuid(worldName);
        return submit(databaseConnection -> {
            long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays);
            int total = countLookup(databaseConnection, worldUuid, worldName, centerX, centerZ, radius, cutoff);
            int totalPages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
            int safePage = Math.max(1, Math.min(page, totalPages));
            List<LookupRow> rows = selectLookup(databaseConnection, worldUuid, worldName, centerX, centerZ,
                    radius, cutoff, safePage, pageSize);
            return new LookupPage(rows, safePage, pageSize, total);
        }, true);
    }

    CompletableFuture<List<RollbackTarget>> rollbackTargetsAsync(String worldName, int centerX, int centerZ,
                                                                   int radius, long targetTimestamp,
                                                                   long snapshotTimestamp, int maxBlocks) {
        return rollbackTargetsAsync(worldName, centerX, centerZ, radius, targetTimestamp,
                snapshotTimestamp, maxBlocks, DEFAULT_MAX_ROLLBACK_SNAPSHOT_BYTES);
    }

    CompletableFuture<List<RollbackTarget>> rollbackTargetsAsync(String worldName, int centerX, int centerZ,
                                                                   int radius, long targetTimestamp,
                                                                   long snapshotTimestamp, int maxBlocks,
                                                                   long maxSnapshotBytes) {
        String worldUuid = resolveWorldUuid(worldName);
        return submit(databaseConnection -> selectRollbackTargets(databaseConnection, worldUuid, worldName,
                centerX, centerZ, radius, targetTimestamp, snapshotTimestamp,
                Math.max(1, maxBlocks), Math.max(1L, maxSnapshotBytes)), true);
    }

    CompletableFuture<RollbackJob> createRollbackJobAsync(String actorUuid, String actorName, String worldName,
                                                           int centerX, int centerZ, int radius,
                                                           long targetTimestamp, long snapshotTimestamp,
                                                           boolean force, List<RollbackTarget> targets) {
        String worldUuid = resolveWorldUuid(worldName);
        List<RollbackTarget> savedTargets = List.copyOf(targets);
        return submit(databaseConnection -> inTransaction(databaseConnection, () -> {
            rejectOverlappingJob(databaseConnection, worldUuid, worldName, centerX, centerZ, radius, -1L);
            long now = System.currentTimeMillis();
            long jobId;
            try (PreparedStatement statement = databaseConnection.prepareStatement("""
                    INSERT INTO rollback_jobs
                    (created_at, updated_at, actor_uuid, actor_name, world_uuid, world,
                     center_x, center_z, radius, target_timestamp, snapshot_timestamp, force,
                     status, total_blocks)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, now);
                statement.setLong(2, now);
                statement.setString(3, actorUuid);
                statement.setString(4, actorName);
                statement.setString(5, worldUuid);
                statement.setString(6, worldName);
                statement.setInt(7, centerX);
                statement.setInt(8, centerZ);
                statement.setInt(9, radius);
                statement.setLong(10, targetTimestamp);
                statement.setLong(11, snapshotTimestamp);
                statement.setInt(12, force ? 1 : 0);
                statement.setInt(13, savedTargets.size());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("SQLite did not return a rollback job ID");
                    }
                    jobId = keys.getLong(1);
                }
            }

            try (PreparedStatement statement = databaseConnection.prepareStatement("""
                    INSERT INTO rollback_job_changes
                    (job_id, sequence, world, x, y, z, expected_data, target_data,
                     expected_entity_data, target_entity_data)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (int index = 0; index < savedTargets.size(); index++) {
                    RollbackTarget target = savedTargets.get(index);
                    statement.setLong(1, jobId);
                    statement.setInt(2, index);
                    statement.setString(3, target.worldName());
                    statement.setInt(4, target.x());
                    statement.setInt(5, target.y());
                    statement.setInt(6, target.z());
                    statement.setString(7, target.expectedData());
                    statement.setString(8, target.blockData());
                    statement.setBytes(9, target.expectedEntityData());
                    statement.setBytes(10, target.targetEntityData());
                    statement.addBatch();
                    if ((index + 1) % batchSize == 0) {
                        statement.executeBatch();
                    }
                }
                statement.executeBatch();
            }
            return loadJob(databaseConnection, jobId);
        }), false);
    }

    CompletableFuture<List<RollbackJob>> loadResumableJobsAsync() {
        return submit(databaseConnection -> {
            List<RollbackJob> jobs = new ArrayList<>();
            try (PreparedStatement statement = databaseConnection.prepareStatement(
                    "SELECT * FROM rollback_jobs WHERE status IN ('RUNNING', 'UNDOING') ORDER BY id")) {
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        jobs.add(readJob(rows));
                    }
                }
            }
            return jobs;
        }, false);
    }

    CompletableFuture<List<RollbackJobChange>> loadRollbackChangesAsync(long jobId, boolean undo) {
        return loadRollbackChangesAsync(jobId, undo, DEFAULT_MAX_ROLLBACK_SNAPSHOT_BYTES);
    }

    CompletableFuture<List<RollbackJobChange>> loadRollbackChangesAsync(long jobId, boolean undo,
                                                                         long maxSnapshotBytes) {
        long maximumSnapshotBytes = Math.max(1L, maxSnapshotBytes);
        return submit(databaseConnection -> {
            String condition = undo
                    ? "before_data IS NOT NULL AND conflicted = 0 AND undone = 0"
                    : "processed = 0";
            String order = undo ? "DESC" : "ASC";
            String sql = """
                    SELECT *,
                           COALESCE(length(before_entity_data), 0) AS before_entity_bytes,
                           COALESCE(length(target_entity_data), 0) AS target_entity_bytes,
                           COALESCE(length(expected_entity_data), 0) AS expected_entity_bytes,
                           COALESCE(length(applied_entity_data), 0) AS applied_entity_bytes
                    FROM rollback_job_changes
                    WHERE job_id = ? AND %s
                    ORDER BY sequence %s
                    """.formatted(condition, order);
            List<RollbackJobChange> changes = new ArrayList<>();
            long snapshotBytes = 0L;
            try (PreparedStatement statement = databaseConnection.prepareStatement(sql)) {
                statement.setLong(1, jobId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        long beforeBytes = rows.getLong("before_entity_bytes");
                        long targetBytes = rows.getLong("target_entity_bytes");
                        long expectedBytes = rows.getLong("expected_entity_bytes");
                        long appliedBytes = rows.getLong("applied_entity_bytes");
                        if (beforeBytes > maximumSnapshotBytes - snapshotBytes
                                || targetBytes > maximumSnapshotBytes - snapshotBytes - beforeBytes
                                || expectedBytes > maximumSnapshotBytes - snapshotBytes
                                        - beforeBytes - targetBytes
                                || appliedBytes > maximumSnapshotBytes - snapshotBytes
                                        - beforeBytes - targetBytes - expectedBytes) {
                            throw new RollbackJobSnapshotLimitExceededException(maximumSnapshotBytes, undo);
                        }
                        snapshotBytes += beforeBytes + targetBytes + expectedBytes + appliedBytes;
                        String beforeData = rows.getString("before_data");
                        String expectedData = rows.getString("expected_data");
                        if (expectedData == null) {
                            expectedData = beforeData;
                        }
                        String appliedData = rows.getString("applied_data");
                        byte[] appliedEntityData = rows.getBytes("applied_entity_data");
                        long pendingAuditId = rows.getLong("pending_audit_id");
                        Long nullablePendingAuditId = rows.wasNull() ? null : pendingAuditId;
                        changes.add(new RollbackJobChange(rows.getInt("sequence"), rows.getString("world"),
                                rows.getInt("x"), rows.getInt("y"), rows.getInt("z"),
                                beforeData, rows.getString("target_data"), expectedData,
                                rows.getBoolean("processed"), rows.getBoolean("applied"),
                                rows.getBoolean("conflicted"), rows.getBoolean("undone"),
                                rows.getBytes("before_entity_data"), rows.getBytes("target_entity_data"),
                                rows.getBytes("expected_entity_data"), appliedData, appliedEntityData,
                                nullablePendingAuditId, rows.getBoolean("pending_audit_undo")));
                    }
                }
            }
            return changes;
        }, false);
    }

    CompletableFuture<Void> prepareRollbackBatchAsync(long jobId, List<RollbackJobChange> changes) {
        List<RollbackJobChange> savedChanges = List.copyOf(changes);
        return submit(databaseConnection -> inTransaction(databaseConnection, () -> {
            try (PreparedStatement statement = databaseConnection.prepareStatement("""
                    UPDATE rollback_job_changes
                    SET before_entity_data = COALESCE(before_entity_data, ?),
                        before_data = COALESCE(before_data, ?)
                    WHERE job_id = ? AND sequence = ? AND processed = 0
                    """)) {
                for (RollbackJobChange change : savedChanges) {
                    statement.setBytes(1, change.beforeEntityData());
                    statement.setString(2, Objects.requireNonNull(change.beforeData(), "Missing original block data"));
                    statement.setLong(3, jobId);
                    statement.setInt(4, change.sequence());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            touchJob(databaseConnection, jobId);
            return null;
        }), false);
    }

    CompletableFuture<Void> replaceRollbackBeforeDataAsync(long jobId, List<RollbackJobChange> changes) {
        List<RollbackJobChange> savedChanges = List.copyOf(changes);
        return submit(databaseConnection -> inTransaction(databaseConnection, () -> {
            try (PreparedStatement statement = databaseConnection.prepareStatement("""
                    UPDATE rollback_job_changes
                    SET before_data = ?, before_entity_data = ?
                    WHERE job_id = ? AND sequence = ? AND processed = 0
                    """)) {
                for (RollbackJobChange change : savedChanges) {
                    statement.setString(1, Objects.requireNonNull(change.beforeData(), "Missing original block data"));
                    statement.setBytes(2, change.beforeEntityData());
                    statement.setLong(3, jobId);
                    statement.setInt(4, change.sequence());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            touchJob(databaseConnection, jobId);
            return null;
        }), false);
    }

    CompletableFuture<Void> markRollbackBatchAppliedAsync(long jobId, List<RollbackStepResult> results) {
        return markBatchAsync(jobId, results, false);
    }

    CompletableFuture<Void> markUndoBatchAppliedAsync(long jobId, List<RollbackStepResult> results) {
        return markBatchAsync(jobId, results, true);
    }

    CompletableFuture<RollbackJob> completeRollbackJobAsync(long jobId, boolean undo) {
        return submit(databaseConnection -> {
            String status = undo ? "UNDONE" : "COMPLETED";
            String lastError = null;
            if (undo) {
                int unresolved = countUnresolvedUndoChanges(databaseConnection, jobId);
                if (unresolved > 0) {
                    status = "FAILED";
                    lastError = "Undo left " + unresolved + " unresolved conflict"
                            + (unresolved == 1 ? "" : "s") + "; run /fg undo " + jobId + " to retry.";
                }
            }
            try (PreparedStatement statement = databaseConnection.prepareStatement(
                    "UPDATE rollback_jobs SET status = ?, updated_at = ?, last_error = ? WHERE id = ?")) {
                statement.setString(1, status);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, lastError);
                statement.setLong(4, jobId);
                statement.executeUpdate();
            }
            return loadJob(databaseConnection, jobId);
        }, false);
    }

    CompletableFuture<Void> failRollbackJobAsync(long jobId, String reason) {
        return submit(databaseConnection -> {
            try (PreparedStatement statement = databaseConnection.prepareStatement(
                    "UPDATE rollback_jobs SET status = 'FAILED', updated_at = ?, last_error = ? WHERE id = ?")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, reason);
                statement.setLong(3, jobId);
                statement.executeUpdate();
            }
            return null;
        }, false);
    }

    CompletableFuture<RollbackJob> beginUndoAsync(long jobId) {
        return submit(databaseConnection -> inTransaction(databaseConnection, () -> {
            RollbackJob job = loadJob(databaseConnection, jobId);
            if (!job.status().equals("COMPLETED") && !job.status().equals("FAILED")) {
                throw new IllegalStateException("Rollback job #" + jobId + " cannot be undone while it is " + job.status() + ".");
            }
            rejectOverlappingJob(databaseConnection, job.worldUuid(), job.worldName(), job.centerX(),
                    job.centerZ(), job.radius(), jobId);
            try (PreparedStatement statement = databaseConnection.prepareStatement(
                    "UPDATE rollback_jobs SET status = 'UNDOING', updated_at = ?, last_error = NULL WHERE id = ?")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setLong(2, jobId);
                statement.executeUpdate();
            }
            return loadJob(databaseConnection, jobId);
        }), false);
    }

    private void runWorker(CompletableFuture<Void> started, List<WorldIdentity> loadedWorlds) {
        try (Connection databaseConnection = DriverManager.getConnection(jdbcUrl)) {
            connection = databaseConnection;
            initializeSchema(databaseConnection, loadedWorlds);
            started.complete(null);

            while (running || !writeQueue.isEmpty() || !operationQueue.isEmpty()) {
                DatabaseOperation<?> operation = operationQueue.poll();
                if (operation != null) {
                    flushWritesThrough(operation.writeBarrier);
                    executeOperation(operation);
                    flushWriteBatch(false);
                    continue;
                }

                PendingBlockChange first = writeQueue.peek();
                if (first != null && (!running
                        || writeQueue.size() >= pressureFlushThreshold
                        || plugin.getServer().getCurrentTick() != first.enqueuedTick)) {
                    flushWriteBatch(false);
                    continue;
                }

                try {
                    DatabaseOperation<?> waitingOperation = operationQueue.poll(10, TimeUnit.MILLISECONDS);
                    if (waitingOperation != null) {
                        flushWritesThrough(waitingOperation.writeBarrier);
                        executeOperation(waitingOperation);
                        flushWriteBatch(false);
                    }
                } catch (InterruptedException ignored) {
                    // Shutdown interrupts this short wait so queued work can be drained immediately.
                }
            }

            checkpointWal(databaseConnection);
        } catch (Throwable exception) {
            healthy = false;
            String reason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            int abandonedWrites = abandonQueuedWrites();
            if (abandonedWrites > 0) {
                droppedWrites.addAndGet(abandonedWrites);
                reason += "; " + abandonedWrites + " queued write"
                        + (abandonedWrites == 1 ? " was" : "s were") + " abandoned";
            }
            lastError = reason;
            if (!started.isDone()) {
                started.completeExceptionally(exception);
            }
            plugin.getLogger().log(Level.SEVERE, "FragGuard database worker stopped unexpectedly", exception);
            if (abandonedWrites > 0) {
                plugin.getLogger().severe("FragGuard lost " + abandonedWrites
                        + " queued block log write(s) when the database worker stopped.");
            }
            DatabaseOperation<?> pending;
            while ((pending = operationQueue.poll()) != null) {
                pending.future.completeExceptionally(exception);
            }
        } finally {
            running = false;
            connection = null;
            workerStopped = true;
        }
    }

    private void checkpointWal(Connection databaseConnection) throws SQLException {
        try (Statement statement = databaseConnection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            if (!rows.next()) {
                throw new SQLException("SQLite returned no WAL checkpoint result during shutdown.");
            }
            int busyConnections = rows.getInt(1);
            if (busyConnections != 0) {
                throw new SQLException("SQLite WAL checkpoint remained busy with " + busyConnections
                        + " connection(s) during shutdown.");
            }
        }
        walCheckpointCompleted = true;
    }

    private int abandonQueuedWrites() {
        synchronized (coalescedChanges) {
            int abandonedWrites = writeQueue.size();
            writeQueue.clear();
            coalescedChanges.clear();
            return abandonedWrites;
        }
    }

    private void initializeSchema(Connection databaseConnection, List<WorldIdentity> loadedWorlds) throws SQLException {
        try (Statement statement = databaseConnection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
        }

        int schemaVersion = readSchemaVersion(databaseConnection);
        if (schemaVersion > SCHEMA_VERSION) {
            throw new SQLException("FragGuard database schema version " + schemaVersion
                    + " is newer than the supported version " + SCHEMA_VERSION
                    + ". Upgrade FragGuard instead of opening this database with an older plugin.");
        }

        if (schemaVersion < SCHEMA_VERSION && hasExistingSchema(databaseConnection)) {
            createMigrationBackup(databaseConnection, schemaVersion);
        }

        try (Statement statement = databaseConnection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
        }

        while (schemaVersion < SCHEMA_VERSION) {
            int previousVersion = schemaVersion;
            inTransaction(databaseConnection, () -> {
                switch (previousVersion) {
                    case 0 -> migrateUnversionedSchema(databaseConnection, loadedWorlds);
                    case 1 -> migrateBlockEntitySchema(databaseConnection);
                    case 2 -> migrateRollbackSafetySchema(databaseConnection);
                    default -> throw new SQLException("No FragGuard database migration exists for schema version "
                            + previousVersion + ".");
                }
                try (Statement statement = databaseConnection.createStatement()) {
                    statement.execute("PRAGMA user_version=" + (previousVersion + 1));
                }
                return null;
            });
            schemaVersion++;
            plugin.getLogger().info("Migrated FragGuard's SQLite schema from version " + previousVersion
                    + " to version " + schemaVersion + ".");
        }

        inTransaction(databaseConnection, () -> {
            refreshRollbackWorldNames(databaseConnection, loadedWorlds);
            return null;
        });
    }

    private int readSchemaVersion(Connection databaseConnection) throws SQLException {
        try (Statement statement = databaseConnection.createStatement();
             ResultSet version = statement.executeQuery("PRAGMA user_version")) {
            if (!version.next()) {
                throw new SQLException("SQLite did not return FragGuard's database schema version.");
            }
            return version.getInt(1);
        }
    }

    private boolean hasExistingSchema(Connection databaseConnection) throws SQLException {
        try (Statement statement = databaseConnection.createStatement();
             ResultSet tables = statement.executeQuery("""
                     SELECT 1 FROM sqlite_master
                     WHERE type = 'table'
                       AND name IN ('block_changes', 'rollback_jobs', 'rollback_job_changes')
                     LIMIT 1
                     """)) {
            return tables.next();
        }
    }

    private void createMigrationBackup(Connection databaseConnection, int previousVersion) throws SQLException {
        File backupDirectory = new File(plugin.getDataFolder(), "backups");
        if (!backupDirectory.isDirectory() && !backupDirectory.mkdirs()) {
            throw new SQLException("Cannot safely migrate FragGuard's database because the backup directory "
                    + backupDirectory.getAbsolutePath() + " could not be created.");
        }

        String prefix = databaseFile.getName() + ".pre-migration-v" + previousVersion
                + "-to-v" + SCHEMA_VERSION + "-" + System.currentTimeMillis();
        File backupFile = new File(backupDirectory, prefix + ".bak");
        for (int attempt = 1; backupFile.exists(); attempt++) {
            backupFile = new File(backupDirectory, prefix + "-" + attempt + ".bak");
        }

        try (PreparedStatement statement = databaseConnection.prepareStatement("VACUUM INTO ?")) {
            statement.setString(1, backupFile.getAbsolutePath());
            statement.execute();
        } catch (SQLException exception) {
            throw new SQLException("Cannot safely migrate FragGuard's database because the SQLite backup at "
                    + backupFile.getAbsolutePath() + " could not be created.", exception);
        }

        try (Connection backupConnection = DriverManager.getConnection(
                "jdbc:sqlite:" + backupFile.getAbsolutePath());
             Statement statement = backupConnection.createStatement();
             ResultSet check = statement.executeQuery("PRAGMA quick_check")) {
            if (!check.next() || !"ok".equalsIgnoreCase(check.getString(1))) {
                throw new SQLException("The pre-migration SQLite backup did not pass its integrity check.");
            }
        } catch (SQLException exception) {
            throw new SQLException("Cannot safely migrate FragGuard's database because the backup at "
                    + backupFile.getAbsolutePath() + " could not be verified.", exception);
        }

        plugin.getLogger().info("Created and verified FragGuard's pre-migration database backup: "
                + backupFile.getAbsolutePath());
    }

    private void migrateUnversionedSchema(Connection databaseConnection, List<WorldIdentity> loadedWorlds)
            throws SQLException {
        try (Statement statement = databaseConnection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS block_changes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        happened_at INTEGER NOT NULL,
                        actor_uuid TEXT NOT NULL,
                        actor_name TEXT NOT NULL,
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        action TEXT NOT NULL,
                        before_data TEXT NOT NULL,
                        after_data TEXT NOT NULL,
                        world_uuid TEXT NOT NULL DEFAULT '',
                        chunk_x INTEGER NOT NULL DEFAULT 0,
                        chunk_z INTEGER NOT NULL DEFAULT 0,
                        coalesce_session TEXT,
                        server_tick INTEGER
                    )
                    """);
        }

        addColumnIfMissing(databaseConnection, "block_changes", "world_uuid", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(databaseConnection, "block_changes", "chunk_x", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(databaseConnection, "block_changes", "chunk_z", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(databaseConnection, "block_changes", "coalesce_session", "TEXT");
        addColumnIfMissing(databaseConnection, "block_changes", "server_tick", "INTEGER");

        try (Statement statement = databaseConnection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE block_changes
                    SET world_uuid = world, chunk_x = x >> 4, chunk_z = z >> 4
                    WHERE world_uuid = ''
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fg_time ON block_changes(happened_at)");
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_fg_spatial_time
                    ON block_changes(world_uuid, chunk_x, chunk_z, happened_at)
                    """);
            statement.executeUpdate("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_fg_tick_coalesce
                    ON block_changes(world_uuid, x, y, z, coalesce_session, server_tick)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS rollback_jobs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        actor_uuid TEXT NOT NULL,
                        actor_name TEXT NOT NULL,
                        world_uuid TEXT NOT NULL,
                        world TEXT NOT NULL,
                        center_x INTEGER NOT NULL,
                        center_z INTEGER NOT NULL,
                        radius INTEGER NOT NULL,
                        target_timestamp INTEGER NOT NULL,
                        snapshot_timestamp INTEGER NOT NULL DEFAULT 0,
                        force INTEGER NOT NULL DEFAULT 0,
                        status TEXT NOT NULL,
                        total_blocks INTEGER NOT NULL,
                        processed_blocks INTEGER NOT NULL DEFAULT 0,
                        applied_blocks INTEGER NOT NULL DEFAULT 0,
                        conflict_blocks INTEGER NOT NULL DEFAULT 0,
                        undo_processed_blocks INTEGER NOT NULL DEFAULT 0,
                        last_error TEXT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS rollback_job_changes (
                        job_id INTEGER NOT NULL,
                        sequence INTEGER NOT NULL,
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        before_data TEXT,
                        target_data TEXT NOT NULL,
                        expected_data TEXT,
                        processed INTEGER NOT NULL DEFAULT 0,
                        applied INTEGER NOT NULL DEFAULT 0,
                        conflicted INTEGER NOT NULL DEFAULT 0,
                        undone INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(job_id, sequence),
                        FOREIGN KEY(job_id) REFERENCES rollback_jobs(id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fg_active_jobs ON rollback_jobs(world_uuid, status)");
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_fg_pending_changes
                    ON rollback_job_changes(job_id, processed, sequence)
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_fg_undo_changes
                    ON rollback_job_changes(job_id, undone, sequence)
                    """);
        }

        addColumnIfMissing(databaseConnection, "rollback_jobs", "snapshot_timestamp", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(databaseConnection, "rollback_jobs", "force", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(databaseConnection, "rollback_jobs", "conflict_blocks", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(databaseConnection, "rollback_job_changes", "expected_data", "TEXT");
        addColumnIfMissing(databaseConnection, "rollback_job_changes", "conflicted", "INTEGER NOT NULL DEFAULT 0");

        try (Statement statement = databaseConnection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE rollback_jobs
                    SET snapshot_timestamp = created_at
                    WHERE snapshot_timestamp = 0
                    """);
            statement.executeUpdate("""
                    UPDATE rollback_job_changes
                    SET expected_data = before_data
                    WHERE expected_data IS NULL AND before_data IS NOT NULL
                    """);
        }

        migrateWorldIdentities(databaseConnection, loadedWorlds);
        migrateActionIdentifiers(databaseConnection);
    }

    private void migrateBlockEntitySchema(Connection databaseConnection) throws SQLException {
        addColumnIfMissing(databaseConnection, "block_changes", "before_entity_data", "BLOB");
        addColumnIfMissing(databaseConnection, "block_changes", "after_entity_data", "BLOB");
        addColumnIfMissing(databaseConnection, "rollback_job_changes", "before_entity_data", "BLOB");
        addColumnIfMissing(databaseConnection, "rollback_job_changes", "target_entity_data", "BLOB");
        addColumnIfMissing(databaseConnection, "rollback_job_changes", "expected_entity_data", "BLOB");
    }

    private void migrateRollbackSafetySchema(Connection databaseConnection) throws SQLException {
        addColumnIfMissing(databaseConnection, "block_changes", "rollback_pending",
                "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(databaseConnection, "rollback_job_changes", "applied_data", "TEXT");
        addColumnIfMissing(databaseConnection, "rollback_job_changes", "applied_entity_data", "BLOB");
        addColumnIfMissing(databaseConnection, "rollback_job_changes", "pending_audit_id", "INTEGER");
        addColumnIfMissing(databaseConnection, "rollback_job_changes", "pending_audit_undo",
                "INTEGER NOT NULL DEFAULT 0");
    }

    private void migrateWorldIdentities(Connection databaseConnection, List<WorldIdentity> loadedWorlds)
            throws SQLException {
        try (PreparedStatement changes = databaseConnection.prepareStatement("""
                     UPDATE block_changes SET world_uuid = ?
                     WHERE world = ? AND (world_uuid = '' OR world_uuid = world)
                     """);
             PreparedStatement jobs = databaseConnection.prepareStatement("""
                     UPDATE rollback_jobs SET world_uuid = ?
                     WHERE world = ? AND (world_uuid = '' OR world_uuid = world)
                     """)) {
            for (WorldIdentity world : loadedWorlds) {
                changes.setString(1, world.uuid());
                changes.setString(2, world.name());
                changes.executeUpdate();

                jobs.setString(1, world.uuid());
                jobs.setString(2, world.name());
                jobs.executeUpdate();
            }
        }
    }

    private void migrateActionIdentifiers(Connection databaseConnection) throws SQLException {
        try (PreparedStatement statement = databaseConnection.prepareStatement(
                "UPDATE block_changes SET action = ? WHERE action = ?")) {
            for (ChangeAction action : ChangeAction.values()) {
                statement.setString(1, action.storageId());
                statement.setString(2, action.legacyStorageId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void refreshRollbackWorldNames(Connection databaseConnection, List<WorldIdentity> loadedWorlds)
            throws SQLException {
        try (PreparedStatement jobs = databaseConnection.prepareStatement(
                     "UPDATE rollback_jobs SET world = ? WHERE world_uuid = ? AND world <> ?");
             PreparedStatement changes = databaseConnection.prepareStatement("""
                     UPDATE rollback_job_changes SET world = ?
                     WHERE world <> ?
                       AND job_id IN (SELECT id FROM rollback_jobs WHERE world_uuid = ?)
                     """)) {
            for (WorldIdentity world : loadedWorlds) {
                jobs.setString(1, world.name());
                jobs.setString(2, world.uuid());
                jobs.setString(3, world.name());
                jobs.executeUpdate();

                changes.setString(1, world.name());
                changes.setString(2, world.name());
                changes.setString(3, world.uuid());
                changes.executeUpdate();
            }
        }
    }

    private void addColumnIfMissing(Connection databaseConnection, String table, String column,
                                    String definition) throws SQLException {
        try (Statement statement = databaseConnection.createStatement();
             ResultSet columns = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (columns.next()) {
                if (column.equals(columns.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement statement = databaseConnection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private <T> CompletableFuture<T> submit(SqlOperation<T> work, boolean timedQuery) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (!running) {
            future.completeExceptionally(new IllegalStateException("FragGuard's database is not running."));
            return future;
        }

        DatabaseOperation<T> operation;
        synchronized (coalescedChanges) {
            if (!running) {
                future.completeExceptionally(new IllegalStateException("FragGuard's database is not running."));
                return future;
            }
            operation = new DatabaseOperation<>(acceptedWriteSequence.get(), work, future, timedQuery);
            if (!operationQueue.offer(operation)) {
                future.completeExceptionally(new IllegalStateException("FragGuard's database operation queue is full."));
                warnStorage("FragGuard database operation queue is full (" + operationCapacity + ").", null);
                return future;
            }
        }

        if (timedQuery) {
            future.orTimeout(queryTimeoutSeconds, TimeUnit.SECONDS);
            future.whenComplete((result, throwable) -> {
                if ((future.isCancelled() || throwable instanceof TimeoutException) && activeQuery == future) {
                    cancelActiveStatement();
                }
            });
        }
        return future;
    }

    private void flushWritesThrough(long barrier) throws SQLException {
        while (true) {
            PendingBlockChange next = writeQueue.peek();
            if (next == null || next.sequence > barrier) {
                return;
            }
            flushWriteBatch(true);
        }
    }

    private void flushWriteBatch(boolean force) throws SQLException {
        PendingBlockChange first = writeQueue.peek();
        if (first == null) {
            return;
        }

        long currentTick = plugin.getServer().getCurrentTick();
        boolean pressureFlush = writeQueue.size() >= pressureFlushThreshold;
        if (!force && running && first.enqueuedTick == currentTick && !pressureFlush) {
            return;
        }
        boolean allowCurrentTick = force || !running || pressureFlush;

        List<PendingBlockChange> batch = new ArrayList<>(Math.min(batchSize, writeQueue.size()));
        synchronized (coalescedChanges) {
            while (batch.size() < batchSize) {
                PendingBlockChange pending = writeQueue.peek();
                if (pending == null) {
                    break;
                }
                if (!allowCurrentTick && pending.enqueuedTick == currentTick) {
                    break;
                }
                pending = writeQueue.poll();
                if (pending == null) {
                    break;
                }
                batch.add(pending);
                coalescedChanges.remove(pending.key, pending);
            }
            if (!batch.isEmpty()) {
                activeWriteBatchSize = batch.size();
            }
        }
        if (batch.isEmpty()) {
            return;
        }

        try {
            long persistedCoalesces = inTransaction(connection, () -> {
                long existingRows = countPersistedCoalesceConflicts(connection, batch);
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO block_changes
                        (happened_at, actor_uuid, actor_name, world, x, y, z, action,
                         before_data, after_data, world_uuid, chunk_x, chunk_z, coalesce_session, server_tick,
                         before_entity_data, after_entity_data)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(world_uuid, x, y, z, coalesce_session, server_tick) DO UPDATE SET
                            actor_uuid = excluded.actor_uuid,
                            actor_name = excluded.actor_name,
                            action = excluded.action,
                            after_data = excluded.after_data,
                            after_entity_data = excluded.after_entity_data
                        """)) {
                    for (PendingBlockChange pending : batch) {
                        BlockChange change = pending.change;
                        if (sameState(change.beforeData(), change.beforeEntityData(),
                                change.afterData(), change.afterEntityData())) {
                            continue;
                        }
                        statement.setLong(1, change.happenedAt());
                        statement.setString(2, change.actorUuid());
                        statement.setString(3, change.actorName());
                        statement.setString(4, change.worldName());
                        statement.setInt(5, change.x());
                        statement.setInt(6, change.y());
                        statement.setInt(7, change.z());
                        statement.setString(8, change.action().storageId());
                        statement.setString(9, change.beforeData());
                        statement.setString(10, change.afterData());
                        statement.setString(11, pending.worldUuid);
                        statement.setInt(12, change.x() >> 4);
                        statement.setInt(13, change.z() >> 4);
                        statement.setString(14, coalesceSession);
                        statement.setLong(15, pending.key.tick());
                        statement.setBytes(16, change.beforeEntityData());
                        statement.setBytes(17, change.afterEntityData());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM block_changes
                        WHERE world_uuid = ? AND x = ? AND y = ? AND z = ?
                          AND coalesce_session = ? AND server_tick = ?
                          AND before_data = after_data
                          AND before_entity_data IS after_entity_data
                        """)) {
                    for (PendingBlockChange pending : batch) {
                        statement.setString(1, pending.worldUuid);
                        statement.setInt(2, pending.key.x());
                        statement.setInt(3, pending.key.y());
                        statement.setInt(4, pending.key.z());
                        statement.setString(5, coalesceSession);
                        statement.setLong(6, pending.key.tick());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                return existingRows;
            });
            synchronized (coalescedChanges) {
                completedWrites.addAndGet(batch.size());
                activeWriteBatchSize = 0;
            }
            coalescedWrites.addAndGet(persistedCoalesces);
            healthy = true;
            lastError = "";
        } catch (SQLException exception) {
            synchronized (coalescedChanges) {
                droppedWrites.addAndGet(batch.size());
                activeWriteBatchSize = 0;
            }
            healthy = false;
            lastError = exception.getMessage();
            warnStorage("FragGuard could not persist " + batch.size()
                    + " queued block changes; check SQLite storage health.", exception);
            throw exception;
        } finally {
            synchronized (coalescedChanges) {
                activeWriteBatchSize = 0;
            }
        }
    }

    private long countPersistedCoalesceConflicts(Connection databaseConnection,
                                                  List<PendingBlockChange> batch) throws SQLException {
        List<PendingBlockChange> candidates = batch.stream()
                .filter(pending -> !pending.change.beforeData().equals(pending.change.afterData()))
                .toList();
        long conflicts = 0L;
        for (int offset = 0; offset < candidates.size(); offset += COALESCE_CONFLICT_QUERY_BATCH_SIZE) {
            int end = Math.min(candidates.size(), offset + COALESCE_CONFLICT_QUERY_BATCH_SIZE);
            StringBuilder values = new StringBuilder();
            for (int index = offset; index < end; index++) {
                if (!values.isEmpty()) {
                    values.append(", ");
                }
                values.append("(?, ?, ?, ?, ?, ?)");
            }

            String sql = """
                    WITH incoming(world_uuid, x, y, z, coalesce_session, server_tick) AS (
                        VALUES %s
                    )
                    SELECT COUNT(*)
                    FROM incoming
                    JOIN block_changes existing
                      ON existing.world_uuid = incoming.world_uuid
                     AND existing.x = incoming.x
                     AND existing.y = incoming.y
                     AND existing.z = incoming.z
                     AND existing.coalesce_session = incoming.coalesce_session
                     AND existing.server_tick = incoming.server_tick
                    """.formatted(values);
            try (PreparedStatement statement = databaseConnection.prepareStatement(sql)) {
                int parameter = 1;
                for (int index = offset; index < end; index++) {
                    PendingBlockChange pending = candidates.get(index);
                    statement.setString(parameter++, pending.worldUuid);
                    statement.setInt(parameter++, pending.key.x());
                    statement.setInt(parameter++, pending.key.y());
                    statement.setInt(parameter++, pending.key.z());
                    statement.setString(parameter++, coalesceSession);
                    statement.setLong(parameter++, pending.key.tick());
                }
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) {
                        conflicts += rows.getLong(1);
                    }
                }
            }
        }
        return conflicts;
    }

    private <T> void executeOperation(DatabaseOperation<T> operation) {
        if (operation.future.isDone()) {
            return;
        }
        activeQuery = operation.timedQuery ? operation.future : null;
        try {
            T result = operation.work.execute(connection);
            operation.future.complete(result);
            healthy = true;
            lastError = "";
        } catch (Throwable exception) {
            if (exception instanceof SQLException) {
                healthy = false;
                lastError = exception.getMessage();
                warnStorage("FragGuard database operation failed; check SQLite storage health.", exception);
            }
            operation.future.completeExceptionally(exception);
        } finally {
            activeStatement = null;
            activeQuery = null;
        }
    }

    private int countLookup(Connection databaseConnection, String worldUuid, String worldName,
                            int centerX, int centerZ, int radius, long cutoffTimestamp) throws SQLException {
        String sql = "SELECT COUNT(*) FROM block_changes WHERE " + AREA_FILTER.formatted(">=");
        try (PreparedStatement statement = queryStatement(databaseConnection, sql)) {
            bindArea(statement, worldUuid, worldName, centerX, centerZ, radius, cutoffTimestamp);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private List<LookupRow> selectLookup(Connection databaseConnection, String worldUuid, String worldName,
                                         int centerX, int centerZ, int radius, long cutoffTimestamp,
                                         int page, int pageSize) throws SQLException {
        String sql = """
                SELECT happened_at, actor_name, world, x, y, z, action, before_data, after_data
                FROM block_changes
                WHERE %s
                ORDER BY happened_at DESC, id DESC
                LIMIT ? OFFSET ?
                """.formatted(AREA_FILTER.formatted(">="));
        try (PreparedStatement statement = queryStatement(databaseConnection, sql)) {
            int index = bindArea(statement, worldUuid, worldName, centerX, centerZ, radius, cutoffTimestamp);
            statement.setInt(index++, pageSize);
            statement.setInt(index, (page - 1) * pageSize);

            List<LookupRow> rows = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(new LookupRow(resultSet.getLong("happened_at"), resultSet.getString("actor_name"),
                            resultSet.getString("world"), resultSet.getInt("x"), resultSet.getInt("y"),
                            resultSet.getInt("z"), ChangeAction.fromStorageId(resultSet.getString("action")),
                            resultSet.getString("before_data"), resultSet.getString("after_data")));
                }
            }
            return rows;
        }
    }

    private List<RollbackTarget> selectRollbackTargets(Connection databaseConnection, String worldUuid,
                                                         String worldName, int centerX, int centerZ,
                                                         int radius, long targetTimestamp,
                                                         long snapshotTimestamp, int maxBlocks,
                                                         long maxSnapshotBytes) throws SQLException {
        String sql = """
                WITH ranked AS (
                    SELECT x, y, z, before_data, after_data, before_entity_data, after_entity_data,
                           happened_at, id,
                           ROW_NUMBER() OVER (
                               PARTITION BY x, y, z
                               ORDER BY happened_at ASC, id ASC
                           ) AS first_rank,
                           ROW_NUMBER() OVER (
                               PARTITION BY x, y, z
                               ORDER BY happened_at DESC, id DESC
                           ) AS last_rank
                    FROM block_changes
                    WHERE %s
                      AND happened_at <= ?
                ),
                first_changes AS (
                    SELECT x, y, z, before_data, before_entity_data, happened_at, id
                    FROM ranked
                    WHERE first_rank = 1
                ),
                last_changes AS (
                    SELECT x, y, z, after_data, after_entity_data
                    FROM ranked
                    WHERE last_rank = 1
                )
                SELECT first_changes.x, first_changes.y, first_changes.z,
                       first_changes.before_data AS target_data,
                       last_changes.after_data AS expected_data,
                       COALESCE(length(first_changes.before_entity_data), 0) AS target_entity_bytes,
                       COALESCE(length(last_changes.after_entity_data), 0) AS expected_entity_bytes,
                       first_changes.before_entity_data AS target_entity_data,
                       last_changes.after_entity_data AS expected_entity_data,
                       first_changes.happened_at, first_changes.id
                FROM first_changes
                JOIN last_changes USING (x, y, z)
                ORDER BY first_changes.happened_at ASC, first_changes.id ASC
                LIMIT ?
                """.formatted(AREA_FILTER.formatted(">"));

        try (PreparedStatement statement = queryStatement(databaseConnection, sql)) {
            int index = bindArea(statement, worldUuid, worldName, centerX, centerZ, radius, targetTimestamp);
            statement.setLong(index++, snapshotTimestamp);
            statement.setLong(index, (long) maxBlocks + 1L);
            List<RollbackTarget> targets = new ArrayList<>(Math.min(maxBlocks, 255) + 1);
            long snapshotBytes = 0L;
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    long targetBytes = resultSet.getLong("target_entity_bytes");
                    long expectedBytes = resultSet.getLong("expected_entity_bytes");
                    if (targetBytes > maxSnapshotBytes - snapshotBytes
                            || expectedBytes > maxSnapshotBytes - snapshotBytes - targetBytes) {
                        throw new RollbackSnapshotLimitExceededException(maxSnapshotBytes);
                    }
                    snapshotBytes += targetBytes + expectedBytes;
                    targets.add(new RollbackTarget(worldName, resultSet.getInt("x"), resultSet.getInt("y"),
                            resultSet.getInt("z"), Objects.requireNonNull(resultSet.getString("target_data")),
                            Objects.requireNonNull(resultSet.getString("expected_data")),
                            resultSet.getBytes("target_entity_data"), resultSet.getBytes("expected_entity_data")));
                }
            }
            return targets;
        }
    }

    private PreparedStatement queryStatement(Connection databaseConnection, String sql) throws SQLException {
        PreparedStatement statement = databaseConnection.prepareStatement(sql);
        statement.setQueryTimeout(queryTimeoutSeconds);
        activeStatement = statement;
        return statement;
    }

    private int bindArea(PreparedStatement statement, String worldUuid, String worldName,
                         int centerX, int centerZ, int radius, long timestamp) throws SQLException {
        long minX = (long) centerX - radius;
        long maxX = (long) centerX + radius;
        long minZ = (long) centerZ - radius;
        long maxZ = (long) centerZ + radius;
        int index = 1;
        statement.setString(index++, worldUuid);
        statement.setString(index++, worldName);
        statement.setString(index++, worldUuid);
        statement.setString(index++, worldName);
        statement.setLong(index++, timestamp);
        statement.setLong(index++, Math.floorDiv(minX, 16L));
        statement.setLong(index++, Math.floorDiv(maxX, 16L));
        statement.setLong(index++, Math.floorDiv(minZ, 16L));
        statement.setLong(index++, Math.floorDiv(maxZ, 16L));
        statement.setLong(index++, minX);
        statement.setLong(index++, maxX);
        statement.setLong(index++, minZ);
        statement.setLong(index++, maxZ);
        statement.setInt(index++, centerX);
        statement.setInt(index++, centerX);
        statement.setInt(index++, centerZ);
        statement.setInt(index++, centerZ);
        statement.setLong(index++, (long) radius * radius);
        return index;
    }

    private CompletableFuture<Void> markBatchAsync(long jobId, List<RollbackStepResult> results, boolean undo) {
        List<RollbackStepResult> savedResults = List.copyOf(results);
        return submit(databaseConnection -> inTransaction(databaseConnection, () -> {
            String sql = undo
                    ? """
                      UPDATE rollback_job_changes
                      SET undone = 1, pending_audit_id = NULL, pending_audit_undo = 0
                      WHERE job_id = ? AND sequence = ? AND undone = 0
                      """
                    : """
                      UPDATE rollback_job_changes
                      SET processed = 1, applied = ?, conflicted = ?,
                          applied_data = CASE WHEN ? = 1 THEN COALESCE(?, target_data) ELSE applied_data END,
                          applied_entity_data = CASE
                              WHEN ? = 0 THEN applied_entity_data
                              WHEN ? IS NULL THEN target_entity_data
                              ELSE ?
                          END,
                          pending_audit_id = NULL, pending_audit_undo = 0
                      WHERE job_id = ? AND sequence = ? AND processed = 0
                      """;
            int processed = 0;
            int applied = 0;
            int conflicts = 0;
            try (PreparedStatement confirmAudit = databaseConnection.prepareStatement("""
                         UPDATE block_changes
                         SET after_data = COALESCE(?, after_data),
                             after_entity_data = CASE WHEN ? IS NULL THEN after_entity_data ELSE ? END,
                             rollback_pending = 0
                         WHERE id = (SELECT pending_audit_id FROM rollback_job_changes
                                     WHERE job_id = ? AND sequence = ? AND pending_audit_undo = ?)
                           AND rollback_pending = 1
                         """);
                 PreparedStatement deleteAudit = databaseConnection.prepareStatement("""
                         DELETE FROM block_changes
                         WHERE id = (SELECT pending_audit_id FROM rollback_job_changes
                                     WHERE job_id = ? AND sequence = ?)
                           AND rollback_pending = 1
                         """);
                 PreparedStatement clearAudit = databaseConnection.prepareStatement("""
                         UPDATE rollback_job_changes
                         SET pending_audit_id = NULL, pending_audit_undo = 0
                         WHERE job_id = ? AND sequence = ?
                         """);
                 PreparedStatement statement = databaseConnection.prepareStatement(sql)) {
                for (RollbackStepResult result : savedResults) {
                    if (result.changed()) {
                        confirmAudit.setString(1, result.appliedData());
                        confirmAudit.setString(2, result.appliedData());
                        confirmAudit.setBytes(3, result.appliedEntityData());
                        confirmAudit.setLong(4, jobId);
                        confirmAudit.setInt(5, result.sequence());
                        confirmAudit.setInt(6, undo ? 1 : 0);
                        confirmAudit.executeUpdate();
                        deleteAudit.setLong(1, jobId);
                        deleteAudit.setInt(2, result.sequence());
                        deleteAudit.executeUpdate();
                    } else {
                        deleteAudit.setLong(1, jobId);
                        deleteAudit.setInt(2, result.sequence());
                        deleteAudit.executeUpdate();
                    }

                    if (undo && result.conflicted()) {
                        clearAudit.setLong(1, jobId);
                        clearAudit.setInt(2, result.sequence());
                        clearAudit.executeUpdate();
                        continue;
                    }
                    int index = 1;
                    if (!undo) {
                        statement.setInt(index++, result.changed() ? 1 : 0);
                        statement.setInt(index++, result.conflicted() ? 1 : 0);
                        statement.setInt(index++, result.changed() ? 1 : 0);
                        statement.setString(index++, result.appliedData());
                        statement.setInt(index++, result.changed() ? 1 : 0);
                        statement.setString(index++, result.appliedData());
                        statement.setBytes(index++, result.appliedEntityData());
                    }
                    statement.setLong(index++, jobId);
                    statement.setInt(index, result.sequence());
                    int updated = statement.executeUpdate();
                    processed += updated;
                    if (!undo && updated > 0 && result.changed()) {
                        applied++;
                    }
                    if (!undo && updated > 0 && result.conflicted()) {
                        conflicts++;
                    }
                }
            }

            String counters = undo
                    ? "UPDATE rollback_jobs SET undo_processed_blocks = undo_processed_blocks + ?, updated_at = ? WHERE id = ?"
                    : "UPDATE rollback_jobs SET processed_blocks = processed_blocks + ?, applied_blocks = applied_blocks + ?, conflict_blocks = conflict_blocks + ?, updated_at = ? WHERE id = ?";
            try (PreparedStatement statement = databaseConnection.prepareStatement(counters)) {
                int index = 1;
                statement.setInt(index++, processed);
                if (!undo) {
                    statement.setInt(index++, applied);
                    statement.setInt(index++, conflicts);
                }
                statement.setLong(index++, System.currentTimeMillis());
                statement.setLong(index, jobId);
                statement.executeUpdate();
            }
            return null;
        }), false);
    }

    private int countUnresolvedUndoChanges(Connection databaseConnection, long jobId) throws SQLException {
        try (PreparedStatement statement = databaseConnection.prepareStatement("""
                SELECT COUNT(*)
                FROM rollback_job_changes
                WHERE job_id = ? AND before_data IS NOT NULL AND conflicted = 0 AND undone = 0
                """)) {
            statement.setLong(1, jobId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private void rejectOverlappingJob(Connection databaseConnection, String worldUuid, String worldName,
                                       int centerX, int centerZ, int radius, long excludedJobId) throws SQLException {
        try (PreparedStatement statement = databaseConnection.prepareStatement("""
                SELECT id, center_x, center_z, radius
                FROM rollback_jobs
                WHERE world_uuid IN (?, ?) AND (world_uuid = ? OR world = ?)
                  AND status IN ('RUNNING', 'UNDOING') AND id <> ?
                """)) {
            statement.setString(1, worldUuid);
            statement.setString(2, worldName);
            statement.setString(3, worldUuid);
            statement.setString(4, worldName);
            statement.setLong(5, excludedJobId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long dx = (long) rows.getInt("center_x") - centerX;
                    long dz = (long) rows.getInt("center_z") - centerZ;
                    long combinedRadius = (long) rows.getInt("radius") + radius;
                    if (dx * dx + dz * dz <= combinedRadius * combinedRadius) {
                        throw new IllegalStateException("Rollback job #" + rows.getLong("id")
                                + " is already running in an overlapping region.");
                    }
                }
            }
        }
    }

    private RollbackJob loadJob(Connection databaseConnection, long jobId) throws SQLException {
        try (PreparedStatement statement = databaseConnection.prepareStatement(
                "SELECT * FROM rollback_jobs WHERE id = ?")) {
            statement.setLong(1, jobId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Rollback job #" + jobId + " does not exist.");
                }
                return readJob(rows);
            }
        }
    }

    private RollbackJob readJob(ResultSet rows) throws SQLException {
        long createdAt = rows.getLong("created_at");
        long snapshotTimestamp = rows.getLong("snapshot_timestamp");
        if (snapshotTimestamp == 0L) {
            snapshotTimestamp = createdAt;
        }
        return new RollbackJob(rows.getLong("id"), createdAt, rows.getString("actor_uuid"),
                rows.getString("actor_name"), rows.getString("world_uuid"), rows.getString("world"),
                rows.getInt("center_x"), rows.getInt("center_z"), rows.getInt("radius"),
                rows.getLong("target_timestamp"), snapshotTimestamp, rows.getBoolean("force"),
                rows.getString("status"), rows.getInt("total_blocks"), rows.getInt("processed_blocks"),
                rows.getInt("applied_blocks"), rows.getInt("conflict_blocks"),
                rows.getInt("undo_processed_blocks"), rows.getString("last_error"));
    }

    private void touchJob(Connection databaseConnection, long jobId) throws SQLException {
        try (PreparedStatement statement = databaseConnection.prepareStatement(
                "UPDATE rollback_jobs SET updated_at = ? WHERE id = ?")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setLong(2, jobId);
            statement.executeUpdate();
        }
    }

    private <T> T inTransaction(Connection databaseConnection, SqlSupplier<T> operation) throws SQLException {
        boolean previousAutoCommit = databaseConnection.getAutoCommit();
        databaseConnection.setAutoCommit(false);
        try {
            T result = operation.get();
            databaseConnection.commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            databaseConnection.rollback();
            throw exception;
        } finally {
            databaseConnection.setAutoCommit(previousAutoCommit);
        }
    }

    private BlockChange merge(BlockChange previous, BlockChange latest, long serverTick) {
        return new BlockChange(previous.happenedAt(), serverTick, latest.actorUuid(), latest.actorName(), previous.worldName(),
                previous.x(), previous.y(), previous.z(), latest.action(), previous.beforeData(), latest.afterData(),
                previous.beforeEntityData(), latest.afterEntityData());
    }

    private static boolean sameState(String firstData, byte[] firstEntityData,
                                     String secondData, byte[] secondEntityData) {
        return firstData.equals(secondData) && Arrays.equals(firstEntityData, secondEntityData);
    }

    private String resolveWorldUuid(String worldName) {
        World world = plugin.getServer().getWorld(worldName);
        return world == null ? worldName : world.getUID().toString();
    }

    static final class RollbackSnapshotLimitExceededException extends IllegalStateException {
        private final long maximumBytes;

        RollbackSnapshotLimitExceededException(long maximumBytes) {
            super("Rollback preview block-entity snapshots exceed the " + maximumBytes + "-byte limit.");
            this.maximumBytes = maximumBytes;
        }

        long maximumBytes() {
            return maximumBytes;
        }
    }

    static final class RollbackJobSnapshotLimitExceededException extends IllegalStateException {
        private final long maximumBytes;

        RollbackJobSnapshotLimitExceededException(long maximumBytes, boolean undo) {
            super((undo ? "Undo" : "Rollback") + " job block-entity snapshots exceed the configured "
                    + "rollback-max-snapshot-bytes-per-command limit of " + maximumBytes + " bytes.");
            this.maximumBytes = maximumBytes;
        }

        long maximumBytes() {
            return maximumBytes;
        }
    }

    private record WorldIdentity(String uuid, String name) {
    }

    private void cancelActiveStatement() {
        Statement statement = activeStatement;
        if (statement == null) {
            return;
        }
        try {
            statement.cancel();
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.FINE, "Could not cancel FragGuard's active SQLite query", exception);
        }
    }

    private void warnStorage(String message, Throwable exception) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - lastWarningAt < WARNING_INTERVAL_MILLIS) {
                return;
            }
            lastWarningAt = now;
        }
        if (exception == null) {
            plugin.getLogger().warning(message);
        } else {
            plugin.getLogger().log(Level.WARNING, message, exception);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T execute(Connection databaseConnection) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    private record CoalesceKey(String worldUuid, int x, int y, int z, long tick) {
    }

    private record RequiredBlockChange(String worldUuid, BlockChange change) {
    }

    private record ResolvedRollbackPendingAudit(int sequence, String worldUuid, BlockChange change) {
    }

    private static final class PendingBlockChange {
        private final long sequence;
        private final CoalesceKey key;
        private final long enqueuedTick;
        private final String worldUuid;
        private BlockChange change;

        private PendingBlockChange(long sequence, CoalesceKey key, long enqueuedTick,
                                   String worldUuid, BlockChange change) {
            this.sequence = sequence;
            this.key = key;
            this.enqueuedTick = enqueuedTick;
            this.worldUuid = worldUuid;
            this.change = change;
        }
    }

    private static final class DatabaseOperation<T> {
        private final long writeBarrier;
        private final SqlOperation<T> work;
        private final CompletableFuture<T> future;
        private final boolean timedQuery;

        private DatabaseOperation(long writeBarrier, SqlOperation<T> work,
                                  CompletableFuture<T> future, boolean timedQuery) {
            this.writeBarrier = writeBarrier;
            this.work = work;
            this.future = future;
            this.timedQuery = timedQuery;
        }
    }
}
