package org.pinnaclesmp.fragguard;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RollbackPreviewWorldBindingTest {
    @Test
    void previewStaysBoundToQueriedWorldWhenPlayerChangesWorld() throws Exception {
        PreviewSnapshot preview = createPreview("world_a", "world_b");

        assertEquals("world_a", preview.worldName());
        assertEquals("world_a", preview.targetWorldName());
    }

    @Test
    void previewKeepsSameWorldBehaviorWhenPlayerDoesNotMove() throws Exception {
        PreviewSnapshot preview = createPreview("world_a", "world_a");

        assertEquals("world_a", preview.worldName());
        assertEquals("world_a", preview.targetWorldName());
    }

    private PreviewSnapshot createPreview(String queriedWorldName, String currentWorldName) throws Exception {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        Database database = mock(Database.class);
        FileConfiguration config = mock(FileConfiguration.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(config.getInt("rollback-max-chunks-per-command", 256)).thenReturn(256);
        when(config.getInt("rollback-confirmation-timeout-seconds", 60)).thenReturn(60);

        Player player = mock(Player.class);
        World currentWorld = mock(World.class);
        when(currentWorld.getName()).thenReturn(currentWorldName);
        when(player.getWorld()).thenReturn(currentWorld);
        when(player.getUniqueId()).thenReturn(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        when(player.getName()).thenReturn("Operator");
        when(player.isOnline()).thenReturn(true);

        FragGuardCommand command = new FragGuardCommand(plugin, database);
        List<RollbackTarget> targets = List.of(new RollbackTarget(
                queriedWorldName, 10, 64, 20, "minecraft:stone", "minecraft:air"));

        Method previewRollback = FragGuardCommand.class.getDeclaredMethod(
                "previewRollback",
                Player.class, String.class, List.class, int.class, int.class, int.class,
                long.class, long.class, boolean.class, int.class);
        previewRollback.setAccessible(true);
        previewRollback.invoke(command, player, queriedWorldName, targets,
                10, 20, 15, 1_000L, 2_000L, false, 100);

        Field previewsField = FragGuardCommand.class.getDeclaredField("previews");
        previewsField.setAccessible(true);
        Map<?, ?> previews = (Map<?, ?>) previewsField.get(command);
        assertEquals(1, previews.size());

        Object storedPreview = previews.values().iterator().next();
        Method storedWorldName = storedPreview.getClass().getDeclaredMethod("worldName");
        storedWorldName.setAccessible(true);

        return new PreviewSnapshot(
                (String) storedWorldName.invoke(storedPreview),
                targets.getFirst().worldName());
    }

    private record PreviewSnapshot(String worldName, String targetWorldName) {
    }
}
