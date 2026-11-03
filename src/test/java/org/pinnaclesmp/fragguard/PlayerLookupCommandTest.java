package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PlayerLookupCommandTest {
    private static final UUID OPERATOR = UUID.fromString("a1111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER = UUID.fromString("b2222222-2222-2222-2222-222222222222");

    @Test
    void commandRendersPhysicalExplosionCauseWorldTimeAndNextPage() {
        Fixture f = fixture();
        PlayerLookupRow row = new PlayerLookupRow(6L, System.currentTimeMillis() - 2000,
                "tnt-uuid", "Explosion: TNT", PLAYER.toString(), "Builder",
                "TNT_CHAIN", "world_nether", 500, 68, -900,
                ChangeAction.EXPLOSION, "minecraft:stone", "minecraft:air", false);
        var page = new PlayerLookupPage(
                new HistoricalPlayerResolver.HistoricalPlayerIdentity(PLAYER, "Builder"),
                List.of(row), 1, 15, 16);
        when(f.database.lookupPlayerAsync(eq("Builder"), eq("world_nether"),
                eq(1), eq(15), eq(6L * 3_600_000L), eq(30)))
                .thenReturn(CompletableFuture.completedFuture(page));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(f.scheduler);
            runScheduledImmediately(f);
            f.command.onCommand(f.operator, mock(Command.class), "fg",
                    new String[]{"lookup", "u:Builder", "w:world_nether", "t:6h", "p:1"});
        }
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(f.operator, atLeastOnce()).sendMessage(messages.capture());
        var actual = messages.getAllValues();
        assertTrue(actual.stream().anyMatch(s -> s.contains("FragGuard Player Lookup")));
        assertTrue(actual.stream().anyMatch(s -> s.contains("Explosion: TNT")
                && s.contains("TNT_CHAIN") && s.contains("stone") && s.contains("air")),
                "physical source and exact before/after states must remain visible");
        assertTrue(actual.stream().anyMatch(s -> s.contains("world_nether")
                && s.contains("500") && s.contains("-900")));
        assertTrue(actual.stream().anyMatch(s -> s.contains(
                "/fg lookup u:Builder w:world_nether t:6h p:2")));
    }

    @Test
    void mixedRadiusAndPlayerScopeIsRejectedBeforeDatabaseAccess() {
        Fixture f = fixture();
        f.command.onCommand(f.operator, mock(Command.class), "fg",
                new String[]{"lookup", "r:30", "u:Builder"});
        verify(f.database, never()).lookupPlayerAsync(any(), any(), anyInt(), anyInt(), anyLong(), anyInt());
        verify(f.database, never()).lookupAsync(any(), anyInt(), anyInt(), anyInt(),
                anyInt(), anyInt(), anyInt());
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(f.operator, atLeastOnce()).sendMessage(messages.capture());
        assertTrue(messages.getAllValues().stream().anyMatch(s -> s.contains("either")));
    }

    @Test
    void aNewAreaLookupSupersedesAnUnfinishedPlayerLookup() {
        Fixture f = fixture();
        CompletableFuture<PlayerLookupPage> pending = new CompletableFuture<>();
        when(f.database.lookupPlayerAsync(eq("Builder"), eq(null),
                eq(1), eq(15), eq(0L), eq(30))).thenReturn(pending);
        when(f.database.lookupAsync(eq("world"), anyInt(), anyInt(), eq(15),
                eq(1), eq(15), eq(30))).thenReturn(
                        CompletableFuture.completedFuture(new LookupPage(List.of(), 1, 15, 0)));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(f.scheduler);
            runScheduledImmediately(f);
            f.command.onCommand(f.operator, mock(Command.class), "fg",
                    new String[]{"lookup", "u:Builder"});
            f.command.onCommand(f.operator, mock(Command.class), "fg",
                    new String[]{"lookup", "r:15"});
            pending.complete(new PlayerLookupPage(
                    new HistoricalPlayerResolver.HistoricalPlayerIdentity(PLAYER, "Builder"),
                    List.of(), 1, 15, 0));
        }
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(f.operator, atLeastOnce()).sendMessage(messages.capture());
        assertTrue(messages.getAllValues().stream().anyMatch(s -> s.contains("Radius:")));
        assertFalse(messages.getAllValues().stream().anyMatch(s -> s.contains("FragGuard Player Lookup")),
                "stale player results must not appear after radius lookup begins");
    }

    private static void runScheduledImmediately(Fixture f) {
        when(f.scheduler.runTask(eq(f.plugin), any(Runnable.class))).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return mock(BukkitTask.class);
        });
    }

    private static Fixture fixture() {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        Database database = mock(Database.class);
        Player operator = mock(Player.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        World world = mock(World.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("lookup-page-size", 15);
        config.set("max-lookup-radius", 150);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getRetentionDays()).thenReturn(30);
        when(operator.getUniqueId()).thenReturn(OPERATOR);
        when(operator.isOp()).thenReturn(true);
        when(operator.hasPermission("fragguard.admin")).thenReturn(true);
        when(operator.isOnline()).thenReturn(true);
        when(operator.getWorld()).thenReturn(world);
        when(operator.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(world.getName()).thenReturn("world");
        return new Fixture(plugin, database, operator, scheduler,
                new FragGuardCommand(plugin, database));
    }

    private record Fixture(FragGuardPlugin plugin, Database database,
                           Player operator, BukkitScheduler scheduler,
                           FragGuardCommand command) {
    }
}
