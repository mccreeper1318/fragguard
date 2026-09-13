package org.pinnaclesmp.fragguard;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuiLookupCutoffTest {
    private static final UUID WORLD_UUID = UUID.fromString("563fce36-6445-43e9-9e79-3bb6d0780b13");
    private static final UUID ACTOR_UUID = UUID.fromString("714ea63f-075e-4694-b2c4-ae06a79748aa");

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
    void shortGuiWindowCountsAndSelectsOnlyRowsInsideItsCutoff() throws Exception {
        database = startDatabase();
        long now = System.currentTimeMillis();
        long guiCutoff = now - 15L * 60L * 1000L;

        List<BlockChange> changes = new ArrayList<>();
        for (int index = 0; index < 260; index++) {
            changes.add(change(now - 2L * 24L * 60L * 60L * 1000L - index, index));
        }
        changes.add(change(now - 5L * 60L * 1000L, 1_000));
        changes.add(change(now - 60L * 1000L, 1_001));
        database.insertRequiredAsync(changes).join();

        LookupPage guiWindow = database.lookupSinceAsync("world", 0, 0, 10, 1, 250, guiCutoff).join();
        assertEquals(2, guiWindow.totalRows(),
                "the GUI row cap must count only records inside the selected time window");
        assertEquals(2, guiWindow.rows().size());
        assertTrue(guiWindow.rows().stream().allMatch(row -> row.happenedAt() >= guiCutoff),
                "SQLite must exclude older retained rows before returning the GUI page");

        LookupPage retainedHistory = database.lookupAsync("world", 0, 0, 10, 1, 500, 30).join();
        assertEquals(262, retainedHistory.totalRows(),
                "the existing retention-based command lookup path must remain unchanged");
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
        when(plugin.getLogger()).thenReturn(Logger.getLogger("FragGuardGuiLookupCutoffTest"));
        when(server.getCurrentTick()).thenAnswer(invocation -> currentTick.get());
        when(server.getWorld("world")).thenReturn(world);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(world.getUID()).thenReturn(WORLD_UUID);
        when(world.getName()).thenReturn("world");

        Database instance = new Database(plugin);
        instance.init();
        return instance;
    }

    private BlockChange change(long timestamp, int offset) {
        int x = Math.floorMod(offset, 7) - 3;
        int z = Math.floorMod(offset / 7, 7) - 3;
        return new BlockChange(timestamp, ACTOR_UUID.toString(), "Builder", "world", x, 64, z,
                ChangeAction.BREAK, "minecraft:stone", "minecraft:air");
    }
}
