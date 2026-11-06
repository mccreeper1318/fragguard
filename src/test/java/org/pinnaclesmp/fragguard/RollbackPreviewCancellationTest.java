package org.pinnaclesmp.fragguard;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RollbackPreviewCancellationTest {
    private static final UUID ACTOR = UUID.fromString("18181818-1818-1818-1818-181818181818");

    @Test
    void cancelClearsTokenAndVisualizationWithoutStartingAJob() throws Exception {
        Fixture f = fixture();
        String token = start(f);
        assertTrue(f.visuals().isShowing(ACTOR, token));

        command(f, "rollback", "cancel");
        assertTrue(previews(f).isEmpty());
        assertFalse(f.visuals().isShowing(ACTOR, token));
        verify(f.task()).cancel();
        verify(f.db(), never()).createRollbackJobAsync(anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyLong(), anyLong(), anyBoolean(), anyList());

        // Repeating cancel must remain harmless.
        command(f, "rollback", "cancel");
        assertTrue(previews(f).isEmpty());
    }

    @Test
    void cancellationAlsoInvalidatesPendingQueries() throws Exception {
        Fixture f = fixture();
        Map<UUID, UUID> pending = pending(f);
        pending.put(ACTOR, UUID.randomUUID());
        command(f, "rollback", "cancel");
        assertFalse(pending.containsKey(ACTOR));
        verify(f.db(), never()).createRollbackJobAsync(anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyLong(), anyLong(), anyBoolean(), anyList());
    }

    @Test
    void replacementStopsOldVisualAndStaleExpirationCannotRemoveNewToken() throws Exception {
        Fixture f = fixture();
        String old = start(f);
        Object oldPreview = previews(f).get(old);
        String replacement = start(f);
        assertNotEquals(old, replacement);
        assertFalse(f.visuals().isShowing(ACTOR, old));
        assertTrue(f.visuals().isShowing(ACTOR, replacement));

        expire(f, oldPreview, expiration(oldPreview) + 1);
        assertTrue(f.visuals().isShowing(ACTOR, replacement));
        assertEquals(1, previews(f).size());
        verify(f.task()).cancel();
    }

    @Test
    void expirationStopsVisualAndDeletesToken() throws Exception {
        Fixture f = fixture();
        String token = start(f);
        Object preview = previews(f).get(token);
        expire(f, preview, expiration(preview) + 1);

        assertTrue(previews(f).isEmpty());
        assertFalse(f.visuals().isShowing(ACTOR, token));
        verify(f.task()).cancel();
    }

    @Test
    void confirmationDisposesVisualBeforeSavingRollbackJob() throws Exception {
        Fixture f = fixture();
        String token = start(f);
        when(f.db().createRollbackJobAsync(anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyLong(), anyLong(), anyBoolean(), anyList()))
                .thenAnswer(invocation -> {
                    assertFalse(f.visuals().isShowing(ACTOR, token),
                            "visual must stop before database job creation");
                    assertFalse(previews(f).containsKey(token),
                            "token must be consumed before job creation");
                    return new CompletableFuture<RollbackJob>();
                });

        command(f, "rollback", "confirm", token);
        assertTrue(previews(f).isEmpty());
        assertFalse(f.visuals().isShowing(ACTOR, token));
        verify(f.task()).cancel();
        verify(f.db()).createRollbackJobAsync(anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyLong(), anyLong(), anyBoolean(), anyList());
    }

    @Test
    void quitAndPluginShutdownRemoveVisualsAndPendingWork() throws Exception {
        Fixture f = fixture();
        String token = start(f);
        pending(f).put(ACTOR, UUID.randomUUID());

        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(f.player());
        f.command().onPlayerQuit(event);
        assertFalse(f.visuals().isShowing(ACTOR, token));
        assertTrue(previews(f).isEmpty());
        assertTrue(pending(f).isEmpty());

        String second = start(f);
        pending(f).put(ACTOR, UUID.randomUUID());
        f.command().clearRollbackPreviews();
        assertFalse(f.visuals().isShowing(ACTOR, second));
        assertTrue(previews(f).isEmpty());
        assertTrue(pending(f).isEmpty());
    }

    private static Fixture fixture() {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        Database db = mock(Database.class);
        FileConfiguration config = mock(FileConfiguration.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        Player player = mock(Player.class);

        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getWorld("world")).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(UUID.fromString("29292929-2929-2929-2929-292929292929"));
        when(player.getWorld()).thenReturn(world);
        when(player.getUniqueId()).thenReturn(ACTOR);
        when(player.getName()).thenReturn("Operator");
        when(player.isOnline()).thenReturn(true);
        when(player.isOp()).thenReturn(true);
        when(player.hasPermission("fragguard.admin")).thenReturn(true);
        when(config.getInt("rollback-max-chunks-per-command", 256)).thenReturn(256);
        when(config.getInt("rollback-confirmation-timeout-seconds", 60)).thenReturn(60);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(0L), eq(20L)))
                .thenReturn(task);

        return new Fixture(new FragGuardCommand(plugin, db), db, player, task);
    }

    private static String start(Fixture f) throws Exception {
        Method method = FragGuardCommand.class.getDeclaredMethod("previewRollback",
                Player.class, String.class, List.class, int.class, int.class,
                int.class, long.class, long.class, boolean.class, int.class);
        method.setAccessible(true);
        method.invoke(f.command(), f.player(), "world",
                List.of(new RollbackTarget("world", 0, 64, 0, "minecraft:stone", "minecraft:air")),
                0, 0, 15, 1000L, 2000L, false, 50_000);
        return previews(f).keySet().iterator().next();
    }

    private static void command(Fixture f, String... args) {
        f.command().onCommand(f.player(), mock(Command.class), "fg", args);
    }

    private static void expire(Fixture f, Object preview, long now) throws Exception {
        Method method = FragGuardCommand.class.getDeclaredMethod(
                "expirePreview", preview.getClass(), long.class);
        method.setAccessible(true);
        method.invoke(f.command(), preview, now);
    }

    private static long expiration(Object preview) throws Exception {
        Method method = preview.getClass().getDeclaredMethod("expiresAt");
        method.setAccessible(true);
        return (long) method.invoke(preview);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> previews(Fixture f) throws Exception {
        Field field = FragGuardCommand.class.getDeclaredField("previews");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(f.command());
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, UUID> pending(Fixture f) throws Exception {
        Field field = FragGuardCommand.class.getDeclaredField("pendingRollbackQueries");
        field.setAccessible(true);
        return (Map<UUID, UUID>) field.get(f.command());
    }

    private record Fixture(FragGuardCommand command, Database db, Player player, BukkitTask task) {
        private RollbackAreaPreview visuals() throws Exception {
            Field field = FragGuardCommand.class.getDeclaredField("areaPreview");
            field.setAccessible(true);
            return (RollbackAreaPreview) field.get(command);
        }
    }
}
