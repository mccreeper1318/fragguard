package org.pinnaclesmp.fragguard;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
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
}
