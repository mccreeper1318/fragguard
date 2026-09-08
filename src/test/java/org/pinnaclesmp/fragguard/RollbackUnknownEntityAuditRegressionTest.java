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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RollbackUnknownEntityAuditRegressionTest {
    private static final UUID WORLD_UUID = UUID.fromString("4a343355-1390-427d-86ab-b19f5f94260e");
    private static final UUID ACTOR_UUID = UUID.fromString("320ef59c-d814-4f74-a430-a1f240902125");

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
    void failedPostMutationEntityCapturePersistsExplicitUnknownMarker() throws Exception {
        database = startDatabase();
        long timestamp = System.currentTimeMillis();
        RollbackJob job = database.createRollbackJobAsync(ACTOR_UUID.toString(), "Builder", "world",
                4, 4, 10, timestamp - 1_000L, timestamp, false,
                List.of(new RollbackTarget("world", 4, 70, 4,
                        "minecraft:chest", "minecraft:dirt",
                        new byte[]{7, 7, 7}, null))).join();
        RollbackJobChange change = database.loadRollbackChangesAsync(job.id(), false).join().get(0);
        database.prepareRollbackBatchAsync(job.id(),
                List.of(change.withBeforeData("minecraft:dirt"))).join();

        byte[] intendedEntityState = new byte[]{9, 9, 9};
        BlockChange audit = RollbackAudit.create(job, "world", 4, 70, 4,
                "minecraft:dirt", "minecraft:chest",
                null, intendedEntityState, false);
        database.insertPendingRollbackAuditsAsync(job.id(), false,
                List.of(new RollbackPendingAudit(change.sequence(), audit))).join();

        byte[] unknownEntityState = new byte[]{0};
        database.markRollbackBatchAppliedAsync(job.id(),
                List.of(new RollbackStepResult(change.sequence(), true, false,
                        "minecraft:chest", unknownEntityState))).join();

        try (Connection connection = openDatabase(); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT after_entity_data, rollback_pending FROM block_changes")) {
            assertTrue(row.next());
            assertArrayEquals(unknownEntityState, row.getBytes("after_entity_data"),
                    "an unverified post-mutation entity state must remain explicitly unknown in visible history");
            assertEquals(0, row.getInt("rollback_pending"));
        }

        try (Connection connection = openDatabase(); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT applied_entity_data FROM rollback_job_changes WHERE job_id = " + job.id())) {
            assertTrue(row.next());
            assertArrayEquals(unknownEntityState, row.getBytes("applied_entity_data"),
                    "the rollback job state and visible audit must carry the same explicit unknown marker");
        }
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
        when(server.getWorlds()).thenReturn(List.of(world));
        when(world.getUID()).thenReturn(WORLD_UUID);
        when(world.getName()).thenReturn("world");

        Database instance = new Database(plugin);
        instance.init();
        return instance;
    }

    private Connection openDatabase() throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + temporaryDirectory.resolve("fragguard.db"));
    }
}
