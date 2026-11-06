package org.pinnaclesmp.fragguard;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlayerRollbackPreviewTest {
    private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TARGET = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID WORLD = UUID.fromString("99999999-2222-3333-4444-555555555555");

    @Test
    void playerPreviewHasOwnTokenLifecycleWithoutMisleadingRadiusVisuals() throws Exception {
        var fixture = fixture();
        String token = preview(fixture);
        assertEquals(1, previews(fixture).size());
        RollbackAreaPreview visuals = visuals(fixture);
        assertFalse(visuals.isShowing(ADMIN, token),
                "multi-world player rollback must not draw a single radius perimeter");
        verify(fixture.scheduler()).runTaskLater(eq(fixture.plugin()), any(Runnable.class), anyLong());

        command(fixture, "rollback", "cancel");
        assertTrue(previews(fixture).isEmpty());
        assertFalse(visuals.isShowing(ADMIN, token));
        verify(fixture.database(), never()).createPlayerRollbackJobAsync(
                anyString(), anyString(), any(), any(), anyBoolean());

        String replacement = preview(fixture);
        assertNotEquals(token, replacement);
        command(fixture, "rollback", "confirm", token);
        assertTrue(previews(fixture).containsKey(replacement),
                "invalid old token must not discard the current preview");
    }

    @Test
    void confirmationUsesSeparateOperatorAndPlayerAndDoesNotRequireCurrentOperatorWorld()
            throws Exception {
        var fixture = fixture();
        String token = preview(fixture);
        when(fixture.database().createPlayerRollbackJobAsync(eq(ADMIN.toString()),
                eq("Admin"), any(PlayerRollbackPlan.class), isNull(), eq(false)))
                .thenReturn(new CompletableFuture<>());
        // The operator's location/world is intentionally not part of a player-scoped rollback.
        when(fixture.player().getWorld()).thenReturn(null);
        command(fixture, "rollback", "confirm", token);
        assertFalse(previews(fixture).containsKey(token));
        verify(fixture.database()).createPlayerRollbackJobAsync(eq(ADMIN.toString()),
                eq("Admin"), any(PlayerRollbackPlan.class), isNull(), eq(false));
        verify(fixture.database(), never()).createRollbackJobAsync(
                anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt(),
                anyLong(), anyLong(), anyBoolean(), anyList());
    }

    @Test
    void expirationAndLogoutClearPlayerPreviewWithoutStartingJobs() throws Exception {
        var fixture = fixture();
        String token = preview(fixture);
        Object first = previews(fixture).get(token);
        Method expire = FragGuardCommand.class.getDeclaredMethod(
                "expirePreview", first.getClass(), long.class);
        expire.setAccessible(true);
        Method expiresAt = first.getClass().getDeclaredMethod("expiresAt");
        expiresAt.setAccessible(true);
        expire.invoke(fixture.command(), first, (long) expiresAt.invoke(first) + 1L);
        assertTrue(previews(fixture).isEmpty());
        preview(fixture);
        fixture.command().clearRollbackPreviews();
        assertTrue(previews(fixture).isEmpty());
    }

    private static String preview(Fixture f) throws Exception {
        PlayerRollbackPlan plan = new PlayerRollbackPlan(
                new HistoricalPlayerResolver.HistoricalPlayerIdentity(TARGET, "Builder"),
                System.currentTimeMillis() - 3_600_000L, System.currentTimeMillis(),
                List.of(new RollbackTarget("world", 16, 64, 4,
                        "minecraft:stone", "minecraft:air",
                        null, null, WORLD.toString(),
                        "tnt-entity", "Explosion: TNT", TARGET.toString(), "chain", "TNT_CHAIN")),
                1, 2, 0, 0);
        PlayerRollbackArguments options = new PlayerRollbackArguments("Builder", null, 3_600_000, false);
        Method invoke = FragGuardCommand.class.getDeclaredMethod(
                "previewPlayerRollback", Player.class, PlayerRollbackArguments.class,
                PlayerRollbackPlan.class);
        invoke.setAccessible(true);
        invoke.invoke(f.command(), f.player(), options, plan);
        return previews(f).keySet().iterator().next();
    }

    private static void command(Fixture fixture, String... args) {
        fixture.command().onCommand(fixture.player(), mock(Command.class), "fg", args);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> previews(Fixture fixture) throws Exception {
        Field field = FragGuardCommand.class.getDeclaredField("previews");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(fixture.command());
    }

    private static RollbackAreaPreview visuals(Fixture f) throws Exception {
        Field field = FragGuardCommand.class.getDeclaredField("areaPreview");
        field.setAccessible(true);
        return (RollbackAreaPreview) field.get(f.command());
    }

    private static Fixture fixture() {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        Database database = mock(Database.class);
        Player player = mock(Player.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("rollback-max-chunks-per-command", 10);
        cfg.set("rollback-confirmation-timeout-seconds", 60);
        when(plugin.getConfig()).thenReturn(cfg);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getWorld(WORLD)).thenReturn(world);
        when(world.getUID()).thenReturn(WORLD);
        when(world.getName()).thenReturn("world");
        when(player.getUniqueId()).thenReturn(ADMIN);
        when(player.getName()).thenReturn("Admin");
        when(player.isOnline()).thenReturn(true);
        when(player.isOp()).thenReturn(true);
        when(player.hasPermission("fragguard.admin")).thenReturn(true);
        return new Fixture(plugin, database, player, scheduler, new FragGuardCommand(plugin, database));
    }

    private record Fixture(FragGuardPlugin plugin, Database database, Player player,
                           BukkitScheduler scheduler, FragGuardCommand command) {
    }
}
