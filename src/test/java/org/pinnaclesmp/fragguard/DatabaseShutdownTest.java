package org.pinnaclesmp.fragguard;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseShutdownTest {
    private static final UUID WORLD_UUID = UUID.fromString("cfe4267e-3c23-4c5d-818d-7fc97e3057aa");
    private static final UUID ACTOR_UUID = UUID.fromString("b01767df-9207-4a04-905d-fcef894f6bcc");

    @TempDir
    Path temporaryDirectory;

    @Test
    void shutdownDrainsCurrentTickWritesAndCheckpointsWal() throws Exception {
        AtomicInteger currentTick = new AtomicInteger(100);
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("database-write-queue-capacity", 64);
        configuration.set("database-write-batch-size", 16);
        configuration.set("database-query-timeout-seconds", 5);
        configuration.set("database-shutdown-timeout-seconds", 5);
        configuration.set("database-shutdown-cancel-timeout-seconds", 2);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getConfig()).thenReturn(configuration);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("FragGuardShutdownTest"));
        when(server.getCurrentTick()).thenAnswer(invocation -> currentTick.get());
        when(server.getWorld("world")).thenReturn(world);
        when(world.getUID()).thenReturn(WORLD_UUID);

        Database database = new Database(plugin);
        database.init();
        long timestamp = System.currentTimeMillis();
        for (int index = 0; index < 8; index++) {
            database.insertAsync(new BlockChange(
                    timestamp + index,
                    700L,
                    ACTOR_UUID.toString(),
                    "Builder",
                    "world",
                    index,
                    64,
                    0,
                    ChangeAction.BREAK,
                    "minecraft:stone",
                    "minecraft:air"
            ));
        }

        DatabaseHealth before = database.health();
        long completedBefore = database.completedWrites();
        assertEquals(8, before.queuedWrites(),
                "same-tick writes below the pressure threshold should still be queued before shutdown");

        database.shutdown();
        DatabaseHealth after = database.health();
        StorageShutdownReport report = StorageShutdownSupport.finish(
                before, after,
                completedBefore, database.completedWrites(),
                database.workerStopped() ? 0 : database.inFlightWrites(),
                database.workerStopped(), database.walCheckpointCompleted());

        assertEquals(8, report.drainedWrites());
        assertEquals(0, report.remainingWrites());
        assertEquals(0, report.remainingOperations());
        assertEquals(0, report.unconfirmedOperations());
        assertEquals(0, report.lostDuringShutdown());
        assertEquals(0, report.unconfirmedWrites());
        assertTrue(report.workerStopped(), "database worker must finish before clean shutdown is reported");
        assertTrue(report.walCheckpointCompleted(), "database worker must checkpoint WAL after draining queues");
        assertTrue(report.clean());

        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + temporaryDirectory.resolve("fragguard.db"));
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM block_changes")) {
            assertTrue(rows.next());
            assertEquals(8, rows.getInt(1), "all queued writes must be durable after shutdown returns");
        }
    }

    @Test
    void abandonedWritesAreNotCountedAsDrained() {
        DatabaseHealth before = new DatabaseHealth(8, 64, 0, 16,
                0L, 0L, true, "");
        DatabaseHealth after = new DatabaseHealth(0, 64, 0, 16,
                8L, 0L, false, "write failure");

        StorageShutdownReport report = StorageShutdownSupport.finish(
                before, after,
                12L, 12L,
                0, true, false);

        assertEquals(0, report.drainedWrites(),
                "abandoned queue entries must not be reported as successfully drained");
        assertEquals(8, report.lostDuringShutdown());
        assertFalse(report.clean());
    }

    @Test
    void liveWorkerWriteBatchIsReportedAsUnconfirmed() {
        DatabaseHealth before = new DatabaseHealth(5, 64, 0, 16,
                0L, 0L, true, "");
        DatabaseHealth after = new DatabaseHealth(0, 64, 0, 16,
                5L, 0L, false, "database worker did not stop");

        StorageShutdownReport report = StorageShutdownSupport.finish(
                before, after,
                20L, 20L,
                3, false, false);

        assertEquals(0, report.drainedWrites());
        assertEquals(0, report.unconfirmedOperations(),
                "the single database worker cannot own an operation while it owns the active write batch");
        assertEquals(5, report.lostDuringShutdown(),
                "queued writes explicitly abandoned after cancellation must be counted as lost");
        assertEquals(3, report.unconfirmedWrites(),
                "an in-flight batch still owned by a live worker must never disappear from the shutdown report");
        assertFalse(report.clean());
    }

    @Test
    void liveWorkerOperationIsIncludedInRemainingOperations() {
        DatabaseHealth before = new DatabaseHealth(0, 64, 0, 16,
                0L, 0L, true, "");
        DatabaseHealth after = new DatabaseHealth(0, 64, 0, 16,
                0L, 0L, false, "database worker did not stop");

        StorageShutdownReport report = StorageShutdownSupport.finish(
                before, after,
                40L, 40L,
                0, false, false);

        assertEquals(1, report.unconfirmedOperations(),
                "a live worker with no active write batch must be conservatively accounted as active database work");
        assertEquals(1, report.remainingOperations(),
                "active work already removed from the operation queue must not produce a zero-operation report");
        assertFalse(report.clean());
    }

    @Test
    void anyKnownDroppedWriteKeepsStorageVisiblyDegraded() {
        DatabaseHealth health = new DatabaseHealth(0, 64, 0, 16,
                1L, 0L, true, "");

        assertTrue(health.storageAvailable());
        assertTrue(health.degraded());
        assertFalse(health.healthy(),
                "a session with confirmed log loss must not report storage health as OK");
    }

    @Test
    void activeDatabaseErrorIsVisibleEvenWhileWorkerIsReachable() {
        DatabaseHealth health = new DatabaseHealth(0, 64, 0, 16,
                0L, 0L, true, "database or disk is full");

        assertTrue(health.storageAvailable());
        assertTrue(health.degraded());
        assertFalse(health.healthy(),
                "a reported SQLite failure must be visible instead of appearing healthy");
    }
}
