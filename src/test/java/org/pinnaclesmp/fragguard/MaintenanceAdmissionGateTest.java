package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MaintenanceAdmissionGateTest {
    private static final long START = 1_800_000_000_000L;
    private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static Player player(boolean op, boolean permitted) {
        Player player = mock(Player.class);
        when(player.isOp()).thenReturn(op);
        if (!op) when(player.hasPermission("fragguard.maintenance.bypass")).thenReturn(permitted);
        return player;
    }

    @Test
    void loginGateHonorsOperatorAndPermissionAndRevocation() {
        var phase = new AtomicReference<>(MaintenanceSnapshot.scheduled(
                ADMIN, START, START + 60_000, MaintenancePolicy.STRICT)
                .withPhase(MaintenancePhase.ACTIVE, "active"));
        MaintenanceCoordinator coordinator = mock(MaintenanceCoordinator.class);
        when(coordinator.snapshot()).thenAnswer(ignored -> phase.get());
        Player op = player(true, false);
        Player staff = player(false, true);
        Player regular = player(false, false);
        try (var gate = new MaintenanceAdmissionGate(mock(JavaPlugin.class), coordinator,
                () -> START, "&cMaintenance", "&cTry later")) {
            PlayerLoginEvent denied = mock(PlayerLoginEvent.class);
            when(denied.getPlayer()).thenReturn(regular);
            gate.onLogin(denied);
            verify(denied).disallow(eq(PlayerLoginEvent.Result.KICK_OTHER), any(net.kyori.adventure.text.Component.class));

            PlayerLoginEvent allowedOp = mock(PlayerLoginEvent.class);
            when(allowedOp.getPlayer()).thenReturn(op);
            gate.onLogin(allowedOp);
            verify(allowedOp, never()).disallow(any(), any(net.kyori.adventure.text.Component.class));

            PlayerLoginEvent allowedStaff = mock(PlayerLoginEvent.class);
            when(allowedStaff.getPlayer()).thenReturn(staff);
            gate.onLogin(allowedStaff);
            verify(allowedStaff, never()).disallow(any(), any(net.kyori.adventure.text.Component.class));

            when(staff.hasPermission("fragguard.maintenance.bypass")).thenReturn(false);
            gate.onLogin(allowedStaff);
            verify(allowedStaff).disallow(eq(PlayerLoginEvent.Result.KICK_OTHER),
                    any(net.kyori.adventure.text.Component.class));

            phase.set(phase.get().withPhase(MaintenancePhase.CANCELLED, "cancelled"));
            gate.onLogin(denied);
            verify(denied, times(1)).disallow(any(), any(net.kyori.adventure.text.Component.class));
        }
    }

    @Test
    void expiredCountdownDeniesEntryBeforeDurableActivation() {
        MaintenanceCoordinator coordinator = mock(MaintenanceCoordinator.class);
        when(coordinator.snapshot()).thenReturn(MaintenanceSnapshot.scheduled(
                ADMIN, START, START + 60_000, MaintenancePolicy.STRICT));
        AtomicLong now = new AtomicLong(START + 59_999);
        Player regular = player(false, false);
        try (var gate = new MaintenanceAdmissionGate(mock(JavaPlugin.class), coordinator,
                now::get, "kicked", "maintenance")) {
            PlayerLoginEvent login = mock(PlayerLoginEvent.class);
            when(login.getPlayer()).thenReturn(regular);
            gate.onLogin(login);
            verify(login, never()).disallow(any(), any(net.kyori.adventure.text.Component.class));
            now.incrementAndGet();
            gate.onLogin(login);
            verify(login).disallow(eq(PlayerLoginEvent.Result.KICK_OTHER),
                    any(net.kyori.adventure.text.Component.class));

            PlayerJoinEvent join = mock(PlayerJoinEvent.class);
            when(join.getPlayer()).thenReturn(regular);
            gate.onJoin(join);
            verify(regular).kick(any(net.kyori.adventure.text.Component.class));
        }
    }

    @Test
    void deadlineEvictsRegularPlayersAndPeriodicallyEnforcesRevocation() {
        MaintenanceSnapshot countdown = MaintenanceSnapshot.scheduled(
                ADMIN, START, START + 60_000, MaintenancePolicy.STRICT);
        AtomicReference<MaintenanceSnapshot> snapshot = new AtomicReference<>(countdown);
        MaintenanceCoordinator coordinator = mock(MaintenanceCoordinator.class);
        when(coordinator.snapshot()).thenAnswer(ignored -> snapshot.get());
        when(coordinator.activate()).thenAnswer(ignored -> {
            MaintenanceSnapshot active = countdown.withPhase(MaintenancePhase.ACTIVE, "activated");
            snapshot.set(active);
            return CompletableFuture.completedFuture(active);
        });
        Player normal = player(false, false);
        Player op = player(true, false);
        Player staff = player(false, true);
        JavaPlugin plugin = mock(JavaPlugin.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             var gate = new MaintenanceAdmissionGate(plugin, coordinator,
                     () -> START + 60_000, "kick", "login")) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(normal, op, staff));
            // The plugin may be disabled during asynchronous completion; the
            // initial synchronous sweep must still remove ordinary players.
            gate.activateAtDeadline(countdown);
            verify(normal).kick(any(net.kyori.adventure.text.Component.class));
            verify(op, never()).kick(any(net.kyori.adventure.text.Component.class));
            verify(staff, never()).kick(any(net.kyori.adventure.text.Component.class));
            verify(coordinator, times(1)).activate();
            gate.activateAtDeadline(countdown);
            verify(coordinator, times(1)).activate();

            when(staff.hasPermission("fragguard.maintenance.bypass")).thenReturn(false);
            gate.sweep();
            verify(staff).kick(any(net.kyori.adventure.text.Component.class));
        }
    }
}
