package org.pinnaclesmp.fragguard;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RollbackSafetyRegressionTest {
    private static final UUID WORLD_UUID = UUID.fromString("06e08e65-9ecf-4983-ab69-308ca1d10d0c");
    private static final UUID ACTOR_UUID = UUID.fromString("31996b0f-48b6-46df-9b6b-0ebd8fdb247a");

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
    void forceRetryExhaustionCommitsCompletedResultsBeforeFailing() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/org/pinnaclesmp/fragguard/FragGuardCommand.java"));
        int start = source.indexOf("if (forceAttempt >= MAX_FORCE_REVALIDATION_RETRIES)");
        int end = source.indexOf("retryForcedChanges(job, operator", start);

        assertTrue(start >= 0, "force retry exhaustion branch must exist");
        assertTrue(end > start, "force retry exhaustion branch must precede the next retry");
        String exhaustionBranch = source.substring(start, end);
        assertTrue(exhaustionBranch.contains(
                        "persistCompletedResultsBeforeFailure(job, operator, results, false"),
                "successful sibling mutations must be committed before retry exhaustion fails the job");
        assertFalse(exhaustionBranch.contains("failJob(job, operator"),
                "retry exhaustion must not bypass completed-result persistence");
    }

    @Test
    void schemaTwoMigrationKeepsLegacyAppliedStateUnknown() throws Exception {
        database = startDatabase();
        long snapshotTimestamp = System.currentTimeMillis();
        RollbackJob job = database.createRollbackJobAsync(ACTOR_UUID.toString(), "Builder", "world",
                4, 4, 10, snapshotTimestamp - 1_000L, snapshotTimestamp, false,
                List.of(new RollbackTarget("world", 4, 70, 4,
                        "minecraft:stone", "minecraft:dirt"))).join();
        RollbackJobChange change = database.loadRollbackChangesAsync(job.id(), false).join().get(0);
        database.prepareRollbackBatchAsync(job.id(),
                List.of(change.withBeforeData("minecraft:dirt"))).join();
        database.markRollbackBatchAppliedAsync(job.id(),
                List.of(new RollbackStepResult(change.sequence(), true, false,
                        "minecraft:air", null))).join();
        database.completeRollbackJobAsync(job.id(), false).join();
        database.shutdown();
        database = null;

        try (Connection connection = openDatabase(); Statement statement = connection.createStatement()) {
            try (ResultSet row = statement.executeQuery(
                    "SELECT applied_data FROM rollback_job_changes WHERE job_id = " + job.id())) {
                assertTrue(row.next());
                assertEquals("minecraft:air", row.getString("applied_data"),
                        "the v3 setup must model a physics-normalized result distinct from target_data");
            }
            statement.executeUpdate("ALTER TABLE block_changes DROP COLUMN rollback_pending");
            statement.executeUpdate("ALTER TABLE rollback_job_changes DROP COLUMN applied_data");
            statement.executeUpdate("ALTER TABLE rollback_job_changes DROP COLUMN applied_entity_data");
            statement.executeUpdate("ALTER TABLE rollback_job_changes DROP COLUMN pending_audit_id");
            statement.executeUpdate("ALTER TABLE rollback_job_changes DROP COLUMN pending_audit_undo");
            statement.execute("PRAGMA user_version=2");
        }

        database = startDatabase();
        try (Connection connection = openDatabase(); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT applied_data, applied_entity_data FROM rollback_job_changes WHERE job_id = " + job.id())) {
            assertTrue(row.next());
            assertNull(row.getString("applied_data"),
                    "schema-v2 jobs did not capture the observed physics result and must remain unknown");
            assertNull(row.getBytes("applied_entity_data"));
        }

        database.beginUndoAsync(job.id()).join();
        RollbackJobChange migrated = database.loadRollbackChangesAsync(job.id(), true).join().get(0);
        assertNull(migrated.appliedData(),
                "loading a migrated row must not reconstruct the unknown state from target_data");
        assertNull(migrated.appliedEntityData());
    }

    @Test
    void unknownLegacyAppliedStateDoesNotUseTheStrictUndoConflictComparator() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/org/pinnaclesmp/fragguard/FragGuardCommand.java"));
        int methodStart = source.indexOf("private void applyPreparedBatch");
        int undoStart = source.indexOf("if (undo) {", methodStart);
        int undoEnd = source.indexOf("continue;", undoStart);

        assertTrue(methodStart >= 0 && undoStart > methodStart && undoEnd > undoStart);
        String undoGuard = source.substring(undoStart, undoEnd);
        assertTrue(undoGuard.contains("change.appliedData() != null"),
                "strict undo conflict checking must only run when the observed applied state is known");
        assertFalse(undoGuard.contains("Objects.requireNonNullElse(change.appliedData(), change.targetData())"),
                "legacy unknown applied state must not be asserted to equal target_data");
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
