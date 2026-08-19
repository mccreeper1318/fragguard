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
             ResultSet row = statement.executeQuery("SELECT world_uuid, chunk_x, chunk_z FROM block_changes")) {
            assertTrue(row.next());
            assertEquals(WORLD_UUID.toString(), row.getString("world_uuid"));
            assertEquals(-2, row.getInt("chunk_x"));
            assertEquals(-1, row.getInt("chunk_z"));
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
    void rollbackQuerySelectsEarliestChangeAndEnforcesLimitInSql() throws Exception {
        database = startDatabase();
        long timestamp = System.currentTimeMillis();
        database.insertAsync(change(timestamp - 2_000, 600L, 0, 64, 0, "minecraft:stone", "minecraft:dirt"));
        database.insertAsync(change(timestamp - 1_000, 601L, 0, 64, 0, "minecraft:dirt", "minecraft:gold_block"));
        database.insertAsync(change(timestamp - 900, 602L, 1, 64, 0, "minecraft:oak_log", "minecraft:air"));
        database.insertAsync(change(timestamp - 800, 603L, 2, 64, 0, "minecraft:diamond_block", "minecraft:air"));
        database.insertAsync(change(timestamp - 700, 604L, 5, 64, 5, "minecraft:outside", "minecraft:air"));

        List<RollbackTarget> targets = database.rollbackTargetsAsync("world", 0, 0, 3,
                timestamp - 3_000, 2).join();

        assertEquals(3, targets.size(), "SQLite should return only maxBlocks + 1 distinct coordinates");
        assertEquals("minecraft:stone", targets.get(0).blockData());
        assertEquals(List.of(0, 1, 2), targets.stream().map(RollbackTarget::x).toList());
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
                timestamp - 1, 10).join();
        assertEquals(1, targets.size());
        try (Connection connection = openDatabase();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT world_uuid, chunk_x, chunk_z FROM block_changes")) {
            assertTrue(row.next());
            assertEquals("world", row.getString("world_uuid"));
            assertEquals(-2, row.getInt("chunk_x"));
            assertEquals(-3, row.getInt("chunk_z"));
        }
    }

    @Test
    void persistsRollbackProgressRejectsOverlapAndSupportsUndo() throws Exception {
        database = startDatabase();
        List<RollbackTarget> targets = List.of(
                new RollbackTarget("world", 4, 70, 4, "minecraft:stone"),
                new RollbackTarget("world", 5, 70, 4, "minecraft:oak_log")
        );
        RollbackJob job = database.createRollbackJobAsync(ACTOR_UUID.toString(), "Builder", "world",
                4, 4, 10, System.currentTimeMillis() - 1_000, targets).join();

        assertEquals("RUNNING", job.status());
        assertEquals(1, database.loadResumableJobsAsync().join().size());
        CompletionException overlap = assertThrows(CompletionException.class, () ->
                database.createRollbackJobAsync(ACTOR_UUID.toString(), "Builder", "world",
                        6, 6, 10, System.currentTimeMillis(), targets).join());
        assertTrue(overlap.getCause().getMessage().contains("overlapping region"));

        List<RollbackJobChange> changes = database.loadRollbackChangesAsync(job.id(), false).join();
        List<RollbackJobChange> prepared = changes.stream()
                .map(change -> change.withBeforeData("minecraft:air"))
                .toList();
        database.prepareRollbackBatchAsync(job.id(), prepared).join();
        database.markRollbackBatchAppliedAsync(job.id(), List.of(
                new RollbackStepResult(changes.get(0).sequence(), true),
                new RollbackStepResult(changes.get(1).sequence(), false)
        )).join();
        database.completeRollbackJobAsync(job.id(), false).join();

        RollbackJob undo = database.beginUndoAsync(job.id()).join();
        assertEquals("UNDOING", undo.status());
        assertEquals(2, undo.processedBlocks());
        assertEquals(1, undo.appliedBlocks());
        List<RollbackJobChange> undoChanges = database.loadRollbackChangesAsync(job.id(), true).join();
        assertEquals(List.of(1, 0), undoChanges.stream().map(RollbackJobChange::sequence).toList());
        assertTrue(undoChanges.stream().allMatch(change -> change.beforeData().equals("minecraft:air")));

        database.markUndoBatchAppliedAsync(job.id(), undoChanges.stream()
                .map(change -> new RollbackStepResult(change.sequence(), true))
                .toList()).join();
        database.completeRollbackJobAsync(job.id(), true).join();
        assertTrue(database.loadResumableJobsAsync().join().isEmpty());
        assertTrue(database.health().healthy());
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

    private Connection openDatabase() throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + temporaryDirectory.resolve("fragguard.db"));
    }

    private BlockChange change(long timestamp, long serverTick, int x, int y, int z, String before, String after) {
        return new BlockChange(timestamp, serverTick, ACTOR_UUID.toString(), "Builder", "world", x, y, z,
                ChangeAction.BREAK, before, after);
    }
}
