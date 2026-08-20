package org.pinnaclesmp.fragguard;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
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
        assertEquals(8, before.queuedWrites(),
                "same-tick writes below the pressure threshold should still be queued before shutdown");

        database.shutdown();
        DatabaseHealth after = database.health();
        StorageShutdownReport report = StorageShutdownSupport.finish(
                new File(temporaryDirectory.toFile(), "fragguard.db"), before, after);

        assertEquals(8, report.drainedWrites());
        assertEquals(0, report.remainingWrites());
        assertEquals(0, report.remainingOperations());
        assertEquals(0, report.lostDuringShutdown());
        assertTrue(report.checkpoint().success(), report.checkpoint().error());
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
    void anyKnownDroppedWriteKeepsStorageVisiblyDegraded() {
        DatabaseHealth health = new DatabaseHealth(0, 64, 0, 16,
                1L, 0L, true, "");

        assertTrue(health.storageAvailable());
        assertTrue(health.degraded());
        assertFalse(health.healthy(),
                "a session with confirmed log loss must not report storage health as OK");
    }
}
