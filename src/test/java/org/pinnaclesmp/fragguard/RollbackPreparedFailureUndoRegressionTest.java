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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RollbackPreparedFailureUndoRegressionTest {
    private static final UUID WORLD_UUID = UUID.fromString("b507f42b-66df-4e2f-8e49-0528fc16c763");
    private static final UUID ACTOR_UUID = UUID.fromString("b5e3f50b-e582-48c2-9028-dd8ad9fa2f74");

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
    void failedRollbackExcludesPreparedButUnappliedRowsFromUndo() throws Exception {
        database = startDatabase();
        long timestamp = System.currentTimeMillis();
        RollbackJob job = database.createRollbackJobAsync(ACTOR_UUID.toString(), "Builder", "world",
                4, 4, 10, timestamp - 1_000L, timestamp, false,
                List.of(
                        new RollbackTarget("world", 4, 70, 4,
                                "minecraft:stone", "minecraft:dirt"),
                        new RollbackTarget("world", 5, 70, 4,
                                "minecraft:oak_log", "minecraft:dirt")
                )).join();

        List<RollbackJobChange> changes = database.loadRollbackChangesAsync(job.id(), false).join();
        database.prepareRollbackBatchAsync(job.id(), changes.stream()
                .map(change -> change.withBeforeData("minecraft:dirt"))
                .toList()).join();

        List<RollbackPendingAudit> audits = changes.stream()
                .map(change -> new RollbackPendingAudit(change.sequence(), RollbackAudit.create(
                        job, "world", change.x(), change.y(), change.z(),
                        "minecraft:dirt", change.targetData(), false)))
                .toList();
        database.insertPendingRollbackAuditsAsync(job.id(), false, audits).join();

        database.markRollbackBatchAppliedAsync(job.id(), List.of(
                new RollbackStepResult(changes.get(0).sequence(), true, false,
                        "minecraft:stone", null)
        )).join();
        database.failRollbackJobAsync(job.id(), "simulated post-mutation failure").join();

        RollbackJob undo = database.beginUndoAsync(job.id()).join();
        List<RollbackJobChange> undoChanges = database.loadRollbackChangesAsync(job.id(), true).join();
        assertEquals(List.of(changes.get(0).sequence()),
                undoChanges.stream().map(RollbackJobChange::sequence).toList(),
                "undo must never include a row that was prepared but never mutated");
        assertEquals(2, undo.processedBlocks(),
                "the abandoned prepared row should be finalized as resolved rollback progress");
        assertEquals(1, undo.appliedBlocks());
        assertEquals(1, undo.conflictBlocks(),
                "the abandoned prepared row should be recorded as a non-applied conflict");

        String abandonedRowSql = "SELECT processed, applied, conflicted, pending_audit_id "
                + "FROM rollback_job_changes WHERE job_id = " + job.id()
                + " AND sequence = " + changes.get(1).sequence();
        try (Connection connection = openDatabase(); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(abandonedRowSql)) {
            assertTrue(row.next());
            assertEquals(1, row.getInt("processed"));
            assertEquals(0, row.getInt("applied"));
            assertEquals(1, row.getInt("conflicted"));
            assertNull(row.getObject("pending_audit_id"));
        }

        try (Connection connection = openDatabase(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM block_changes WHERE rollback_pending = 1")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1),
                    "failure finalization must not leave an abandoned hidden rollback audit behind");
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
