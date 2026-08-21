package org.pinnaclesmp.fragguard;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseTest {
    private static final UUID WORLD_UUID = UUID.fromString("563fce36-6445-43e9-9e79-3bb6d0780b13");
    private static final UUID ACTOR_UUID = UUID.fromString("714ea63f-075e-4694-b2c4-ae06a79748aa");

    @TempDir
    Path temporaryDirectory;

    private final AtomicInteger currentTick = new AtomicInteger(100);
    private Database database;

    @AfterEach
    void shutDownDatabase() {
        if (database != null) {
            database.shutdown();
        }
    }

    @Test
    void batchesWritesCoalescesSameServerTickAcrossWallClockBucketsAndReadsAcceptedWrites() throws Exception {
        database = startDatabase();
        long timestamp = ((System.currentTimeMillis() + 1_000) / 50L) * 50L + 10L;
        database.insertAsync(change(timestamp, 400L, -17, 70, -1, "minecraft:stone", "minecraft:dirt"));
        database.insertAsync(change(timestamp + 75L, 400L, -17, 70, -1, "minecraft:dirt", "minecraft:grass_block"));

        LookupPage page = database.lookupAsync("world", -17, -1, 1, 1, 15, 30).join();

        assertEquals(1, page.totalRows());
        assertEquals("minecraft:stone", page.rows().get(0).beforeData());
        assertEquals("minecraft:grass_block", page.rows().get(0).afterData());
        assertEquals(1, database.health().coalescedWrites());

        try (Connection connection = openDatabase();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT world_uuid, chunk_x, chunk_z, coalesce_session, server_tick FROM block_changes")) {
            assertTrue(row.next());
            assertEquals(WORLD_UUID.toString(), row.getString("world_uuid"));
            assertEquals(-2, row.getInt("chunk_x"));
            assertEquals(-1, row.getInt("chunk_z"));
            assertNotNull(row.getString("coalesce_session"));
            assertEquals(400L, row.getLong("server_tick"));
        }
    }

    @Test
    void doesNotCoalesceDistinctServerTicksInsideSameWallClockBucket() throws Exception {
        database = startDatabase();
        long timestamp = ((System.currentTimeMillis() + 1_000) / 50L) * 50L + 10L;
        database.insertAsync(change(timestamp, 500L, 3, 64, 3, "minecraft:stone", "minecraft:dirt"));
        database.insertAsync(change(timestamp + 1L, 501L, 3, 64, 3, "minecraft:dirt", "minecraft:grass_block"));

        LookupPage page = database.lookupAsync("world", 3, 3, 1, 1, 15, 30).join();

        assertEquals(2, page.totalRows());
        assertEquals("minecraft:grass_block", page.rows().get(0).afterData());
        assertEquals("minecraft:dirt", page.rows().get(1).afterData());
        assertEquals(0, database.health().coalescedWrites());
    }

    @Test
    void drainsPressureBatchesWithoutWaitingForServerTickToAdvance() throws Exception {
        database = startDatabase();
        long timestamp = System.currentTimeMillis();

        for (int batch = 0; batch < 5; batch++) {
            for (int offset = 0; offset < 16; offset++) {
                int x = batch * 16 + offset;
                database.insertAsync(change(timestamp + x, 800L, x, 64, 0,
                        "minecraft:stone", "minecraft:air"));
            }
            waitForWriteQueueToDrain();
        }

        assertEquals(100, currentTick.get(), "the test must not advance the server tick");
        assertEquals(0, database.health().droppedWrites());
        LookupPage page = database.lookupAsync("world", 40, 0, 100, 1, 100, 30).join();
        assertEquals(80, page.totalRows());
    }

    @Test
    void coalescesSameTickAcrossSeparateDatabaseFlushes() throws Exception {
        database = startDatabase();
        long timestamp = System.currentTimeMillis();

        database.insertAsync(change(timestamp, 900L, 7, 64, 7,
                "minecraft:stone", "minecraft:dirt"));
        database.lookupAsync("world", 7, 7, 1, 1, 15, 30).join();
        assertEquals(0, database.health().coalescedWrites());

        database.insertAsync(change(timestamp + 1L, 900L, 7, 64, 7,
                "minecraft:dirt", "minecraft:grass_block"));
        LookupPage page = database.lookupAsync("world", 7, 7, 1, 1, 15, 30).join();

        assertEquals(1, page.totalRows());
        assertEquals("minecraft:stone", page.rows().get(0).beforeData());
        assertEquals("minecraft:grass_block", page.rows().get(0).afterData());
        assertEquals(1, database.health().coalescedWrites(),
                "SQLite cross-flush upserts must contribute to /fg status coalesce metrics");
    }

    @Test
    void removesSameTickNetNoOpAcrossSeparateDatabaseFlushes() throws Exception {
        database = startDatabase();
        long timestamp = System.currentTimeMillis();

        database.insertAsync(change(timestamp, 901L, 8, 64, 8,
                "minecraft:stone", "minecraft:dirt"));
        database.lookupAsync("world", 8, 8, 1, 1, 15, 30).join();

        database.insertAsync(change(timestamp + 1L, 901L, 8, 64, 8,
                "minecraft:dirt", "minecraft:stone"));
        LookupPage page = database.lookupAsync("world", 8, 8, 1, 1, 15, 30).join();

        assertEquals(0, page.totalRows());
        assertEquals(1, database.health().coalescedWrites());
    }

    @Test
    void doesNotCoalesceMatchingTickNumbersAcrossServerSessions() throws Exception {
        long timestamp = System.currentTimeMillis();
        database = startDatabase();
        database.insertAsync(change(timestamp, 42L, 9, 64, 9,
                "minecraft:stone", "minecraft:dirt"));
        database.lookupAsync("world", 9, 9, 1, 1, 15, 30).join();
        database.shutdown();
        database = null;

        database = startDatabase();
        database.insertAsync(change(timestamp + 1L, 42L, 9, 64, 9,
                "minecraft:dirt", "minecraft:grass_block"));
        LookupPage page = database.lookupAsync("world", 9, 9, 1, 1, 15, 30).join();

        assertEquals(2, page.totalRows());
        assertEquals(0, database.health().coalescedWrites());
    }

    @Test
    void rollbackQuerySelectsEarliestChangeAndEnforcesLimitInSql() throws Exception {
        database = startDatabase();
        long timestamp = System.currentTimeMillis();
        database.insertAsync(change(timestamp - 2_000, 600L, 0, 64, 0, "minecraft:stone", "minecraft:dirt"));
        database.insertAsync(change(timestamp - 1_000, 601L, 0, 64, 0, "minecraft:dirt", "minecraft:gold_block"));
        database.insertAsync(change(timestamp - 900, 602L, 1, 64, 0, "minecraft:oak_log", "minecraft:air"));
        database.insertAsync(change(timestamp - 800, 603L, 2, 64, 0, "minecraft:diamond_block", "minecraft:air"));
        database.insertAsync(change(timestamp - 700, 604L, 5, 64, 5, "minecraft:outside", "minecraft:air"));

        List<RollbackTarget> targets = database.rollbackTargetsAsync("world", 0, 0, 3,
                timestamp - 3_000, timestamp, 2).join();

        assertEquals(3, targets.size(), "SQLite should return only maxBlocks + 1 distinct coordinates");
        assertEquals("minecraft:stone", targets.get(0).blockData());
        assertEquals("minecraft:gold_block", targets.get(0).expectedData());
        assertEquals(List.of(0, 1, 2), targets.stream().map(RollbackTarget::x).toList());
    }

    @Test
    void rollbackSnapshotExcludesLaterChangesAndCapturesExpectedState() throws Exception {
        database = startDatabase();
        long now = System.currentTimeMillis();
        long targetTimestamp = now - 5_000L;
        long snapshotTimestamp = now - 2_000L;

        database.insertRequiredAsync(List.of(
                change(targetTimestamp + 1_000L, 610L, 0, 64, 0,
                        "minecraft:stone", "minecraft:dirt"),
                change(targetTimestamp + 2_000L, 611L, 0, 64, 0,
                        "minecraft:dirt", "minecraft:gold_block"),
                change(snapshotTimestamp + 500L, 612L, 0, 64, 0,
                        "minecraft:gold_block", "minecraft:diamond_block"),
                change(snapshotTimestamp + 600L, 613L, 1, 64, 0,
                        "minecraft:oak_log", "minecraft:air")
        )).join();

        List<RollbackTarget> targets = database.rollbackTargetsAsync("world", 0, 0, 3,
                targetTimestamp, snapshotTimestamp, 10).join();

        assertEquals(1, targets.size(), "post-snapshot-only coordinates must not enter the rollback plan");
        RollbackTarget target = targets.get(0);
        assertEquals(0, target.x());
        assertEquals("minecraft:stone", target.blockData());
        assertEquals("minecraft:gold_block", target.expectedData(),
                "expected state must be the latest state at the snapshot boundary");
    }

    @Test
    void migratesExistingRowsIncludingNegativeChunkCoordinates() throws Exception {
        long timestamp = System.currentTimeMillis();
        try (Connection connection = openDatabase(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE block_changes (
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
                        after_data TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("INSERT INTO block_changes VALUES (1, " + timestamp
                    + ", 'actor', 'Builder', 'world', -17, 64, -33, 'BREAK', 'minecraft:stone', 'minecraft:air')");
        }

        database = startDatabase();

        List<RollbackTarget> targets = database.rollbackTargetsAsync("world", -17, -33, 1,
                timestamp - 1, timestamp, 10).join();
        assertEquals(1, targets.size());
        try (Connection connection = openDatabase();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT world_uuid, chunk_x, chunk_z, coalesce_session, server_tick FROM block_changes")) {
            assertTrue(row.next());
            assertEquals("world", row.getString("world_uuid"));
            assertEquals(-2, row.getInt("chunk_x"));
            assertEquals(-3, row.getInt("chunk_z"));
            assertNull(row.getString("coalesce_session"));
            assertNull(row.getObject("server_tick"));
        }
    }

    @Test
    void persistsRollbackProgressRejectsOverlapAndSupportsUndo() throws Exception {
        database = startDatabase();
        List<RollbackTarget> targets = List.of(
                new RollbackTarget("world", 4, 70, 4, "minecraft:stone", "minecraft:air"),
                new RollbackTarget("world", 5, 70, 4, "minecraft:oak_log", "minecraft:air")
        );
        long snapshotTimestamp = System.currentTimeMillis();
        RollbackJob job = database.createRollbackJobAsync(ACTOR_UUID.toString(), "Builder", "world",
                4, 4, 10, snapshotTimestamp - 1_000, snapshotTimestamp, false, targets).join();

        assertEquals("RUNNING", job.status());
        assertEquals(snapshotTimestamp, job.snapshotTimestamp());
        assertFalse(job.force());
        assertEquals(1, database.loadResumableJobsAsync().join().size());
        CompletionException overlap = assertThrows(CompletionException.class, () ->
                database.createRollbackJobAsync(ACTOR_UUID.toString(), "Builder", "world",
                        6, 6, 10, snapshotTimestamp - 500, snapshotTimestamp, false, targets).join());
        assertTrue(overlap.getCause().getMessage().contains("overlapping region"));

        List<RollbackJobChange> changes = database.loadRollbackChangesAsync(job.id(), false).join();
        assertTrue(changes.stream().allMatch(change -> "minecraft:air".equals(change.expectedData())));
        List<RollbackJobChange> prepared = changes.stream()
                .map(change -> change.withBeforeData("minecraft:air"))
                .toList();
        database.prepareRollbackBatchAsync(job.id(), prepared).join();
        database.markRollbackBatchAppliedAsync(job.id(), List.of(
                new RollbackStepResult(changes.get(0).sequence(), true, false),
                new RollbackStepResult(changes.get(1).sequence(), false, true)
        )).join();
        RollbackJob completed = database.completeRollbackJobAsync(job.id(), false).join();
        assertEquals(1, completed.conflictBlocks());

        RollbackJob undo = database.beginUndoAsync(job.id()).join();
        assertEquals("UNDOING", undo.status());
        assertEquals(2, undo.processedBlocks());
        assertEquals(1, undo.appliedBlocks());
        List<RollbackJobChange> undoChanges = database.loadRollbackChangesAsync(job.id(), true).join();
        assertEquals(List.of(0), undoChanges.stream().map(RollbackJobChange::sequence).toList(),
                "undo must ignore conflict-skipped or otherwise unapplied rollback coordinates");
        assertTrue(undoChanges.stream().allMatch(change -> change.beforeData().equals("minecraft:air")));

        database.markUndoBatchAppliedAsync(job.id(), undoChanges.stream()
                .map(change -> new RollbackStepResult(change.sequence(), true))
                .toList()).join();
        RollbackJob undone = database.completeRollbackJobAsync(job.id(), true).join();
        assertEquals("UNDONE", undone.status());
        assertTrue(database.loadResumableJobsAsync().join().isEmpty());
        assertTrue(database.health().healthy());
    }

    @Test
    void preservesInterleavedChunkSequenceForRollbackAndReverseUndo() throws Exception {
        database = startDatabase();
        long snapshotTimestamp = System.currentTimeMillis();
        List<RollbackTarget> targets = List.of(
                new RollbackTarget("world", 15, 70, 0, "minecraft:stone", "minecraft:air"),
                new RollbackTarget("world", 16, 70, 0, "minecraft:oak_log", "minecraft:air"),
                new RollbackTarget("world", 14, 70, 0, "minecraft:oak_planks", "minecraft:air")
        );
        RollbackJob job = database.createRollbackJobAsync(ACTOR_UUID.toString(), "Builder", "world",
                15, 0, 10, snapshotTimestamp - 1_000, snapshotTimestamp, false, targets).join();

        RollbackChunkPlan.Plan rollback = RollbackChunkPlan.group(
                database.loadRollbackChangesAsync(job.id(), false).join());
        assertEquals(2, rollback.chunkCount());
        assertEquals(List.of(0, 1, 2),
                rollback.changes().stream().map(RollbackJobChange::sequence).toList(),
                "revisiting the first chunk must not move its later coordinate ahead of the boundary block");

        List<RollbackJobChange> prepared = rollback.changes().stream()
                .map(change -> change.withBeforeData("minecraft:air"))
                .toList();
        database.prepareRollbackBatchAsync(job.id(), prepared).join();
        database.markRollbackBatchAppliedAsync(job.id(), rollback.changes().stream()
                .map(change -> new RollbackStepResult(change.sequence(), true, false))
                .toList()).join();
        database.completeRollbackJobAsync(job.id(), false).join();
        database.beginUndoAsync(job.id()).join();

        RollbackChunkPlan.Plan undo = RollbackChunkPlan.group(
                database.loadRollbackChangesAsync(job.id(), true).join());
        assertEquals(2, undo.chunkCount());
        assertEquals(List.of(2, 1, 0),
                undo.changes().stream().map(RollbackJobChange::sequence).toList(),
                "undo must preserve the exact reverse rollback order for physics across chunk boundaries");
    }

    @Test
    void preservesPreparedCrashWindowMutationForUndoAfterResumeMarksItUnchanged() throws Exception {
        database = startDatabase();
        long snapshotTimestamp = System.currentTimeMillis();
        RollbackJob job = database.createRollbackJobAsync(ACTOR_UUID.toString(), "Builder", "world",
                4, 4, 10, snapshotTimestamp - 1_000, snapshotTimestamp, false,
                List.of(new RollbackTarget("world", 4, 70, 4,
                        "minecraft:stone", "minecraft:dirt"))).join();

        RollbackJobChange change = database.loadRollbackChangesAsync(job.id(), false).join().get(0);
        database.prepareRollbackBatchAsync(job.id(),
                List.of(change.withBeforeData("minecraft:dirt"))).join();

        database.markRollbackBatchAppliedAsync(job.id(),
                List.of(new RollbackStepResult(change.sequence(), false, false))).join();
        database.completeRollbackJobAsync(job.id(), false).join();

        RollbackJob completed = database.beginUndoAsync(job.id()).join();
        assertEquals("UNDOING", completed.status());
        List<RollbackJobChange> undoChanges = database.loadRollbackChangesAsync(job.id(), true).join();
        assertEquals(1, undoChanges.size(),
                "a prepared non-conflicted mutation must remain undoable even if applied=1 was never committed");
        RollbackJobChange recovered = undoChanges.get(0);
        assertEquals("minecraft:dirt", recovered.beforeData());
        assertEquals("minecraft:stone", recovered.targetData());
        assertFalse(recovered.applied(),
                "the regression case deliberately simulates the missing applied marker");
        assertFalse(recovered.conflicted());
    }

    @Test
    void undoConflictRemainsRetryableUntilItIsResolved() throws Exception {
        database = startDatabase();
        long snapshotTimestamp = System.currentTimeMillis();
        RollbackJob job = database.createRollbackJobAsync(ACTOR_UUID.toString(), "Builder", "world",
                4, 4, 10, snapshotTimestamp - 1_000, snapshotTimestamp, false,
                List.of(new RollbackTarget("world", 4, 70, 4,
                        "minecraft:stone", "minecraft:dirt"))).join();

        RollbackJobChange change = database.loadRollbackChangesAsync(job.id(), false).join().get(0);
        database.prepareRollbackBatchAsync(job.id(),
                List.of(change.withBeforeData("minecraft:dirt"))).join();
        database.markRollbackBatchAppliedAsync(job.id(),
                List.of(new RollbackStepResult(change.sequence(), true, false))).join();
        database.completeRollbackJobAsync(job.id(), false).join();

        database.beginUndoAsync(job.id()).join();
        RollbackJobChange undoChange = database.loadRollbackChangesAsync(job.id(), true).join().get(0);
        database.markUndoBatchAppliedAsync(job.id(),
                List.of(new RollbackStepResult(undoChange.sequence(), false, true))).join();

        RollbackJob incomplete = database.completeRollbackJobAsync(job.id(), true).join();
        assertEquals("FAILED", incomplete.status(),
                "an undo conflict must keep the job retryable instead of finalizing it as UNDONE");
        assertTrue(incomplete.lastError().contains("1 unresolved conflict"));
        assertEquals(0, incomplete.undoProcessedBlocks(),
                "conflicted undo coordinates must not count as resolved progress");
        assertEquals(1, database.loadRollbackChangesAsync(job.id(), true).join().size(),
                "the unresolved coordinate must remain eligible for another undo attempt");

        RollbackJob retry = database.beginUndoAsync(job.id()).join();
        assertEquals("UNDOING", retry.status());
        RollbackJobChange retryChange = database.loadRollbackChangesAsync(job.id(), true).join().get(0);
        database.markUndoBatchAppliedAsync(job.id(),
                List.of(new RollbackStepResult(retryChange.sequence(), true, false))).join();
        RollbackJob completed = database.completeRollbackJobAsync(job.id(), true).join();
        assertEquals("UNDONE", completed.status());
        assertEquals(1, completed.undoProcessedBlocks());
        assertTrue(database.loadRollbackChangesAsync(job.id(), true).join().isEmpty());
    }

    private Database startDatabase() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("database-write-queue-capacity", 64);
        configuration.set("database-write-batch-size", 16);
        configuration.set("database-query-timeout-seconds", 5);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getConfig()).thenReturn(configuration);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("FragGuardTest"));
        when(server.getCurrentTick()).thenAnswer(invocation -> currentTick.get());
        when(server.getWorld("world")).thenReturn(world);
        when(world.getUID()).thenReturn(WORLD_UUID);

        Database instance = new Database(plugin);
        instance.init();
        return instance;
    }

    private void waitForWriteQueueToDrain() throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (database.health().queuedWrites() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertEquals(0, database.health().queuedWrites(),
                "write queue should drain under pressure without waiting for the server tick to advance");
    }

    private Connection openDatabase() throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + temporaryDirectory.resolve("fragguard.db"));
    }

    private BlockChange change(long timestamp, long serverTick, int x, int y, int z, String before, String after) {
        return new BlockChange(timestamp, serverTick, ACTOR_UUID.toString(), "Builder", "world", x, y, z,
                ChangeAction.BREAK, before, after);
    }
}
