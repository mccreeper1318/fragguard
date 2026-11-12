package org.pinnaclesmp.fragguard;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MaintenanceAdmissionGateTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String KICK = "&cMaintenance is active. Please come back later.";
    private static final String DENY = "&cServer maintenance is underway.";

    @Test
    void allowsEveryoneDuringCountdownAndDeniesOnlyUntrustedAfterActivation() {
        MaintenanceCoordinator coordinator = mock(MaintenanceCoordinator.class);
        MaintenanceSnapshot countdown = MaintenanceSnapshot.scheduled(
                ADMIN, NOW, NOW + 600_000L, MaintenancePolicy.STRICT);
        when(coordinator.snapshot()).thenReturn(countdown);
        when(coordinator.blocksNewJobs()).thenAnswer(ignored ->
                coordinator.snapshot().blocksNewJobs());
        Player ordinary = player(false, false);
        Player operator = player(true, false);
        Player delegated = player(false, true);
        Player adminOnly = player(false, false);
        when(adminOnly.hasPermission("fragguard.admin")).thenReturn(true);
        AtomicLong clock = new AtomicLong(NOW + 1);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             var gate = gate(coordinator, clock::get)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            assertAllowed(gate, ordinary);
            assertFalse(gate.beginActivation(countdown), "cannot activate before persisted deadline");
            clock.set(countdown.countdownEndsAtMillis());
            assertTrue(gate.beginActivation(countdown));
            assertFalse(gate.beginActivation(MaintenanceSnapshot.idle()));
            assertDenied(gate, ordinary);
            assertDenied(gate, adminOnly); // Admin commands do not implicitly bypass.
            assertAllowed(gate, operator);
            assertAllowed(gate, delegated);
            when(coordinator.snapshot()).thenReturn(countdown.withPhase(
                    MaintenancePhase.ACTIVE, "active"));
            gate.activationCompleted();
            assertDenied(gate, ordinary);
            assertAllowed(gate, operator);
        }
    }

    @Test
    void expiredCountdownDeniesLoginAndJoinBeforeDisplayDeadlineCallback() {
        MaintenanceCoordinator coordinator = mock(MaintenanceCoordinator.class);
        MaintenanceSnapshot countdown = MaintenanceSnapshot.scheduled(
                ADMIN, NOW, NOW + 600_000L, MaintenancePolicy.STRICT);
        AtomicReference<MaintenanceSnapshot> state = new AtomicReference<>(countdown);
        AtomicLong clock = new AtomicLong(NOW + 599_999L);
        when(coordinator.snapshot()).thenAnswer(ignored -> state.get());
        when(coordinator.blocksNewJobs()).thenAnswer(ignored -> state.get().blocksNewJobs());

        Player regular = player(false, false);
        Player staff = player(false, true);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             var gate = gate(coordinator, clock::get)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            assertAllowed(gate, regular);
            assertFalse(gate.restricted());
            clock.incrementAndGet(); // deadline reached; no display tick yet
            assertTrue(gate.restricted());
            assertDenied(gate, regular);
            assertAllowed(gate, staff);

            PlayerJoinEvent lateJoin = mock(PlayerJoinEvent.class);
            when(lateJoin.getPlayer()).thenReturn(regular);
            gate.onJoin(lateJoin);
            verify(regular).kick(any(Component.class));
            assertTrue(gate.beginActivation(countdown));
            state.set(countdown.withPhase(MaintenancePhase.ACTIVE, "active"));
            gate.activationCompleted();
            assertDenied(gate, regular);

            state.set(state.get().withPhase(MaintenancePhase.CANCELLED, "cancelled"));
            gate.enforceOnline(); // cancellation clears pending and admits players
            assertAllowed(gate, regular);
            assertFalse(gate.restricted());
        }
    }

    @Test
    void joinRaceAndPeriodicEnforcementHandlePermissionRevocation() {
        MaintenanceCoordinator coordinator = mock(MaintenanceCoordinator.class);
        MaintenanceSnapshot countdown = MaintenanceSnapshot.scheduled(
                ADMIN, NOW, NOW + 600_000L, MaintenancePolicy.STRICT);
        when(coordinator.snapshot()).thenReturn(countdown.withPhase(
                MaintenancePhase.ACTIVE, "active"));
        when(coordinator.blocksNewJobs()).thenReturn(true);
        Player staff = player(false, true);
        Player ordinary = player(false, false);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             var gate = gate(coordinator)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(staff, ordinary));
            gate.enforceOnline();
            verify(ordinary).kick(any(Component.class));
            verify(staff, never()).kick(any(Component.class));

            PlayerJoinEvent lateJoin = mock(PlayerJoinEvent.class);
            when(lateJoin.getPlayer()).thenReturn(ordinary);
            gate.onJoin(lateJoin);
            verify(ordinary, times(2)).kick(any(Component.class));

            when(staff.hasPermission("fragguard.maintenance.bypass")).thenReturn(false);
            gate.enforceOnline();
            verify(staff).kick(any(Component.class));
            assertDenied(gate, staff);
        }
    }

    @Test
    void persistsRestrictionThroughAllMaintenanceStagesAndFailure() {
        MaintenanceCoordinator coordinator = mock(MaintenanceCoordinator.class);
        MaintenanceSnapshot countdown = MaintenanceSnapshot.scheduled(
                ADMIN, NOW, NOW + 600_000L, MaintenancePolicy.STRICT);
        var phase = new AtomicReference<>(MaintenancePhase.COUNTDOWN);
        when(coordinator.snapshot()).thenAnswer(ignored ->
                phase.get() == MaintenancePhase.COUNTDOWN ? countdown :
                        phase.get() == MaintenancePhase.CANCELLED
                                ? countdown.withPhase(MaintenancePhase.CANCELLED, "cancelled")
                                : countdown.withPhase(MaintenancePhase.ACTIVE, "active"));
        when(coordinator.blocksNewJobs()).thenAnswer(ignored ->
                phase.get().blocksNewJobs());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             var gate = gate(coordinator)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            assertTrue(gate.beginActivation(countdown));
            gate.activationFailed();
            assertTrue(gate.restricted(), "failed disk write must not reopen the login gate");
            for (MaintenancePhase locked : List.of(MaintenancePhase.ACTIVE,
                    MaintenancePhase.COMPACTING, MaintenancePhase.VERIFYING,
                    MaintenancePhase.READY_TO_SWAP, MaintenancePhase.FAILED)) {
                phase.set(locked);
                assertTrue(gate.restricted(), locked + " must deny access");
            }
            phase.set(MaintenancePhase.CANCELLED);
            gate.enforceOnline();
            assertFalse(gate.restricted(), "explicit cancellation reopens admissions");
        }
    }

    @Test
    void loginAndKickTextIsConfigurableAndCloseCancelsRevalidation() {
        MaintenanceCoordinator coordinator = mock(MaintenanceCoordinator.class);
        when(coordinator.snapshot()).thenReturn(MaintenanceSnapshot.idle());
        when(coordinator.blocksNewJobs()).thenReturn(false);
        JavaPlugin plugin = mock(JavaPlugin.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(0L), eq(20L)))
                .thenReturn(task);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            var gate = new MaintenanceAdmissionGate(plugin, coordinator, () -> NOW, KICK, DENY);
            gate.start();
            gate.start();
            verify(scheduler, times(1)).runTaskTimer(eq(plugin), any(Runnable.class),
                    eq(0L), eq(20L));
            gate.close();
            verify(task).cancel();
            assertThrows(IllegalStateException.class, gate::start);
        }
    }

    private static void assertAllowed(MaintenanceAdmissionGate gate, Player player) {
        PlayerLoginEvent login = mock(PlayerLoginEvent.class);
        when(login.getPlayer()).thenReturn(player);
        gate.onLogin(login);
        verify(login, never()).disallow(any(PlayerLoginEvent.Result.class), anyString());
    }

    private static void assertDenied(MaintenanceAdmissionGate gate, Player player) {
        PlayerLoginEvent login = mock(PlayerLoginEvent.class);
        when(login.getPlayer()).thenReturn(player);
        gate.onLogin(login);
        verify(login).disallow(eq(PlayerLoginEvent.Result.KICK_OTHER),
                contains("Server maintenance"));
    }

    private static MaintenanceAdmissionGate gate(MaintenanceCoordinator coordinator) {
        return gate(coordinator, () -> NOW + 600_000L);
    }

    private static MaintenanceAdmissionGate gate(MaintenanceCoordinator coordinator,
                                                java.util.function.LongSupplier clock) {
        return new MaintenanceAdmissionGate(mock(JavaPlugin.class), coordinator, clock, KICK, DENY);
    }

    private static Player player(boolean operator, boolean delegated) {
        Player player = mock(Player.class);
        when(player.isOp()).thenReturn(operator);
        if (!operator) {
            when(player.hasPermission("fragguard.maintenance.bypass")).thenReturn(delegated);
        }
        return player;
    }
}
