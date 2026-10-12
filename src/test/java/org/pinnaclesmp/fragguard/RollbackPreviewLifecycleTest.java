package org.pinnaclesmp.fragguard;

import org.bukkit.Server;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RollbackPreviewLifecycleTest {
    private static final UUID OPERATOR_UUID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void previewSchedulesExpirationAndReleasesTargetsWhenDue() throws Exception {
        Fixture fixture = fixture();
        when(fixture.player().isOnline()).thenReturn(true);

        invokePreview(fixture.command(), fixture.player(), targets("world", 1));

        Map<String, Object> previews = previews(fixture.command());
        assertEquals(1, previews.size());
        Object preview = previews.values().iterator().next();
        verify(fixture.scheduler()).runTaskLater(eq(fixture.plugin()), any(Runnable.class), anyLong());

        invokeExpiration(fixture.command(), preview, expiresAt(preview) + 1L);

        assertTrue(previews.isEmpty(),
                "expired previews must release their retained rollback targets");
    }

    @Test
    void staleExpirationCannotRemoveAReplacementPreview() throws Exception {
        Fixture fixture = fixture();
        when(fixture.player().isOnline()).thenReturn(true);

        invokePreview(fixture.command(), fixture.player(), targets("world", 1));
        Map<String, Object> previews = previews(fixture.command());
        Object first = previews.values().iterator().next();
        String firstToken = token(first);

        invokePreview(fixture.command(), fixture.player(), targets("world", 2));
        assertEquals(1, previews.size(),
                "creating a new preview must invalidate the operator's previous preview");
        Object replacement = previews.values().iterator().next();

        previews.clear();
        previews.put(firstToken, replacement);
        invokeExpiration(fixture.command(), first, expiresAt(first) + 1L);

        assertSame(replacement, previews.get(firstToken),
                "cleanup must only remove the exact preview instance it was scheduled for");
    }

    @Test
    void offlinePlayerDoesNotRetainPreviewWhenQueryCompletionReachesPreviewStage() throws Exception {
        Fixture fixture = fixture();
        when(fixture.player().isOnline()).thenReturn(true, false);

        invokePreview(fixture.command(), fixture.player(), targets("world", 1));
        assertEquals(1, previews(fixture.command()).size());

        invokePreview(fixture.command(), fixture.player(), targets("world", 10_000));

        assertTrue(previews(fixture.command()).isEmpty(),
                "disconnect before async preview completion must discard retained preview data");
    }

    private Fixture fixture() {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        Database database = mock(Database.class);
        FileConfiguration config = mock(FileConfiguration.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        Player player = mock(Player.class);

        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(config.getInt("rollback-max-chunks-per-command", 256)).thenReturn(256);
        when(config.getInt("rollback-confirmation-timeout-seconds", 60)).thenReturn(5);
        when(player.getUniqueId()).thenReturn(OPERATOR_UUID);
        when(player.getName()).thenReturn("Operator");

        return new Fixture(plugin, scheduler, player, new FragGuardCommand(plugin, database));
    }

    private void invokePreview(FragGuardCommand command, Player player,
                               List<RollbackTarget> targets) throws Exception {
        Method method = FragGuardCommand.class.getDeclaredMethod(
                "previewRollback",
                Player.class, String.class, List.class, int.class, int.class, int.class,
                long.class, long.class, boolean.class, int.class);
        method.setAccessible(true);
        method.invoke(command, player, "world", targets,
                0, 0, 15, 1_000L, 2_000L, false, 50_000);
    }

    private void invokeExpiration(FragGuardCommand command, Object preview, long now) throws Exception {
        Method method = FragGuardCommand.class.getDeclaredMethod(
                "expirePreview", preview.getClass(), long.class);
        method.setAccessible(true);
        method.invoke(command, preview, now);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> previews(FragGuardCommand command) throws Exception {
        Field field = FragGuardCommand.class.getDeclaredField("previews");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(command);
    }

    private String token(Object preview) throws Exception {
        Method method = preview.getClass().getDeclaredMethod("token");
        method.setAccessible(true);
        return (String) method.invoke(preview);
    }

    private long expiresAt(Object preview) throws Exception {
        Method method = preview.getClass().getDeclaredMethod("expiresAt");
        method.setAccessible(true);
        return (long) method.invoke(preview);
    }

    private List<RollbackTarget> targets(String worldName, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new RollbackTarget(
                        worldName, index, 64, 0, "minecraft:stone", "minecraft:air"))
                .toList();
    }

    private record Fixture(
            FragGuardPlugin plugin,
            BukkitScheduler scheduler,
            Player player,
            FragGuardCommand command
    ) {
    }
}
