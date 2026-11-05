package org.pinnaclesmp.fragguard;

import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
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
    static final int SCHEMA_VERSION = 9;
    static final long DEFAULT_MAX_ROLLBACK_SNAPSHOT_BYTES = 64L * 1024L * 1024L;
    private static final long WARNING_INTERVAL_MILLIS = 10_000L;
    private static final int MAX_PERSISTED_COALESCE_CANDIDATES = 4_096;
    private static final long PERSISTED_COALESCE_TICK_RETENTION = 2L;
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
    private final LinkedHashMap<CoalesceKey, PersistedCoalesceCandidate> persistedCoalesceCandidates =
            new LinkedHashMap<>();
    private final AtomicLong acceptedWriteSequence = new AtomicLong();
    private final AtomicLong completedWrites = new AtomicLong();
    private final AtomicLong droppedWrites = new AtomicLong();
    private final AtomicLong coalescedWrites = new AtomicLong();
    private final int writeCapacity;
    private final int persistedCoalesceCapacity;
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
    private volatile String startupStage = "starting database worker";
    private volatile long lastWarningAt;
    private volatile Statement activeStatement;
    private volatile CompletableFuture<?> activeQuery;
    private Connection connection;

    Database(JavaPlugin plugin) {
        this.plugin = plugin;
        this.databaseFile = new File(plugin.getDataFolder(), "fragguard.db");
        this.jdbcUrl = "jdbc:sqlite:" + databaseFile.getAbsolutePath();
        this.writeCapacity = Math.max(64, plugin.getConfig().getInt("database-write-queue-capacity", 20_000));
        this.persistedCoalesceCapacity = Math.max(64, Math.min(writeCapacity, MAX_PERSISTED_COALESCE_CANDIDATES));
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
        startupStage = "starting database worker";
        running = true;
        worker = new Thread(() -> runWorker(started, loadedWorlds), "FragGuard-Database");
        worker.setDaemon(true);
        worker.start();

        long warningSeconds = Math.max(1L,
                plugin.getConfig().getLong("database-startup-warning-seconds", 30L));
        long timeoutSeconds = Math.max(0L,
                plugin.getConfig().getLong("database-startup-timeout-seconds", 0L));
        try {
            DatabaseStartupAwaiter.await(
                    started,
                    TimeUnit.SECONDS.toMillis(warningSeconds),
                    timeoutSeconds == 0L ? 0L : TimeUnit.SECONDS.toMillis(timeoutSeconds),
                    () -> startupStage,
                    plugin.getLogger()::warning);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            running = false;
            throw new SQLException("Interrupted while starting FragGuard's database", exception);
        } catch (ExecutionException exception) {
            running = false;
            throw new SQLException("Could not start FragGuard's database worker", exception.getCause());
        } catch (TimeoutException exception) {
            running = false;
            Thread databaseWorker = worker;
            if (databaseWorker != null) {
                databaseWorker.interrupt();
            }
            throw new SQLException("Timed out after " + timeoutSeconds
                    + " seconds while starting FragGuard's database worker (current step: "
                    + startupStage + ").", exception);
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
            if (existing != null && canCoalesce(existing.change, change)) {
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
                     before_entity_data, after_entity_data, causal_player_uuid, causal_player_name,
                     causal_chain_id, causal_cause_type)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    statement.setString(16, change.causalPlayerUuid());
                    statement.setString(17, change.causalPlayerName());
                    statement.setString(18, change.causalChainId());
                    statement.setString(19, change.causalCauseType());
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
                          before_entity_data, after_entity_data, rollback_pending,
                          causal_player_uuid, causal_player_name, causal_chain_id, causal_cause_type)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?)
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
                    insert.setString(16, change.causalPlayerUuid());
                    insert.setString(17, change.causalPlayerName());
                    insert.setString(18, change.causalChainId());
                    insert.setString(19, change.causalCauseType());
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

    CompletableFuture<DatabaseCleanupResult> cleanupOldRecordsAsync(
            int retentionDays,
            int rollbackJobRetentionDays
    ) {
        return submit(databaseConnection -> inTransaction(databaseConnection, () -> {
            long now = System.currentTimeMillis();
            long blockCutoff = now - TimeUnit.DAYS.toMillis(Math.max(1, retentionDays));
            long rollbackJobCutoff = now - TimeUnit.DAYS.toMillis(Math.max(1, rollbackJobRetentionDays));

            int deletedBlockRecords;
            try (PreparedStatement statement = databaseConnection.prepareStatement(
                    "DELETE FROM block_changes WHERE happened_at < ? AND rollback_pending = 0")) {
                statement.setLong(1, blockCutoff);
                deletedBlockRecords = statement.executeUpdate();
            }

            String terminalJobCondition = """
                    updated_at < ?
                    AND (
                        status IN ('COMPLETED', 'UNDONE')
                        OR (
                            status = 'FAILED'
                            AND NOT EXISTS (
                                SELECT 1
                                FROM rollback_job_changes
                                WHERE job_id = rollback_jobs.id
                                  AND before_data IS NOT NULL
                                  AND conflicted = 0
                                  AND undone = 0
                            )
                        )
                    )
                    """;

            try (PreparedStatement statement = databaseConnection.prepareStatement("""
                    DELETE FROM block_changes
                    WHERE rollback_pending = 1
                      AND id IN (
                          SELECT pending_audit_id
                          FROM rollback_job_changes
                          WHERE pending_audit_id IS NOT NULL
                            AND job_id IN (
                                SELECT id
                                FROM rollback_jobs
                                WHERE %s
                            )
                      )
                    """.formatted(terminalJobCondition))) {
                statement.setLong(1, rollbackJobCutoff);
                statement.executeUpdate();
            }

            int deletedRollbackJobs;
            try (PreparedStatement statement = databaseConnection.prepareStatement(
                    "DELETE FROM rollback_jobs WHERE " + terminalJobCondition)) {
                statement.setLong(1, rollbackJobCutoff);
                deletedRollbackJobs = statement.executeUpdate();
            }

            return new DatabaseCleanupResult(deletedBlockRecords, deletedRollbackJobs);
        }), false);
    }

    /**
     * Completes after all accepted block writes ahead of this operation are durable.
     *
     * <p>The database worker flushes {@code operation.writeBarrier} before executing
     * this no-op. Unlike {@code lookupSinceAsync(..., pageSize=0)}, this performs
     * no legacy COUNT, page query, or block-entity data loading. The GUI can then
     * capture its bounded snapshot and count through GuiLookupStore exactly once.</p>
     */
    CompletableFuture<Void> awaitWritesAsync() {
        return submit(databaseConnection -> null, true);
    }

    /**
     * Shared UUID identity resolution for future server-wide player lookup and
     * player-scoped rollback. Both consume the same historical mapping.
     */
    CompletableFuture<HistoricalPlayerResolver.HistoricalPlayerIdentity> resolveHistoricalPlayerAsync(
            String playerArgument) {
        return submit(connection -> HistoricalPlayerResolver.resolve(connection, playerArgument,
                this::queryStatement), true);
    }

    /**
     * Global player-scoped history: no center/radius and no block-entity BLOBs.
     * Name resolution, count and page selection run on the same ordered database
     * worker behind its write barrier, with cancellable query statements.
     */
    CompletableFuture<PlayerLookupPage> lookupPlayerAsync(
            String playerArgument, String worldName, int page, int pageSize,
            long durationMillis, int retentionDays) {
        return submit(connection -> {
            long now = System.currentTimeMillis();
            long retainedMillis = TimeUnit.DAYS.toMillis(Math.max(1, retentionDays));
            long window = durationMillis <= 0L ? retainedMillis : Math.min(retainedMillis, durationMillis);
            long cutoff = now - window;

            HistoricalPlayerResolver.HistoricalPlayerIdentity player =
                    HistoricalPlayerResolver.resolve(connection, playerArgument, this::queryStatement);
            int safeSize = Math.min(200, Math.max(1, pageSize));
            String uuid = player.uuid().toString();
            String worldFilter = worldName == null ? "" : " AND world = ?";
            String where = "rollback_pending = 0 AND happened_at >= ? AND happened_at <= ?"
                    + " AND " + playerOwnershipPredicate() + worldFilter;

            long total;
            try (PreparedStatement count = queryStatement(connection,
                    "SELECT COUNT(*) FROM block_changes WHERE " + where)) {
                bindPlayerLookup(count, cutoff, now, uuid, worldName);
                try (ResultSet rows = count.executeQuery()) {
                    total = rows.next() ? rows.getLong(1) : 0L;
                }
            }
            int displayTotal = (int) Math.min(Integer.MAX_VALUE, total);
            int totalPages = Math.max(1, (int) Math.ceil(displayTotal / (double) safeSize));
            int safePage = Math.max(1, Math.min(page, totalPages));
            long offset = (long) (safePage - 1) * safeSize;
            String sql = """
                    SELECT id, happened_at, actor_uuid, actor_name,
                           causal_player_uuid, causal_player_name, causal_cause_type,
                           world, x, y, z, action, before_data, after_data,
                           CASE WHEN before_entity_data IS NULL AND after_entity_data IS NULL
                                THEN 0 ELSE 1 END AS has_block_entity_data
                    FROM block_changes
                    WHERE %s
                    ORDER BY happened_at DESC, id DESC
                    LIMIT ? OFFSET ?
                    """.formatted(where);
            List<PlayerLookupRow> history = new ArrayList<>(Math.min(safeSize, 100));
            try (PreparedStatement select = queryStatement(connection, sql)) {
                int index = bindPlayerLookup(select, cutoff, now, uuid, worldName);
                select.setInt(index++, safeSize);
                select.setLong(index, offset);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        history.add(new PlayerLookupRow(
                                rows.getLong("id"), rows.getLong("happened_at"),
                                rows.getString("actor_uuid"), rows.getString("actor_name"),
                                rows.getString("causal_player_uuid"), rows.getString("causal_player_name"),
                                rows.getString("causal_cause_type"), rows.getString("world"),
                                rows.getInt("x"), rows.getInt("y"), rows.getInt("z"),
                                ChangeAction.fromStorageId(rows.getString("action")),
                                rows.getString("before_data"), rows.getString("after_data"),
                                rows.getInt("has_block_entity_data") != 0));
                    }
                }
            }
            return new PlayerLookupPage(player, history, safePage, safeSize, displayTotal);
        }, true);
    }

    private int bindPlayerLookup(PreparedStatement statement, long cutoff, long now,
                                 String uuid, String worldName) throws SQLException {
        int index = 1;
        statement.setLong(index++, cutoff);
        statement.setLong(index++, now);
        statement.setString(index++, uuid);
        statement.setString(index++, uuid);
        if (worldName != null) {
            statement.setString(index++, worldName);
        }
        return index;
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

    CompletableFuture<LookupPage> lookupSinceAsync(String worldName, int centerX, int centerZ, int radius,
                                                   int page, int pageSize, long cutoffTimestamp) {
        String worldUuid = resolveWorldUuid(worldName);
        return submit(databaseConnection -> {
            int total = countLookup(databaseConnection, worldUuid, worldName, centerX, centerZ, radius,
                    cutoffTimestamp);
            int totalPages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
            int safePage = Math.max(1, Math.min(page, totalPages));
            List<LookupRow> rows = selectLookup(databaseConnection, worldUuid, worldName, centerX, centerZ,
                    radius, cutoffTimestamp, safePage, pageSize);
            return new LookupPage(rows, safePage, pageSize, total);
        }, true);
    }

    /**
     * Plans (but does not apply) a selective rollback. Every candidate's full
     * retained timeline is checked newest-first. Historical ownership cannot
     * be bypassed by force; force remains an executor-only live-state option.
     *
     * The existing database worker flushes queued writes before this query,
     * and all statements retain its query timeout/cancellation mechanism.
     */
    CompletableFuture<PlayerRollbackPlan> planPlayerRollbackAsync(
            String playerArgument, long targetTimestamp, long snapshotTimestamp,
            int maxBlocks, long maxSnapshotBytes, int retentionDays, boolean force) {
        return planPlayerRollbackAsync(playerArgument, null, targetTimestamp, snapshotTimestamp,
                maxBlocks, maxSnapshotBytes, retentionDays, force);
    }

    CompletableFuture<PlayerRollbackPlan> planPlayerRollbackAsync(
            String playerArgument, String worldScopeName,
            long targetTimestamp, long snapshotTimestamp,
            int maxBlocks, long maxSnapshotBytes, int retentionDays, boolean force) {
        if (maxBlocks <= 0 || maxSnapshotBytes <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Player rollback block and snapshot limits must be positive."));
        }
        // Bukkit world access must stay on the caller thread, not the SQLite
        // worker. Capturing loaded names by stable UUID also avoids executing
        // the rollback in a reused/renamed historical world name.
        Map<String, String> loadedWorldNames = new HashMap<>();
        for (World world : plugin.getServer().getWorlds()) {
            if (world.getUID() != null && world.getName() != null) {
                loadedWorldNames.put(world.getUID().toString(), world.getName());
            }
        }
        String worldScopeUuid = null;
        if (worldScopeName != null) {
            for (Map.Entry<String, String> loaded : loadedWorldNames.entrySet()) {
                if (worldScopeName.equals(loaded.getValue())) {
                    worldScopeUuid = loaded.getKey();
                    break;
                }
            }
            if (worldScopeUuid == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(
                        "World '" + worldScopeName + "' is not loaded."));
            }
        }
        final String scopedUuid = worldScopeUuid;
        return submit(databaseConnection -> {
            long now = System.currentTimeMillis();
            long upper = Math.min(snapshotTimestamp, now);
            long cutoff = Math.max(targetTimestamp,
                    now - TimeUnit.DAYS.toMillis(Math.max(1, retentionDays)));
            if (cutoff >= upper) {
                throw new IllegalArgumentException("Player rollback time window is empty or outside retention.");
            }
            // Resolve once using stable UUID, including historical/causal-only names.
            var player = HistoricalPlayerResolver.resolve(databaseConnection,
                    playerArgument, this::queryStatement);
            String uuid = player.uuid().toString();

            record Position(String worldUuid, int x, int y, int z) {}
            List<Position> positions = new ArrayList<>();
            String candidatesSql = """
                    SELECT world_uuid, x, y, z
                    FROM block_changes
                    WHERE rollback_pending = 0
                      AND happened_at > ? AND happened_at <= ?
                      AND %s
                      %s
                    GROUP BY world_uuid, x, y, z
                    LIMIT ?
                    """.formatted(playerOwnershipPredicate(),
                    scopedUuid == null ? "" : "AND world_uuid = ?");
            try (PreparedStatement candidates = queryStatement(databaseConnection, candidatesSql)) {
                candidates.setLong(1, cutoff);
                candidates.setLong(2, upper);
                candidates.setString(3, uuid);
                candidates.setString(4, uuid);
                if (scopedUuid != null) {
                    candidates.setString(5, scopedUuid);
                }
                candidates.setLong(scopedUuid == null ? 5 : 6, (long) maxBlocks + 1L);
                try (ResultSet rows = candidates.executeQuery()) {
                    while (rows.next()) {
                        positions.add(new Position(rows.getString("world_uuid"),
                                rows.getInt("x"), rows.getInt("y"), rows.getInt("z")));
                    }
                }
            }
            if (positions.size() > maxBlocks) {
                throw new PlayerRollbackLimitExceededException(maxBlocks);
            }

            List<RollbackTarget> targets = new ArrayList<>(Math.min(positions.size(), 256));
            int historicalConflicts = 0;
            int noOps = 0;
            int unavailable = 0;
            long snapshotBytes = 0L;

            String timelineSql = """
                    SELECT id, happened_at, actor_uuid, actor_name, causal_player_uuid,
                           causal_chain_id, causal_cause_type, world, before_data,
                           after_data, before_entity_data, after_entity_data
                    FROM block_changes
                    WHERE rollback_pending = 0 AND world_uuid = ?
                      AND x = ? AND y = ? AND z = ?
                      AND happened_at > ? AND happened_at <= ?
                    ORDER BY happened_at DESC, id DESC
                    LIMIT ?
                    """;
            for (Position position : positions) {
                if (activeQuery != null && activeQuery.isDone()) {
                    throw new SQLTimeoutException("Player rollback planning exceeded its query deadline.");
                }
                String currentWorldName = loadedWorldNames.get(position.worldUuid());
                if (currentWorldName == null) {
                    unavailable++; // World unloaded or its UUID is not recognized.
                    continue; // Never fall back to a historical/reused display name.
                }
                var scanner = new SelectivePlayerRollbackPlanner.CoordinateScanner(
                        uuid, currentWorldName, position.worldUuid(),
                        position.x(), position.y(), position.z());
                boolean exceededHistoryLimit = false;
                boolean newerOtherActor = false;
                boolean newerTargetActor = false;
                try (PreparedStatement history = queryStatement(databaseConnection, timelineSql)) {
                    history.setString(1, position.worldUuid());
                    history.setInt(2, position.x());
                    history.setInt(3, position.y());
                    history.setInt(4, position.z());
                    history.setLong(5, cutoff);
                    history.setLong(6, now);
                    history.setInt(7, SelectivePlayerRollbackPlanner.MAX_HISTORY_ROWS_PER_COORDINATE + 1);
                    try (ResultSet rows = history.executeQuery()) {
                        while (rows.next()) {
                            if (activeQuery != null && activeQuery.isDone()) {
                                throw new SQLTimeoutException(
                                        "Player rollback planning exceeded its query deadline.");
                            }
                            // A known change after the caller's preview snapshot
                            // makes that preview stale. Never let executor force
                            // overwrite those recorded later edits. The next
                            // planning attempt should capture a new snapshot.
                            if (rows.getLong("happened_at") > upper) {
                                boolean owned = uuid.equals(rows.getString("actor_uuid"))
                                        || uuid.equals(rows.getString("causal_player_uuid"));
                                newerTargetActor = owned;
                                newerOtherActor = !owned;
                                break;
                            }
                            try {
                                scanner.add(new SelectivePlayerRollbackPlanner.HistoryRow(
                                        rows.getLong("id"), rows.getString("actor_uuid"),
                                        rows.getString("causal_player_uuid"), rows.getString("world"),
                                        rows.getString("before_data"), rows.getString("after_data"),
                                        rows.getBytes("before_entity_data"), rows.getBytes("after_entity_data"),
                                        rows.getString("actor_name"), rows.getString("causal_chain_id"),
                                        rows.getString("causal_cause_type")));
                            } catch (SelectivePlayerRollbackPlanner.HistoryLimitExceededException exception) {
                                exceededHistoryLimit = true;
                                break;
                            }
                        }
                    }
                }
                if (newerOtherActor) {
                    historicalConflicts++;
                    continue;
                }
                if (exceededHistoryLimit || newerTargetActor) {
                    unavailable++;
                    continue;
                }
                var decision = scanner.finish();
                if (decision.protectedOlderChanges()
                        || decision.status() == SelectivePlayerRollbackPlanner.Status.HISTORICAL_CONFLICT) {
                    historicalConflicts++;
                }
                switch (decision.status()) {
                    case REVERSIBLE -> {
                        RollbackTarget target = decision.target();
                        long targetBytes = target.targetEntityData() == null
                                ? 0L : target.targetEntityData().length;
                        long expectedBytes = target.expectedEntityData() == null
                                ? 0L : target.expectedEntityData().length;
                        if (targetBytes > maxSnapshotBytes - snapshotBytes
                                || expectedBytes > maxSnapshotBytes - snapshotBytes - targetBytes) {
                            throw new RollbackSnapshotLimitExceededException(maxSnapshotBytes);
                        }
                        snapshotBytes += targetBytes + expectedBytes;
                        targets.add(target);
                    }
                    case NO_OP -> noOps++;
                    case UNAVAILABLE -> unavailable++;
                    case HISTORICAL_CONFLICT -> { /* Already counted above. */ }
                }
            }
            return new PlayerRollbackPlan(player, cutoff, upper, targets,
                    positions.size(), historicalConflicts, noOps, unavailable);
        }, true);
    }

    static final class PlayerRollbackLimitExceededException extends IllegalStateException {
        PlayerRollbackLimitExceededException(int maxBlocks) {
            super("Player rollback touches more than " + maxBlocks
                    + " coordinates; narrow the time window or adjust rollback-max-blocks-per-command.");
        }
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
            rejectOverlappingChunks(databaseConnection,
                    RollbackJobChunk.radius(worldUuid, centerX, centerZ, radius), -1L);
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
                    (job_id, sequence, world, world_uuid, x, y, z, expected_data, target_data,
                     expected_entity_data, target_entity_data)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (int index = 0; index < savedTargets.size(); index++) {
                    RollbackTarget target = savedTargets.get(index);
                    statement.setLong(1, jobId);
                    statement.setInt(2, index);
                    statement.setString(3, target.worldName());
                    statement.setString(4, worldUuid);
                    statement.setInt(5, target.x());
                    statement.setInt(6, target.y());
                    statement.setInt(7, target.z());
                    statement.setString(8, target.expectedData());
                    statement.setString(9, target.blockData());
                    statement.setBytes(10, target.expectedEntityData());
                    statement.setBytes(11, target.targetEntityData());
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

    /**
     * Transactionally save player scope, operator/target identities, per-world
     * target provenance, and chunk reservations before any world mutation.
     * Refuse coordinates with recorded post-preview edits even with force.
     */
    CompletableFuture<RollbackJob> createPlayerRollbackJobAsync(
            String operatorUuid, String operatorName, PlayerRollbackPlan plan,
            String worldScopeName, boolean force) {
        List<RollbackTarget> targets = List.copyOf(plan.targets());
        if (targets.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "There are no safe player rollback targets to execute."));
        }
        String worldScopeUuid = worldScopeName == null ? null : resolveWorldUuid(worldScopeName);
        if (worldScopeName != null && worldScopeUuid.equals(worldScopeName)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "The requested player rollback world is not loaded."));
        }
        final String scopedWorldUuid = worldScopeUuid;
        Set<RollbackJobChunk> reservations = RollbackJobChunk.fromTargets(targets);
        int maxBlocks = Math.max(1, plugin.getConfig().getInt("rollback-max-blocks-per-command", 50_000));
        int maxChunks = Math.max(1, plugin.getConfig().getInt("rollback-max-chunks-per-command", 256));
        long maxSnapshotBytes = Math.max(1L, plugin.getConfig().getLong(
                "rollback-max-snapshot-bytes-per-command", DEFAULT_MAX_ROLLBACK_SNAPSHOT_BYTES));
        if (targets.size() > maxBlocks || reservations.size() > maxChunks) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "The player rollback preview exceeds the currently configured block/chunk limits."));
        }
        long snapshotBytes = 0L;
        for (RollbackTarget target : targets) {
            long expected = target.expectedEntityData() == null ? 0 : target.expectedEntityData().length;
            long desired = target.targetEntityData() == null ? 0 : target.targetEntityData().length;
            if (expected > maxSnapshotBytes - snapshotBytes
                    || desired > maxSnapshotBytes - snapshotBytes - expected) {
                return CompletableFuture.failedFuture(
                        new RollbackSnapshotLimitExceededException(maxSnapshotBytes));
            }
            snapshotBytes += expected + desired;
        }
        int affectedWorlds = (int) reservations.stream().map(RollbackJobChunk::worldUuid).distinct().count();
        return submit(db -> inTransaction(db, () -> {
            rejectOverlappingChunks(db, reservations, -1L);
            // Do not persist an unsafe/stale plan even if another operator
            // changed the world after the original preview was displayed.
            try (PreparedStatement changed = db.prepareStatement("""
                    SELECT 1 FROM block_changes
                    WHERE rollback_pending = 0 AND world_uuid = ?
                      AND x = ? AND y = ? AND z = ?
                      AND happened_at > ?
                    LIMIT 1
                    """)) {
                for (RollbackTarget target : targets) {
                    if (scopedWorldUuid != null && !scopedWorldUuid.equals(target.worldUuid())) {
                        throw new IllegalStateException("Target lies outside the requested world scope.");
                    }
                    changed.setString(1, target.worldUuid());
                    changed.setInt(2, target.x());
                    changed.setInt(3, target.y());
                    changed.setInt(4, target.z());
                    changed.setLong(5, plan.snapshotTimestamp());
                    try (ResultSet later = changed.executeQuery()) {
                        if (later.next()) {
                            throw new IllegalStateException("The player rollback preview is stale. "
                                    + "New recorded changes require a fresh preview.");
                        }
                    }
                }
            }
            long now = System.currentTimeMillis();
            long jobId;
            try (PreparedStatement statement = db.prepareStatement("""
                    INSERT INTO rollback_jobs
                    (created_at, updated_at, actor_uuid, actor_name, world_uuid, world,
                     center_x, center_z, radius, target_timestamp, snapshot_timestamp,
                     force, status, total_blocks, scope_type, target_player_uuid,
                     target_player_name, world_scope_uuid, world_scope_name,
                     historical_conflicts, affected_worlds, affected_chunks)
                    VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, ?, ?, ?, 'RUNNING', ?,
                            'PLAYER', ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, now);
                statement.setLong(2, now);
                statement.setString(3, operatorUuid);
                statement.setString(4, operatorName);
                statement.setString(5, scopedWorldUuid == null ? "" : scopedWorldUuid);
                statement.setString(6, worldScopeName == null ? "*" : worldScopeName);
                statement.setLong(7, plan.targetTimestamp());
                statement.setLong(8, plan.snapshotTimestamp());
                statement.setInt(9, force ? 1 : 0);
                statement.setInt(10, targets.size());
                statement.setString(11, plan.player().uuid().toString());
                statement.setString(12, plan.player().latestName());
                statement.setString(13, scopedWorldUuid);
                statement.setString(14, worldScopeName);
                statement.setInt(15, plan.historicalConflicts());
                statement.setInt(16, affectedWorlds);
                statement.setInt(17, reservations.size());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("SQLite did not return a player rollback job ID.");
                    }
                    jobId = keys.getLong(1);
                }
            }
            try (PreparedStatement changes = db.prepareStatement("""
                    INSERT INTO rollback_job_changes
                    (job_id, sequence, world, world_uuid, x, y, z,
                     expected_data, target_data, expected_entity_data, target_entity_data,
                     physical_actor_uuid, physical_actor_name, causal_player_uuid,
                     causal_chain_id, causal_cause_type)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (int i = 0; i < targets.size(); i++) {
                    RollbackTarget target = targets.get(i);
                    changes.setLong(1, jobId);
                    changes.setInt(2, i);
                    changes.setString(3, target.worldName());
                    changes.setString(4, target.worldUuid());
                    changes.setInt(5, target.x());
                    changes.setInt(6, target.y());
                    changes.setInt(7, target.z());
                    changes.setString(8, target.expectedData());
                    changes.setString(9, target.blockData());
                    changes.setBytes(10, target.expectedEntityData());
                    changes.setBytes(11, target.targetEntityData());
                    changes.setString(12, target.physicalActorUuid());
                    changes.setString(13, target.physicalActorName());
                    changes.setString(14, target.causalPlayerUuid());
                    changes.setString(15, target.causalChainId());
                    changes.setString(16, target.causalCauseType());
                    changes.addBatch();
                    if ((i + 1) % batchSize == 0) {
                        changes.executeBatch();
                    }
                }
                changes.executeBatch();
            }
            try (PreparedStatement reserve = db.prepareStatement("""
                    INSERT INTO rollback_job_chunks(job_id, world_uuid, chunk_x, chunk_z)
                    VALUES (?, ?, ?, ?)
                    """)) {
                for (RollbackJobChunk key : reservations) {
                    reserve.setLong(1, jobId);
                    reserve.setString(2, key.worldUuid());
                    reserve.setInt(3, key.x());
                    reserve.setInt(4, key.z());
                    reserve.addBatch();
                }
                reserve.executeBatch();
            }
            return loadJob(db, jobId);
        }), false);
    }

    CompletableFuture<RollbackJob> loadRollbackJobAsync(long jobId) {
        return submit(db -> loadJob(db, jobId), true);
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
                                nullablePendingAuditId, rows.getBoolean("pending_audit_undo"),
                                rows.getString("world_uuid"), rows.getString("physical_actor_uuid"),
                                rows.getString("physical_actor_name"), rows.getString("causal_player_uuid"),
                                rows.getString("causal_chain_id"), rows.getString("causal_cause_type")));
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
        return submit(databaseConnection -> inTransaction(databaseConnection, () -> {
            RollbackJob job = loadJob(databaseConnection, jobId);
            int abandonedPrepared = 0;
            if (job.status().equals("RUNNING")) {
                try (PreparedStatement deletePendingAudits = databaseConnection.prepareStatement("""
                             DELETE FROM block_changes
                             WHERE rollback_pending = 1
                               AND id IN (
                                   SELECT pending_audit_id
                                   FROM rollback_job_changes
                                   WHERE job_id = ?
                                     AND processed = 0
                                     AND before_data IS NOT NULL
                               )
                             """);
                     PreparedStatement markAbandoned = databaseConnection.prepareStatement("""
                             UPDATE rollback_job_changes
                             SET processed = 1,
                                 applied = 0,
                                 conflicted = 1,
                                 pending_audit_id = NULL,
                                 pending_audit_undo = 0
                             WHERE job_id = ?
                               AND processed = 0
                               AND before_data IS NOT NULL
                             """)) {
                    deletePendingAudits.setLong(1, jobId);
                    deletePendingAudits.executeUpdate();
                    markAbandoned.setLong(1, jobId);
                    abandonedPrepared = markAbandoned.executeUpdate();
                }
            }

            try (PreparedStatement statement = databaseConnection.prepareStatement("""
                    UPDATE rollback_jobs
                    SET status = 'FAILED',
                        updated_at = ?,
                        last_error = ?,
                        processed_blocks = processed_blocks + ?,
                        conflict_blocks = conflict_blocks + ?
                    WHERE id = ?
                    """)) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, reason);
                statement.setInt(3, abandonedPrepared);
                statement.setInt(4, abandonedPrepared);
                statement.setLong(5, jobId);
                statement.executeUpdate();
            }
            return null;
        }), false);
    }

    CompletableFuture<RollbackJob> beginUndoAsync(long jobId) {
        return submit(databaseConnection -> inTransaction(databaseConnection, () -> {
            RollbackJob job = loadJob(databaseConnection, jobId);
            if (!job.status().equals("COMPLETED") && !job.status().equals("FAILED")) {
                throw new IllegalStateException("Rollback job #" + jobId + " cannot be undone while it is " + job.status() + ".");
            }
            rejectOverlappingChunks(databaseConnection, reservedChunks(databaseConnection, job), jobId);
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
        startupStage = "opening SQLite database";
        try (Connection databaseConnection = DriverManager.getConnection(jdbcUrl)) {
            connection = databaseConnection;
            startupStage = "initializing SQLite schema";
            initializeSchema(databaseConnection, loadedWorlds);
            startupStage = "ready";
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
            persistedCoalesceCandidates.clear();
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

        boolean existingSchema = schemaVersion < SCHEMA_VERSION && hasExistingSchema(databaseConnection);
        File migrationBackup = null;
        if (existingSchema && MigrationStoragePolicy.requiresFullBackup(schemaVersion)) {
            migrationBackup = createMigrationBackup(databaseConnection, schemaVersion);
        } else if (existingSchema && schemaVersion >= 3) {
            plugin.getLogger().info("FragGuard schema v3+ migrations only update metadata and indexes; "
                    + "skipping a full database copy.");
        }

        try (Statement statement = databaseConnection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
        }

        int startingVersion = schemaVersion;
        while (schemaVersion < SCHEMA_VERSION) {
            int previousVersion = schemaVersion;
            try {
                // Index-only and metadata changes still need WAL/sort headroom.
                // v4 is checked per missing index below to make retries cheap.
                if (existingSchema && previousVersion != 4) {
                    startupStage = "checking space for SQLite schema v" + previousVersion
                            + " to v" + (previousVersion + 1);
                    preflightIndexMigrationSpace(databaseConnection, previousVersion, "schema step");
                }
            } catch (SQLException exception) {
                throw explainMigrationFailure(previousVersion, exception);
            }
            try {
            if (previousVersion == 3) {
                migrateAttributionCoalescingSchema(databaseConnection);
            } else if (previousVersion == 4) {
                // SQLite index creation can require multi-gigabyte WAL/sort space.
                // Commit each index separately, checkpoint between them, and only
                // advance user_version once all three exist. A failed startup
                // retains v4 and resumes any completed indexes via IF NOT EXISTS.
                migrateHistoricalActorIndexes(databaseConnection);
                inTransaction(databaseConnection, () -> {
                    try (Statement statement = databaseConnection.createStatement()) {
                        statement.execute("PRAGMA user_version=5");
                    }
                    return null;
                });
            } else {
                inTransaction(databaseConnection, () -> {
                    switch (previousVersion) {
                        case 0 -> migrateUnversionedSchema(databaseConnection, loadedWorlds);
                        case 1 -> migrateBlockEntitySchema(databaseConnection);
                        case 2 -> migrateRollbackSafetySchema(databaseConnection);
                        case 5 -> migrateCausalAttributionSchema(databaseConnection);
                        case 6 -> migrateCausalPlayerNameIndex(databaseConnection);
                        case 7 -> migratePlayerRollbackCoordinateIndex(databaseConnection);
                        case 8 -> migratePlayerRollbackJobsSchema(databaseConnection);
                        default -> throw new SQLException("No FragGuard database migration exists for schema version "
                                + previousVersion + ".");
                    }
                    try (Statement statement = databaseConnection.createStatement()) {
                        statement.execute("PRAGMA user_version=" + (previousVersion + 1));
                    }
                    return null;
                });
            }
            } catch (SQLException exception) {
                throw explainMigrationFailure(previousVersion, exception);
            }
            schemaVersion++;
            plugin.getLogger().info("Migrated FragGuard's SQLite schema from version " + previousVersion
                    + " to version " + schemaVersion + ".");
        }

        inTransaction(databaseConnection, () -> {
            refreshRollbackWorldNames(databaseConnection, loadedWorlds);
            return null;
        });
        if (startingVersion < SCHEMA_VERSION && migrationBackup != null) {
            pruneVerifiedMigrationBackups();
        }
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

    private File createMigrationBackup(Connection databaseConnection, int previousVersion) throws SQLException {
        File backupDirectory = new File(plugin.getDataFolder(), "backups");
        if (!backupDirectory.isDirectory() && !backupDirectory.mkdirs()) {
            throw new SQLException("Cannot safely migrate FragGuard's database because the backup directory "
                    + backupDirectory.getAbsolutePath() + " could not be created.");
        }

        long estimatedWork = MigrationStoragePolicy.estimateFullMigrationWorkingBytes(databaseFile.length());
        preflightMigrationSpace(backupDirectory, estimatedWork,
                "full pre-migration backup and schema rewrite from v" + previousVersion + " to v" + SCHEMA_VERSION);

        String prefix = databaseFile.getName() + ".pre-migration-v" + previousVersion
                + "-to-v" + SCHEMA_VERSION + "-" + System.currentTimeMillis();
        File backupFile = new File(backupDirectory, prefix + ".bak");
        for (int attempt = 1; backupFile.exists(); attempt++) {
            backupFile = new File(backupDirectory, prefix + "-" + attempt + ".bak");
        }

        try {
            try (PreparedStatement statement = databaseConnection.prepareStatement("VACUUM INTO ?")) {
                statement.setString(1, backupFile.getAbsolutePath());
                statement.execute();
            }
            verifySqliteFile(backupFile, "pre-migration backup");
        } catch (SQLException exception) {
            String cleanup = cleanupFailedMigrationBackup(backupFile);
            throw new SQLException("Cannot safely migrate FragGuard's database because the SQLite backup at "
                    + backupFile.getAbsolutePath() + " could not be created and verified." + cleanup, exception);
        }

        markVerifiedMigrationBackup(backupFile);
        plugin.getLogger().info("Created and verified FragGuard's pre-migration database backup: "
                + backupFile.getAbsolutePath());
        return backupFile;
    }

    private void verifySqliteFile(File sqliteFile, String description) throws SQLException {
        try (Connection backupConnection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.getAbsolutePath());
             Statement statement = backupConnection.createStatement();
             ResultSet check = statement.executeQuery("PRAGMA quick_check")) {
            if (!check.next() || !"ok".equalsIgnoreCase(check.getString(1))) {
                throw new SQLException("The " + description + " did not pass PRAGMA quick_check.");
            }
        }
    }

    private void verifyOpenDatabase(Connection databaseConnection, String description) throws SQLException {
        try (Statement statement = databaseConnection.createStatement();
             ResultSet check = statement.executeQuery("PRAGMA quick_check")) {
            if (!check.next() || !"ok".equalsIgnoreCase(check.getString(1))) {
                throw new SQLException("FragGuard's database did not pass PRAGMA quick_check " + description + ".");
            }
        }
    }

    private void preflightIndexMigrationSpace(Connection databaseConnection, int fromVersion, String step)
            throws SQLException {
        long pageSize = readMigrationPragma(databaseConnection, "page_size");
        long pageCount = readMigrationPragma(databaseConnection, "page_count");
        long freelist = readMigrationPragma(databaseConnection, "freelist_count");
        long logicalBytes = MigrationStoragePolicy.logicalDatabaseBytes(pageSize, pageCount, freelist);
        long estimate = MigrationStoragePolicy.estimateIndexMigrationWorkingBytes(logicalBytes, fromVersion);
        preflightMigrationSpace(databaseFile.getParentFile(), estimate,
                "SQLite schema v" + fromVersion + " to v" + (fromVersion + 1) + " " + step);
    }

    private long readMigrationPragma(Connection connection, String pragma) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA " + pragma)) {
            if (!rows.next()) {
                throw new SQLException("SQLite did not return PRAGMA " + pragma + " for migration planning.");
            }
            return rows.getLong(1);
        }
    }

    private boolean sqliteIndexExists(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ? LIMIT 1")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private void preflightMigrationSpace(File destination, long estimatedWorkingBytes, String step)
            throws SQLException {
        long safetyMiB = Math.max(0L, plugin.getConfig().getLong(
                "database-migration-space-safety-mib", 256L));
        long required = MigrationStoragePolicy.requiredFreeBytes(estimatedWorkingBytes, safetyMiB);
        long filesystemFree = destination.getUsableSpace();
        // Optional operator-entered *free* capacities, not total plan sizes.
        // 0 means unknown. Managed hosts often hide quotas from File.getUsableSpace.
        long accountFree = MigrationStoragePolicy.mibToBytes(plugin.getConfig().getLong(
                "database-migration-account-free-mib", 0L));
        long sqliteTempFree = MigrationStoragePolicy.mibToBytes(plugin.getConfig().getLong(
                "database-migration-sqlite-temp-free-mib", 0L));

        String dataFreeText = filesystemFree > 0L
                ? MigrationStoragePolicy.formatBytes(filesystemFree) : "unknown";
        String accountText = accountFree > 0L
                ? MigrationStoragePolicy.formatBytes(accountFree) + " (operator-reported)" : "unknown";
        String sqliteTempText = sqliteTempFree > 0L
                ? MigrationStoragePolicy.formatBytes(sqliteTempFree) + " (operator-reported)" : "unknown";
        File javaTemp = new File(System.getProperty("java.io.tmpdir", ""));
        long javaTempHostFree = javaTemp.isDirectory() ? javaTemp.getUsableSpace() : 0L;
        String javaTempText = javaTempHostFree > 0L
                ? MigrationStoragePolicy.formatBytes(javaTempHostFree) + " (advisory only)" : "unknown";

        String description = step + ": database=" + MigrationStoragePolicy.formatBytes(databaseFile.length())
                + ", estimated working space=" + MigrationStoragePolicy.formatBytes(estimatedWorkingBytes)
                + ", safety margin=" + MigrationStoragePolicy.formatBytes(
                        MigrationStoragePolicy.mibToBytes(safetyMiB))
                + ", estimated minimum free=" + MigrationStoragePolicy.formatBytes(required)
                + ", data-filesystem-reported available=" + dataFreeText
                + ", account free=" + accountText + ", SQLite-native-temp free=" + sqliteTempText
                + ", Java-temp-filesystem-reported available=" + javaTempText + ".";

        if (!plugin.getConfig().getBoolean("database-migration-space-preflight-enabled", true)) {
            plugin.getLogger().warning("FragGuard migration preflight was explicitly disabled for "
                    + description + " This does not disable SQLite storage failures or make the migration safe.");
            return;
        }

        try {
            MigrationStoragePolicy.checkCapacity(required, filesystemFree, accountFree, sqliteTempFree);
        } catch (SQLException failure) {
            throw new SQLException("FragGuard migration storage preflight refused " + description + " "
                    + failure.getMessage() + ". No schema changes were made by this preflight. "
                    + "Verify actual hosting account and SQLite native temp quotas; "
                    + "keep verified backups and do not delete database WAL/SHM files.", failure);
        }

        plugin.getLogger().info("FragGuard migration preflight passed for " + description
                + " Host-reported space does NOT prove account/container or native SQLite temp capacity. "
                + "Unknown quotas remain unverified, and SQLite can still fail with SQLITE_FULL.");
    }

    private SQLException explainMigrationFailure(int fromVersion, SQLException failure) {
        if (!MigrationStoragePolicy.isSqliteFull(failure)) {
            return failure;
        }
        String step = startupStage;
        long dataFree = databaseFile.getParentFile().getUsableSpace();
        String dataText = dataFree > 0L ? MigrationStoragePolicy.formatBytes(dataFree) : "unknown";
        String accountText = plugin.getConfig().getLong("database-migration-account-free-mib", 0L) > 0
                ? "operator-provided (may be stale)" : "unknown";
        String message = "FragGuard schema v" + fromVersion + " to v" + (fromVersion + 1)
                + " failed at '" + step + "' with SQLITE_FULL. "
                + "Database=" + MigrationStoragePolicy.formatBytes(databaseFile.length())
                + ", WAL=" + migrationSidecarSize("-wal")
                + ", journal=" + migrationSidecarSize("-journal")
                + ", data filesystem reported free=" + dataText
                + ", account quota=" + accountText
                + ". The failing write location is not proven (database/WAL/SQLite temp/quota). "
                + "Migration stopped without deleting history or WAL files. "
                + "Keep the prior verified backup and ask the host to verify account, native temp, "
                + "inode and file-size limits before retrying.";
        plugin.getLogger().severe(message);
        return new SQLException(message, failure.getSQLState(), failure.getErrorCode(), failure);
    }

    private String migrationSidecarSize(String suffix) {
        File sidecar = new File(databaseFile.getPath() + suffix);
        return sidecar.isFile() ? MigrationStoragePolicy.formatBytes(sidecar.length()) : "absent";
    }

    private String cleanupFailedMigrationBackup(File backupFile) {
        File marker = verifiedMarker(backupFile);
        if (marker.exists() && !marker.delete()) {
            plugin.getLogger().warning("Could not remove migration-backup verification marker: "
                    + marker.getAbsolutePath());
        }
        if (!backupFile.exists() || backupFile.delete()) {
            return " The failed partial backup was removed.";
        }
        return " The failed partial backup remains at " + backupFile.getAbsolutePath()
                + " and must be removed manually before retrying.";
    }

    private void markVerifiedMigrationBackup(File backupFile) {
        File marker = verifiedMarker(backupFile);
        try {
            if (!marker.createNewFile() && !marker.isFile()) {
                plugin.getLogger().warning("Could not create migration-backup verification marker: "
                        + marker.getAbsolutePath());
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not create migration-backup verification marker " + marker.getAbsolutePath()
                            + "; this backup will never be auto-pruned.", exception);
        }
    }

    private File verifiedMarker(File backupFile) {
        return new File(backupFile.getParentFile(), backupFile.getName() + ".verified");
    }

    private void pruneVerifiedMigrationBackups() {
        File backupDirectory = new File(plugin.getDataFolder(), "backups");
        int keep = Math.max(1,
                plugin.getConfig().getInt("database-migration-backup-retention-count", 1));
        File[] markers = backupDirectory.listFiles(file -> file.isFile()
                && file.getName().endsWith(".bak.verified"));
        if (markers == null || markers.length <= keep) {
            return;
        }
        Arrays.sort(markers, Comparator.comparingLong(File::lastModified).reversed());
        for (int index = keep; index < markers.length; index++) {
            File marker = markers[index];
            String backupName = marker.getName().substring(0,
                    marker.getName().length() - ".verified".length());
            File backup = new File(backupDirectory, backupName);
            if (backup.exists() && !backup.delete()) {
                plugin.getLogger().warning("Could not prune verified migration backup: " + backup.getAbsolutePath());
                continue;
            }
            if (!marker.delete()) {
                plugin.getLogger().warning("Pruned migration backup but could not remove verification marker: "
                        + marker.getAbsolutePath());
            } else {
                plugin.getLogger().info("Pruned old verified FragGuard migration backup: "
                        + backup.getAbsolutePath());
            }
        }
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

    private void migrateAttributionCoalescingSchema(Connection databaseConnection) throws SQLException {
        startupStage = "removing obsolete coalescing index for schema v3 to v4";
        verifyOpenDatabase(databaseConnection, "before the index-removal v3->v4 migration");
        inTransaction(databaseConnection, () -> {
            try (Statement statement = databaseConnection.createStatement()) {
                statement.executeUpdate("DROP INDEX IF EXISTS idx_fg_tick_coalesce");
            }
            verifyOpenDatabase(databaseConnection, "after removing the obsolete schema-v3 coalescing index");
            try (Statement statement = databaseConnection.createStatement()) {
                statement.execute("PRAGMA user_version=4");
            }
            return null;
        });
    }

    private void migrateHistoricalActorIndexes(Connection databaseConnection) throws SQLException {
        // An interrupted v4->v5 migration can retain earlier completed indexes
        // while user_version remains 4; each CREATE INDEX is restartable.
        startupStage = "schema v4 to v5 index idx_fg_actor_time";
        if (!sqliteIndexExists(databaseConnection, "idx_fg_actor_time")) {
            preflightIndexMigrationSpace(databaseConnection, 4, "index idx_fg_actor_time");
        }
        HistoricalIndexMigrationDiagnostics.createIndex(databaseConnection, databaseFile, "idx_fg_actor_time",
                HistoricalActorIndexPlan.ACTOR_TIME_SQL,
                plugin.getLogger()::info, plugin.getLogger()::warning);
        startupStage = "checkpointing schema v4 to v5 index idx_fg_actor_time";
        HistoricalActorIndexPlan.checkpointBetweenIndexes(databaseConnection);

        startupStage = "schema v4 to v5 index idx_fg_actor_world_time";
        if (!sqliteIndexExists(databaseConnection, "idx_fg_actor_world_time")) {
            preflightIndexMigrationSpace(databaseConnection, 4, "index idx_fg_actor_world_time");
        }
        HistoricalIndexMigrationDiagnostics.createIndex(databaseConnection, databaseFile, "idx_fg_actor_world_time",
                HistoricalActorIndexPlan.ACTOR_WORLD_TIME_SQL,
                plugin.getLogger()::info, plugin.getLogger()::warning);
        startupStage = "checkpointing schema v4 to v5 index idx_fg_actor_world_time";
        HistoricalActorIndexPlan.checkpointBetweenIndexes(databaseConnection);

        startupStage = "schema v4 to v5 index idx_fg_actor_name_uuid";
        if (!sqliteIndexExists(databaseConnection, "idx_fg_actor_name_uuid")) {
            preflightIndexMigrationSpace(databaseConnection, 4, "index idx_fg_actor_name_uuid");
        }
        HistoricalIndexMigrationDiagnostics.createIndex(databaseConnection, databaseFile, "idx_fg_actor_name_uuid",
                HistoricalActorIndexPlan.ACTOR_NAME_SQL,
                plugin.getLogger()::info, plugin.getLogger()::warning);
        startupStage = "checkpointing schema v4 to v5 index idx_fg_actor_name_uuid";
        HistoricalActorIndexPlan.checkpointBetweenIndexes(databaseConnection);
    }

    private void migrateCausalAttributionSchema(Connection databaseConnection) throws SQLException {
        startupStage = "adding optional causal attribution to historical block records";
        addColumnIfMissing(databaseConnection, "block_changes", "causal_player_uuid", "TEXT");
        addColumnIfMissing(databaseConnection, "block_changes", "causal_player_name", "TEXT");
        addColumnIfMissing(databaseConnection, "block_changes", "causal_chain_id", "TEXT");
        addColumnIfMissing(databaseConnection, "block_changes", "causal_cause_type", "TEXT");
        try (Statement statement = databaseConnection.createStatement()) {
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_fg_causal_actor_time
                    ON block_changes(causal_player_uuid, happened_at DESC, id DESC)
                    WHERE causal_player_uuid IS NOT NULL
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_fg_causal_actor_world_time
                    ON block_changes(causal_player_uuid, world_uuid, happened_at DESC, id DESC)
                    WHERE causal_player_uuid IS NOT NULL
                    """);
        }
    }

    private void migrateCausalPlayerNameIndex(Connection databaseConnection) throws SQLException {
        startupStage = "indexing causal player names for cross-world history lookup";
        try (Statement statement = databaseConnection.createStatement()) {
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_fg_causal_name_uuid
                    ON block_changes(causal_player_name COLLATE NOCASE, causal_player_uuid)
                    WHERE causal_player_uuid IS NOT NULL
                    """);
        }
    }

    /**
     * Coordinate history was previously scanning every record in a world
     * for each selected player-rollback coordinate. This partial index makes
     * newest-first history reconstruction an indexed coordinate/time seek.
     */
    private void migratePlayerRollbackCoordinateIndex(Connection databaseConnection) throws SQLException {
        startupStage = "indexing block coordinates for safe player rollback planning";
        try (Statement statement = databaseConnection.createStatement()) {
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_fg_coordinate_time
                    ON block_changes(y, world_uuid, x, z, happened_at DESC, id DESC)
                    WHERE rollback_pending = 0
                    """);
        }
    }

    /** Shared ownership condition for player lookup/rollback in #101/#102. */
    static String playerOwnershipPredicate() {
        return "((actor_uuid = ? AND " + HistoricalActorIndexPlan.PLAYER_ACTOR_PREDICATE
                + ") OR causal_player_uuid = ?)";
    }

    /**
     * Explicit scope metadata and durable per-world target identity. Existing
     * radius records retain their operator, radius and stored history unchanged.
     */
    private void migratePlayerRollbackJobsSchema(Connection db) throws SQLException {
        startupStage = "adding player rollback job scope, provenance and chunk reservations";
        addColumnIfMissing(db, "rollback_jobs", "scope_type", "TEXT NOT NULL DEFAULT 'RADIUS'");
        addColumnIfMissing(db, "rollback_jobs", "target_player_uuid", "TEXT");
        addColumnIfMissing(db, "rollback_jobs", "target_player_name", "TEXT");
        addColumnIfMissing(db, "rollback_jobs", "world_scope_uuid", "TEXT");
        addColumnIfMissing(db, "rollback_jobs", "world_scope_name", "TEXT");
        addColumnIfMissing(db, "rollback_jobs", "historical_conflicts", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(db, "rollback_jobs", "affected_worlds", "INTEGER NOT NULL DEFAULT 1");
        addColumnIfMissing(db, "rollback_jobs", "affected_chunks", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(db, "rollback_job_changes", "world_uuid", "TEXT");
        addColumnIfMissing(db, "rollback_job_changes", "physical_actor_uuid", "TEXT");
        addColumnIfMissing(db, "rollback_job_changes", "physical_actor_name", "TEXT");
        addColumnIfMissing(db, "rollback_job_changes", "causal_player_uuid", "TEXT");
        addColumnIfMissing(db, "rollback_job_changes", "causal_chain_id", "TEXT");
        addColumnIfMissing(db, "rollback_job_changes", "causal_cause_type", "TEXT");
        try (Statement sql = db.createStatement()) {
            sql.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS rollback_job_chunks (
                        job_id INTEGER NOT NULL,
                        world_uuid TEXT NOT NULL,
                        chunk_x INTEGER NOT NULL,
                        chunk_z INTEGER NOT NULL,
                        PRIMARY KEY(job_id, world_uuid, chunk_x, chunk_z),
                        FOREIGN KEY(job_id) REFERENCES rollback_jobs(id) ON DELETE CASCADE
                    )
                    """);
            sql.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_fg_job_chunk_overlap
                    ON rollback_job_chunks(world_uuid, chunk_x, chunk_z, job_id)
                    """);
            sql.executeUpdate("""
                    UPDATE rollback_job_changes
                    SET world_uuid = (SELECT world_uuid FROM rollback_jobs
                                      WHERE rollback_jobs.id = rollback_job_changes.job_id)
                    WHERE world_uuid IS NULL
                    """);
        }
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
                       AND world_uuid = ?
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
                    operation.cancellationRequested = true;
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
            Map<CoalesceKey, PersistedCoalesceCandidate> candidateChanges = new HashMap<>();
            long persistedCoalesces = inTransaction(connection, () -> {
                long coalesces = 0L;
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE block_changes
                        SET after_data = ?, after_entity_data = ?
                        WHERE id = ?
                          AND actor_uuid = ?
                          AND actor_name = ?
                          AND action = ?
                          AND causal_player_uuid IS ?
                          AND causal_player_name IS ?
                          AND causal_chain_id IS ?
                          AND causal_cause_type IS ?
                          AND after_data = ?
                          AND ((after_entity_data IS NULL AND ? IS NULL) OR after_entity_data = ?)
                        """);
                     PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO block_changes
                        (happened_at, actor_uuid, actor_name, world, x, y, z, action,
                         before_data, after_data, world_uuid, chunk_x, chunk_z, coalesce_session, server_tick,
                         before_entity_data, after_entity_data, causal_player_uuid,
                         causal_player_name, causal_chain_id, causal_cause_type)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                     PreparedStatement deleteNoOp = connection.prepareStatement("""
                        DELETE FROM block_changes
                        WHERE id = ?
                          AND before_data = after_data
                          AND before_entity_data IS after_entity_data
                        """)) {
                    for (PendingBlockChange pending : batch) {
                        BlockChange change = pending.change;
                        if (sameState(change.beforeData(), change.beforeEntityData(),
                                change.afterData(), change.afterEntityData())) {
                            continue;
                        }

                        PersistedCoalesceCandidate candidate = candidateChanges.containsKey(pending.key)
                                ? candidateChanges.get(pending.key)
                                : persistedCoalesceCandidates.get(pending.key);
                        if (candidate != null && canCoalesce(candidate, change)) {
                            update.setString(1, change.afterData());
                            update.setBytes(2, change.afterEntityData());
                            update.setLong(3, candidate.rowId());
                            update.setString(4, candidate.actorUuid());
                            update.setString(5, candidate.actorName());
                            update.setString(6, candidate.action().storageId());
                            update.setString(7, candidate.causalPlayerUuid());
                            update.setString(8, candidate.causalPlayerName());
                            update.setString(9, candidate.causalChainId());
                            update.setString(10, candidate.causalCauseType());
                            update.setString(11, candidate.afterData());
                            update.setBytes(12, candidate.afterEntityData());
                            update.setBytes(13, candidate.afterEntityData());
                            if (update.executeUpdate() == 1) {
                                coalesces++;
                                deleteNoOp.setLong(1, candidate.rowId());
                                if (deleteNoOp.executeUpdate() == 1) {
                                    candidateChanges.put(pending.key, null);
                                } else {
                                    candidateChanges.put(pending.key,
                                            persistedCandidate(candidate.rowId(), change));
                                }
                                continue;
                            }
                        }

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
                        insert.setString(11, pending.worldUuid);
                        insert.setInt(12, change.x() >> 4);
                        insert.setInt(13, change.z() >> 4);
                        insert.setString(14, coalesceSession);
                        insert.setLong(15, pending.key.tick());
                        insert.setBytes(16, change.beforeEntityData());
                        insert.setBytes(17, change.afterEntityData());
                        insert.setString(18, change.causalPlayerUuid());
                        insert.setString(19, change.causalPlayerName());
                        insert.setString(20, change.causalChainId());
                        insert.setString(21, change.causalCauseType());
                        insert.executeUpdate();
                        try (ResultSet keys = insert.getGeneratedKeys()) {
                            if (!keys.next()) {
                                throw new SQLException("SQLite did not return a block-change row ID for coalescing.");
                            }
                            candidateChanges.put(pending.key,
                                    persistedCandidate(keys.getLong(1), change));
                        }
                    }
                }
                return coalesces;
            });
            applyPersistedCoalesceCandidateChanges(candidateChanges);
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
            if (exception instanceof SQLException sqlException
                    && !isExpectedTimedQueryCancellation(operation.timedQuery,
                    operation.cancellationRequested, sqlException)) {
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
                SELECT happened_at, actor_name, world, x, y, z, action, before_data, after_data,
                       before_entity_data, after_entity_data
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
                            resultSet.getString("before_data"), resultSet.getString("after_data"),
                            resultSet.getBytes("before_entity_data"), resultSet.getBytes("after_entity_data")));
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

    private void rejectOverlappingChunks(Connection db, Set<RollbackJobChunk> requested,
                                          long excludedJobId) throws SQLException {
        if (requested.isEmpty()) {
            return;
        }
        try (PreparedStatement active = db.prepareStatement("""
                SELECT * FROM rollback_jobs
                WHERE status IN ('RUNNING', 'UNDOING') AND id <> ?
                """)) {
            active.setLong(1, excludedJobId);
            try (ResultSet jobs = active.executeQuery()) {
                while (jobs.next()) {
                    RollbackJob job = readJob(jobs);
                    Set<RollbackJobChunk> occupied = reservedChunks(db, job);
                    for (RollbackJobChunk key : requested) {
                        if (occupied.contains(key)) {
                            throw new IllegalStateException("Rollback job #" + job.id()
                                    + " is already running in an overlapping region.");
                        }
                    }
                }
            }
        }
    }

    private Set<RollbackJobChunk> reservedChunks(Connection db, RollbackJob job) throws SQLException {
        if (!job.isPlayerScope()) {
            return RollbackJobChunk.radius(job.worldUuid(), job.centerX(), job.centerZ(), job.radius());
        }
        Set<RollbackJobChunk> keys = new HashSet<>();
        try (PreparedStatement statement = db.prepareStatement("""
                SELECT world_uuid, chunk_x, chunk_z FROM rollback_job_chunks
                WHERE job_id = ?
                """)) {
            statement.setLong(1, job.id());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    keys.add(new RollbackJobChunk(rows.getString(1), rows.getInt(2), rows.getInt(3)));
                }
            }
        }
        if (keys.isEmpty() && job.totalBlocks() > 0) {
            throw new IllegalStateException("Player rollback job #" + job.id()
                    + " lacks durable chunk reservations; refusing overlapping work.");
        }
        return keys;
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
                rows.getInt("undo_processed_blocks"), rows.getString("last_error"),
                rows.getString("scope_type"), rows.getString("target_player_uuid"),
                rows.getString("target_player_name"), rows.getString("world_scope_uuid"),
                rows.getString("world_scope_name"), rows.getInt("historical_conflicts"),
                rows.getInt("affected_worlds"), rows.getInt("affected_chunks"));
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
            try {
                databaseConnection.rollback();
            } catch (SQLException rollbackFailure) {
                // Preserve the root SQL failure (e.g. SQLITE_FULL) instead of
                // allowing a second rollback error to hide its cause.
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        } finally {
            databaseConnection.setAutoCommit(previousAutoCommit);
        }
    }

    static boolean isExpectedTimedQueryCancellation(boolean timedQuery, boolean cancellationRequested,
                                                    SQLException exception) {
        if (!timedQuery) {
            return false;
        }
        if (cancellationRequested || exception instanceof SQLTimeoutException) {
            return true;
        }

        for (SQLException current = exception; current != null; current = current.getNextException()) {
            if (current instanceof SQLTimeoutException || current.getErrorCode() == 9) {
                return true;
            }
        }
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLTimeoutException) {
                return true;
            }
            if (cause instanceof SQLException sqlCause && sqlCause.getErrorCode() == 9) {
                return true;
            }
        }
        return false;
    }

    private static boolean canCoalesce(BlockChange previous, BlockChange latest) {
        return Objects.equals(previous.actorUuid(), latest.actorUuid())
                && Objects.equals(previous.actorName(), latest.actorName())
                && previous.action() == latest.action()
                && sameCausalAttribution(previous.causalPlayerUuid(), previous.causalPlayerName(),
                        previous.causalChainId(), previous.causalCauseType(), latest)
                && sameState(previous.afterData(), previous.afterEntityData(),
                        latest.beforeData(), latest.beforeEntityData());
    }

    private static boolean canCoalesce(PersistedCoalesceCandidate previous, BlockChange latest) {
        return Objects.equals(previous.actorUuid(), latest.actorUuid())
                && Objects.equals(previous.actorName(), latest.actorName())
                && previous.action() == latest.action()
                && sameCausalAttribution(previous.causalPlayerUuid(), previous.causalPlayerName(),
                        previous.causalChainId(), previous.causalCauseType(), latest)
                && sameState(previous.afterData(), previous.afterEntityData(),
                        latest.beforeData(), latest.beforeEntityData());
    }

    private static boolean sameCausalAttribution(String uuid, String name, String chain,
                                                  String cause, BlockChange change) {
        return Objects.equals(uuid, change.causalPlayerUuid())
                && Objects.equals(name, change.causalPlayerName())
                && Objects.equals(chain, change.causalChainId())
                && Objects.equals(cause, change.causalCauseType());
    }

    private static PersistedCoalesceCandidate persistedCandidate(long rowId, BlockChange change) {
        byte[] entityData = change.afterEntityData() == null
                ? null
                : Arrays.copyOf(change.afterEntityData(), change.afterEntityData().length);
        return new PersistedCoalesceCandidate(rowId, change.actorUuid(), change.actorName(), change.action(),
                change.causalPlayerUuid(), change.causalPlayerName(), change.causalChainId(),
                change.causalCauseType(), change.afterData(), entityData);
    }

    private void applyPersistedCoalesceCandidateChanges(
            Map<CoalesceKey, PersistedCoalesceCandidate> candidateChanges
    ) {
        for (Map.Entry<CoalesceKey, PersistedCoalesceCandidate> entry : candidateChanges.entrySet()) {
            persistedCoalesceCandidates.remove(entry.getKey());
            if (entry.getValue() != null) {
                persistedCoalesceCandidates.put(entry.getKey(), entry.getValue());
            }
        }
        if (persistedCoalesceCandidates.isEmpty()) {
            return;
        }

        long newestTick = persistedCoalesceCandidates.keySet().stream()
                .mapToLong(CoalesceKey::tick)
                .max()
                .orElse(Long.MIN_VALUE);
        long cutoff = newestTick <= Long.MIN_VALUE + PERSISTED_COALESCE_TICK_RETENTION
                ? Long.MIN_VALUE
                : newestTick - PERSISTED_COALESCE_TICK_RETENTION;
        persistedCoalesceCandidates.entrySet().removeIf(entry -> entry.getKey().tick() < cutoff);

        while (persistedCoalesceCandidates.size() > persistedCoalesceCapacity) {
            var iterator = persistedCoalesceCandidates.entrySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private BlockChange merge(BlockChange previous, BlockChange latest, long serverTick) {
        return new BlockChange(previous.happenedAt(), serverTick, latest.actorUuid(), latest.actorName(), previous.worldName(),
                previous.x(), previous.y(), previous.z(), latest.action(), previous.beforeData(), latest.afterData(),
                previous.beforeEntityData(), latest.afterEntityData(),
                latest.causalPlayerUuid(), latest.causalPlayerName(),
                latest.causalChainId(), latest.causalCauseType());
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

    private record PersistedCoalesceCandidate(
            long rowId,
            String actorUuid,
            String actorName,
            ChangeAction action,
            String causalPlayerUuid,
            String causalPlayerName,
            String causalChainId,
            String causalCauseType,
            String afterData,
            byte[] afterEntityData
    ) {
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
        private volatile boolean cancellationRequested;

        private DatabaseOperation(long writeBarrier, SqlOperation<T> work,
                                  CompletableFuture<T> future, boolean timedQuery) {
            this.writeBarrier = writeBarrier;
            this.work = work;
            this.future = future;
            this.timedQuery = timedQuery;
        }
    }
}
