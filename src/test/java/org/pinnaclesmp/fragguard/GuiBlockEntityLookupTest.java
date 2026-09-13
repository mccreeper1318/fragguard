package org.pinnaclesmp.fragguard;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuiBlockEntityLookupTest {
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
    void lookupRowsRetainStoredBlockEntitySnapshots() throws Exception {
        database = startDatabase();
        long now = System.currentTimeMillis();
        byte[] beforeEntity = new byte[]{1, 2, 3, 4};
        byte[] afterEntity = new byte[]{5, 6, 7, 8};
        database.insertRequiredAsync(List.of(new BlockChange(
                now,
                ACTOR_UUID.toString(),
                "Builder",
                "world",
                1,
                64,
                1,
                ChangeAction.BREAK,
                "minecraft:chest",
                "minecraft:chest",
                beforeEntity,
                afterEntity))).join();

        LookupPage page = database.lookupSinceAsync("world", 1, 1, 5, 1, 50, now - 1_000L).join();

        assertEquals(1, page.totalRows());
        assertEquals(1, page.rows().size());
        LookupRow row = page.rows().getFirst();
        assertArrayEquals(beforeEntity, row.beforeEntityData());
        assertArrayEquals(afterEntity, row.afterEntityData());
        assertTrue(row.blockEntityChanged(),
                "the exact GUI row must expose that the stored block-entity state changed");
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
        when(plugin.getLogger()).thenReturn(Logger.getLogger("FragGuardGuiBlockEntityLookupTest"));
        when(server.getCurrentTick()).thenAnswer(invocation -> currentTick.get());
        when(server.getWorld("world")).thenReturn(world);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(world.getUID()).thenReturn(WORLD_UUID);
        when(world.getName()).thenReturn("world");

        Database instance = new Database(plugin);
        instance.init();
        return instance;
    }
}
