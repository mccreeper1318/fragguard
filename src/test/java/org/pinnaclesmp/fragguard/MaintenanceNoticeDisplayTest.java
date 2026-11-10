package org.pinnaclesmp.fragguard;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MaintenanceNoticeDisplayTest {
    private static final long START = 1_800_000_000_000L;
    private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void oneYellowBossBarTracksDeadlineWarnsOnceAndOnlyExpiresOnce() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        MaintenanceCoordinator coordinator = mock(MaintenanceCoordinator.class);
        AtomicLong time = new AtomicLong(START);
        var scheduled = MaintenanceSnapshot.scheduled(ADMIN, START, START + 600_000L,
                MaintenancePolicy.STRICT);
        when(coordinator.snapshot()).thenReturn(scheduled);

        Player staff = player(true, false);
        Player regular = player(false, false);
        List<Player> online = new ArrayList<>(List.of(staff, regular));
        BossBar countdown = bar();
        Server server = mock(Server.class);
        AtomicInteger expirations = new AtomicInteger();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(ignored -> List.copyOf(online));
            bukkit.when(() -> Bukkit.createBossBar(anyString(), any(BarColor.class),
                    eq(BarStyle.SOLID))).thenReturn(countdown);

            try (var display = new MaintenanceNoticeDisplay(plugin, coordinator,
                    MaintenanceNoticeSettings.from(new YamlConfiguration()), time::get,
                    ignored -> expirations.incrementAndGet())) {
                display.tick();
                verify(countdown).setProgress(1.0);
                verify(countdown).setColor(BarColor.YELLOW);
                verify(countdown).setTitle(contains("10:00"));
                assertTrue(countdown.getPlayers().containsAll(online));
                verify(server, times(1)).broadcast(any(Component.class));
                display.tick();
                verify(server, times(1)).broadcast(any(Component.class));

                time.set(START + 300_000L);
                display.tick();
                verify(countdown).setProgress(0.5);
                verify(countdown).setTitle(contains("05:00"));
                verify(server, times(2)).broadcast(any(Component.class));
                display.tick();
                verify(server, times(2)).broadcast(any(Component.class),
                        "a repeated tick must not duplicate five-minute chat");

                time.set(START + 600_000L);
                display.tick();
                verify(countdown).setProgress(0.0);
                assertEquals(1, expirations.get());
                display.tick();
                assertEquals(1, expirations.get(), "deadline callback executes exactly once");
            }
        }
    }

    @Test
    void lateJoinersAndChangingPermissionsSeeCorrectBossBarAndStaffStages() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        MaintenanceCoordinator coordinator = mock(MaintenanceCoordinator.class);
        AtomicLong time = new AtomicLong(START + 240_000L);
        var scheduled = MaintenanceSnapshot.scheduled(ADMIN, START, START + 600_000L,
                MaintenancePolicy.STRICT);
        AtomicReference<MaintenanceSnapshot> state = new AtomicReference<>(scheduled);
        when(coordinator.snapshot()).thenAnswer(ignored -> state.get());

        Player staff = player(true, false);
        Player regular = player(false, false);
        Player newJoin = player(false, false);
        Player trusted = player(false, true);
        List<Player> online = new ArrayList<>(List.of(staff, regular));
        BossBar countdown = bar();
        BossBar status = bar();
        Server server = mock(Server.class);
        AtomicInteger creations = new AtomicInteger();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(ignored -> List.copyOf(online));
            bukkit.when(() -> Bukkit.createBossBar(anyString(), any(BarColor.class),
                    eq(BarStyle.SOLID))).thenAnswer(ignored ->
                        creations.incrementAndGet() == 1 ? countdown : status);
            try (var display = new MaintenanceNoticeDisplay(plugin, coordinator,
                    MaintenanceNoticeSettings.from(new YamlConfiguration()),
                    time::get, ignored -> fail("do not expire early"))) {
                display.tick();
                assertTrue(countdown.getPlayers().contains(staff));
                assertTrue(countdown.getPlayers().contains(regular));

                PlayerJoinEvent late = mock(PlayerJoinEvent.class);
                when(late.getPlayer()).thenReturn(newJoin);
                online.add(newJoin);
                display.onJoin(late);
                verify(countdown, atLeastOnce()).setTitle(contains("06:00"));
                assertTrue(countdown.getPlayers().contains(newJoin));
                verify(server, never()).broadcast(any(Component.class)),
                        "late join must not restart or rebroadcast the countdown";

                // When #161 activates the persisted maintenance phase, the
                // shared yellow countdown is replaced with staff-only red.
                state.set(scheduled.withPhase(MaintenancePhase.ACTIVE, "admission now restricted"));
                display.tick();
                assertTrue(countdown.getPlayers().isEmpty());
                verify(status).setColor(BarColor.RED);
                assertEquals(List.of(staff), status.getPlayers());
                verify(status).setProgress(1.0);

                PlayerJoinEvent bypassJoin = mock(PlayerJoinEvent.class);
                when(bypassJoin.getPlayer()).thenReturn(trusted);
                online.add(trusted);
                display.onJoin(bypassJoin);
                assertTrue(status.getPlayers().contains(trusted),
                        "staff with bypass sees the status on reconnect");
                assertFalse(status.getPlayers().contains(newJoin));

                state.set(state.get().withCandidate("fragguard-compact-test.db",
                        "compacting"));
                display.tick();
                verify(status).setColor(BarColor.BLUE);
                state.set(state.get().withCutoff(START + 250_000L, "snapshot cutoff"));
                state.set(state.get().withPhase(MaintenancePhase.VERIFYING, "verifying"));
                display.tick();
                verify(status, atLeastOnce()).setTitle(contains("VERIFYING"));

                state.set(state.get().withManifest("fragguard-compact-test.manifest", "verified"));
                state.set(state.get().withPhase(MaintenancePhase.READY_TO_SWAP, "ready"));
                display.tick();
                verify(status).setColor(BarColor.GREEN);
                verify(status).setTitle(contains("RESTART REQUIRED"));

                when(trusted.hasPermission("fragguard.maintenance.bypass")).thenReturn(false);
                display.tick();
                assertFalse(status.getPlayers().contains(trusted),
                        "permission revocation removes the staff-only indicator");
                PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
                when(quit.getPlayer()).thenReturn(staff);
                display.onQuit(quit);
                assertFalse(status.getPlayers().contains(staff));
            }
            verify(status, atLeastOnce()).removeAll();
        }
    }

    @Test
    void schedulerUsesMainThreadTimerAndCancelsOnShutdown() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        MaintenanceCoordinator coordinator = mock(MaintenanceCoordinator.class);
        when(coordinator.snapshot()).thenReturn(MaintenanceSnapshot.idle());
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(0L), eq(20L)))
                .thenReturn(task);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            var display = new MaintenanceNoticeDisplay(plugin, coordinator,
                    MaintenanceNoticeSettings.from(new YamlConfiguration()),
                    () -> START, ignored -> fail("idle cannot expire"));
            display.start();
            display.start();
            verify(scheduler, times(1)).runTaskTimer(eq(plugin), any(Runnable.class),
                    eq(0L), eq(20L));
            display.close();
            verify(task).cancel();
            assertThrows(IllegalStateException.class, display::start);
        }
    }

    @Test
    void disabledBossBarStillAllowsCountdownWarnings() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        MaintenanceCoordinator coordinator = mock(MaintenanceCoordinator.class);
        when(coordinator.snapshot()).thenReturn(MaintenanceSnapshot.scheduled(
                ADMIN, START, START + 600_000L, MaintenancePolicy.STRICT));
        YamlConfiguration settings = new YamlConfiguration();
        settings.set("maintenance-countdown-bossbar-enabled", false);
        Server server = mock(Server.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            var display = new MaintenanceNoticeDisplay(plugin, coordinator,
                    MaintenanceNoticeSettings.from(settings), () -> START,
                    ignored -> fail("countdown has not expired"));
            display.tick();
            verify(server).broadcast(any(Component.class));
            bukkit.verify(() -> Bukkit.createBossBar(anyString(), any(BarColor.class),
                    any(BarStyle.class)), never());
            display.close();
        }
    }

    private static Player player(boolean isOp, boolean bypass) {
        Player player = mock(Player.class);
        when(player.isOp()).thenReturn(isOp);
        if (!isOp) when(player.hasPermission("fragguard.maintenance.bypass")).thenReturn(bypass);
        return player;
    }

    private static BossBar bar() {
        BossBar bar = mock(BossBar.class);
        List<Player> players = new ArrayList<>();
        when(bar.getPlayers()).thenAnswer(ignored -> List.copyOf(players));
        doAnswer(inv -> {
            Player player = inv.getArgument(0);
            if (!players.contains(player)) players.add(player);
            return null;
        }).when(bar).addPlayer(any(Player.class));
        doAnswer(inv -> {
            players.remove(inv.getArgument(0));
            return null;
        }).when(bar).removePlayer(any(Player.class));
        doAnswer(inv -> {
            players.clear();
            return null;
        }).when(bar).removeAll();
        return bar;
    }
}
