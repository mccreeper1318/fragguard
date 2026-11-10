package org.pinnaclesmp.fragguard;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MaintenanceRollbackGateTest {
    @TempDir Path directory;

    @Test
    void activeMaintenanceRejectsNewRadiusPlayerAndUndoJobsBeforeDatabaseQueueing() {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.maintenanceBlocksNewJobs()).thenReturn(true);
        Database database = new Database(plugin); // Intentionally not initialized.
        long now = System.currentTimeMillis();

        var radius = assertThrows(CompletionException.class, () ->
                database.createRollbackJobAsync("operator", "Operator", "world", 0, 0, 5,
                        now - 10_000L, now, false, List.of()).join());
        assertTrue(radius.getCause().getMessage().contains("maintenance is active"));

        var player = assertThrows(CompletionException.class, () ->
                database.createPlayerRollbackJobAsync("operator", "Operator", null,
                        null, false).join());
        assertTrue(player.getCause().getMessage().contains("maintenance is active"));

        var undo = assertThrows(CompletionException.class, () ->
                database.beginUndoAsync(1).join());
        assertTrue(undo.getCause().getMessage().contains("maintenance is active"));

        // The guard is immediate; none of these paths should require a running
        // database worker or create a file under the data directory.
        assertFalse(java.nio.file.Files.exists(directory.resolve("fragguard.db")));
    }

    @Test
    void shutdownKeepsMaintenanceGateVisibleUntilDatabaseWorkerFinishes() throws Exception {
        MaintenanceCoordinator coordinator = MaintenanceCoordinator.open(directory);
        coordinator.schedule(UUID.randomUUID(), System.currentTimeMillis(),
                System.currentTimeMillis() + 600_000L, MaintenancePolicy.STRICT).join();
        coordinator.activate().join();

        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        doCallRealMethod().when(plugin).maintenanceBlocksNewJobs();
        doCallRealMethod().when(plugin).onDisable();

        Field maintenanceField = FragGuardPlugin.class.getDeclaredField("maintenance");
        maintenanceField.setAccessible(true);
        maintenanceField.set(plugin, coordinator);

        Database database = mock(Database.class);
        Field databaseField = FragGuardPlugin.class.getDeclaredField("database");
        databaseField.setAccessible(true);
        databaseField.set(plugin, database);

        // Database.shutdown() can drain queued operations. Their second
        // maintenance check must still see ACTIVE, not a cleared coordinator.
        when(database.shutdown()).thenAnswer(invocation -> {
            assertTrue(plugin.maintenanceBlocksNewJobs(),
                    "active maintenance must remain visible while database worker drains");
            throw new IllegalStateException("simulated database shutdown failure");
        });

        var failure = assertThrows(IllegalStateException.class, plugin::onDisable);
        assertEquals("simulated database shutdown failure", failure.getMessage());
        verify(database).shutdown();
        assertTrue(plugin.maintenanceBlocksNewJobs(),
                "even a failed worker shutdown must retain the maintenance gate");
        assertThrows(java.util.concurrent.CompletionException.class, () ->
                coordinator.cancel("too late, coordinator has been closed").join(),
                "the coordinator worker should close only after database shutdown");
    }
}
