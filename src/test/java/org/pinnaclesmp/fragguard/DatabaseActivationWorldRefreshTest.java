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
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseActivationWorldRefreshTest {
    @TempDir Path folder;
    private static final UUID WORLD_ID =
            UUID.fromString("b6a080ba-086a-48e4-9fdb-7281fa548b27");

    @Test
    void activeActivationKeepsVerifiedRollbackWorldNamesUntilDurableStateReset()
            throws Exception {
        seedRollbackNames();
        Database database = start(MaintenancePhase.ACTIVATING);
        try {
            assertEquals("original-world", jobWorld(),
                    "DB.init must not alter approved candidate digests during ACTIVATING");
            assertEquals("original-world", undoWorld(),
                    "rollback/undo saved snapshots must remain byte-equivalent during recovery");
            database.refreshWorldNamesAfterActivationAsync().get(20, TimeUnit.SECONDS);
            assertEquals("renamed-world", jobWorld());
            assertEquals("renamed-world", undoWorld());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void ordinaryStartupStillRefreshesRollbackWorldNames() throws Exception {
        seedRollbackNames();
        Database database = start(MaintenancePhase.IDLE);
        try {
            assertEquals("renamed-world", jobWorld(),
                    "normal startup remains backward-compatible");
            assertEquals("renamed-world", undoWorld());
        } finally {
            database.shutdown();
        }
    }

    private void seedRollbackNames() throws Exception {
        Path file = DatabaseDeepDiagnosticsTest.seededDatabase(folder, 3);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement sql = connection.createStatement()) {
            sql.execute("UPDATE rollback_jobs SET world_uuid='" + WORLD_ID
                    + "', world='original-world' WHERE id=1");
            sql.execute("UPDATE rollback_job_changes SET world_uuid='" + WORLD_ID
                    + "', world='original-world' WHERE job_id=1");
        }
    }

    private Database start(MaintenancePhase stage) throws Exception {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        MaintenanceCoordinator coordinator = mock(MaintenanceCoordinator.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("FGActivationWorldRefreshTest"));
        when(plugin.getMaintenanceCoordinator()).thenReturn(coordinator);
        when(coordinator.snapshot()).thenReturn(stage == MaintenancePhase.IDLE
                ? MaintenanceSnapshot.idle()
                : new MaintenanceSnapshot(
                        MaintenancePhase.ACTIVATING,
                        UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                        1_800_000_000_000L, 1_800_000_001_000L,
                        MaintenancePolicy.STRICT, 1_800_000_000_500L,
                        "fragguard-compact-test.db",
                        "fragguard-compact-test.manifest",
                        "Revalidating installed candidate before normal writes", 5));
        when(server.getWorlds()).thenReturn(List.of(world));
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("renamed-world");
        when(server.getCurrentTick()).thenReturn(100);
        Database instance = new Database(plugin);
        instance.init();
        return instance;
    }

    private String jobWorld() throws Exception {
        return text("SELECT world FROM rollback_jobs WHERE id = 1");
    }

    private String undoWorld() throws Exception {
        return text("SELECT world FROM rollback_job_changes WHERE job_id = 1");
    }

    private String text(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + folder.resolve("fragguard.db"));
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
