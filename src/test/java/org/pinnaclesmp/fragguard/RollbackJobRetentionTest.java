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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RollbackJobRetentionTest {
    private static final UUID WORLD_UUID = UUID.fromString("21d42c5c-a0eb-447d-ae7e-810149eb424e");
    private static final UUID ACTOR_UUID = UUID.fromString("3068f82f-f736-45ed-b27c-e49f2c08ba14");

    @TempDir
    Path temporaryDirectory;

    private Database database;

    @AfterEach
    void shutDownDatabase() {
        if (database != null) {
            database.shutdown();
        }
    }

    @Test
    void cleanupExpiresOnlyTerminalRollbackJobsAndCascadesTheirSnapshots() throws Exception {
        database = startDatabase();
        long now = System.currentTimeMillis();
        long old = now - TimeUnit.DAYS.toMillis(10);

        database.insertRequiredAsync(List.of(new BlockChange(
                now - TimeUnit.DAYS.toMillis(5),
                ACTOR_UUID.toString(),
                "Builder",
                "world",
                100,
                64,
                100,
                ChangeAction.BREAK,
                "minecraft:stone",
                "minecraft:air"
        ))).join();

        long completedOld;
        long undoneOld;
        long failedPermanentOld;
        long failedRecoverableOld;
        long runningOld;
        long undoingOld;
        long completedRecent;

        try (Connection connection = openDatabase()) {
            connection.createStatement().execute("PRAGMA foreign_keys=ON");
            completedOld = insertJob(connection, "COMPLETED", old, true, false, false, 1);
            undoneOld = insertJob(connection, "UNDONE", old, true, false, true, 2);
            failedPermanentOld = insertJob(connection, "FAILED", old, true, true, false, 3);
            failedRecoverableOld = insertJob(connection, "FAILED", old, true, false, false, 4);
            runningOld = insertJob(connection, "RUNNING", old, false, false, false, 5);
            undoingOld = insertJob(connection, "UNDOING", old, true, false, false, 6);
            completedRecent = insertJob(connection, "COMPLETED", now, true, false, false, 7);
        }

        DatabaseCleanupResult deleted = database.cleanupOldRecordsAsync(1, 7).join();

        assertEquals(1, deleted.blockRecordsDeleted());
        assertEquals(3, deleted.rollbackJobsDeleted());

        try (Connection connection = openDatabase()) {
            Set<Long> remaining = jobIds(connection);

            assertFalse(remaining.contains(completedOld));
            assertFalse(remaining.contains(undoneOld));
            assertFalse(remaining.contains(failedPermanentOld));

            assertTrue(remaining.contains(failedRecoverableOld),
                    "failed jobs with an undoable saved state must remain recoverable");
            assertTrue(remaining.contains(runningOld), "running jobs must never be removed by retention cleanup");
            assertTrue(remaining.contains(undoingOld), "undoing jobs must never be removed by retention cleanup");
            assertTrue(remaining.contains(completedRecent), "terminal jobs newer than the retention cutoff must remain");

            assertEquals(0, changeRows(connection, completedOld));
            assertEquals(0, changeRows(connection, undoneOld));
            assertEquals(0, changeRows(connection, failedPermanentOld));
            assertEquals(1, changeRows(connection, failedRecoverableOld));
            assertEquals(1, changeRows(connection, runningOld));
            assertEquals(1, changeRows(connection, undoingOld));
            assertEquals(1, changeRows(connection, completedRecent));
        }
    }

    private long insertJob(
            Connection connection,
            String status,
            long updatedAt,
            boolean hasBeforeState,
            boolean conflicted,
            boolean undone,
            int x
    ) throws Exception {
        long jobId;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO rollback_jobs
                (created_at, updated_at, actor_uuid, actor_name, world_uuid, world,
                 center_x, center_z, radius, target_timestamp, snapshot_timestamp, force,
                 status, total_blocks)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 1)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, updatedAt);
            statement.setLong(2, updatedAt);
            statement.setString(3, ACTOR_UUID.toString());
            statement.setString(4, "Builder");
            statement.setString(5, WORLD_UUID.toString());
            statement.setString(6, "world");
            statement.setInt(7, x);
            statement.setInt(8, 0);
            statement.setInt(9, 1);
            statement.setLong(10, updatedAt - 1_000L);
            statement.setLong(11, updatedAt);
            statement.setString(12, status);
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next());
                jobId = keys.getLong(1);
            }
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO rollback_job_changes
                (job_id, sequence, world, x, y, z, before_data, target_data, expected_data,
                 processed, applied, conflicted, undone, before_entity_data)
                VALUES (?, 0, 'world', ?, 64, 0, ?, 'minecraft:dirt', 'minecraft:stone',
                        1, 1, ?, ?, ?)
                """)) {
            statement.setLong(1, jobId);
            statement.setInt(2, x);
            statement.setString(3, hasBeforeState ? "minecraft:stone" : null);
            statement.setInt(4, conflicted ? 1 : 0);
            statement.setInt(5, undone ? 1 : 0);
            statement.setBytes(6, new byte[]{1, 2, 3, (byte) x});
            statement.executeUpdate();
        }
        return jobId;
    }

    private Set<Long> jobIds(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT id FROM rollback_jobs")) {
            Set<Long> ids = new HashSet<>();
            while (rows.next()) {
                ids.add(rows.getLong(1));
            }
            return ids;
        }
    }

    private int changeRows(Connection connection, long jobId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM rollback_job_changes WHERE job_id = ?")) {
            statement.setLong(1, jobId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
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
        when(plugin.getLogger()).thenReturn(Logger.getLogger("RollbackJobRetentionTest"));
        when(server.getCurrentTick()).thenReturn(900);
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
