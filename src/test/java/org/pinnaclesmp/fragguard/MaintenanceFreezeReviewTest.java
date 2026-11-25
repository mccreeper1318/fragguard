package org.pinnaclesmp.fragguard;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Regression coverage for Codex P1/P2 on maintenance freeze PR #209. */
class MaintenanceFreezeReviewTest {
    @TempDir Path folder;
    private static final UUID WORLD =
            UUID.fromString("b6a080ba-086a-48e4-9fdb-7281fa548b27");

    @Test
    void activeRollbackOrUndoRequiresRealCompletionBeforeDatabaseCanFreeze()
            throws Exception {
        DatabaseDeepDiagnosticsTest.seededDatabase(folder, 3);
        try (Connection connection = open(); Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("""
                    INSERT INTO rollback_jobs
                    (created_at, updated_at, actor_uuid, actor_name, world_uuid, world,
                     center_x, center_z, radius, target_timestamp, status, total_blocks,
                     processed_blocks, applied_blocks, undo_processed_blocks, scope_type,
                     snapshot_timestamp)
                    VALUES (100, 100, 'actor', 'Owner', 'world', 'world', 0, 0, 5, 100,
                            'RUNNING', 0, 0, 0, 0, 'RADIUS', 100)
                    """);
        }
        Database database = start(false);
        try {
            assertTrue(database.hasActiveRollbackJobsAsync().get(20, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () ->
                    database.freezeForMaintenanceAsync().get(20, TimeUnit.SECONDS),
                    "Do not cut off pending rollback audit confirmations");
            assertFalse(database.maintenanceFullyFrozen(),
                    "A failed quiescence check cannot declare the source immutable");
            // A failed attempt must not permanently trap a server owner.
            database.resumeWritesAfterCancellationAsync().get(20, TimeUnit.SECONDS);
            try (Connection connection = open(); Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("UPDATE rollback_jobs SET status='COMPLETED' WHERE status='RUNNING'");
            }
            assertFalse(database.hasActiveRollbackJobsAsync().get(20, TimeUnit.SECONDS));
            database.freezeForMaintenanceAsync().get(20, TimeUnit.SECONDS);
            assertTrue(database.maintenanceFullyFrozen());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void verifiedCandidateRestoresWalBeforeReopeningWrites() throws Exception {
        DatabaseDeepDiagnosticsTest.seededDatabase(folder, 3);
        try (Connection connection = open(); Statement stmt = connection.createStatement();
             ResultSet result = stmt.executeQuery("PRAGMA journal_mode=DELETE")) {
            assertTrue(result.next());
            assertEquals("delete", result.getString(1).toLowerCase(java.util.Locale.ROOT));
        }
        Database database = start(true);
        try {
            assertTrue(database.maintenanceFullyFrozen());
            assertEquals("delete", journalMode());
            database.resumeWritesAfterCancellationAsync().get(20, TimeUnit.SECONDS);
            assertFalse(database.maintenanceWritesPaused());
            assertEquals("wal", journalMode(),
                    "Unsealed VACUUM INTO candidate must not keep DELETE journaling");
            database.refreshWorldNamesAfterActivationAsync().get(20, TimeUnit.SECONDS);
        } finally {
            database.shutdown();
        }
    }

    private Database start(boolean activating) throws Exception {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        MaintenanceCoordinator coordinator = mock(MaintenanceCoordinator.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("FGMaintenanceReviewTest"));
        when(plugin.getMaintenanceCoordinator()).thenReturn(coordinator);
        when(plugin.maintenanceBlocksNewJobs()).thenReturn(activating);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(server.getCurrentTick()).thenReturn(100);
        when(world.getUID()).thenReturn(WORLD);
        when(world.getName()).thenReturn("world");
        Database database = new Database(plugin);
        database.init();
        return database;
    }

    private Connection open() throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("fragguard.db"));
    }

    private String journalMode() throws Exception {
        try (Connection connection = open(); Statement stmt = connection.createStatement();
             ResultSet result = stmt.executeQuery("PRAGMA journal_mode")) {
            assertTrue(result.next());
            return result.getString(1).toLowerCase(java.util.Locale.ROOT);
        }
    }
}
