package org.pinnaclesmp.fragguard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Main-thread maintenance admission protection. PlayerLoginEvent runs after
 * Paper has a Player with usable permissions; AsyncPlayerPreLoginEvent does not.
 * A second check on join and a repeating online check cover phase transitions
 * and permission changes without reading permissions off the login thread.
 */
final class MaintenanceAdmissionGate implements Listener, AutoCloseable {
    private static final LegacyComponentSerializer INPUT =
            LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer OUTPUT =
            LegacyComponentSerializer.legacySection();

    private final JavaPlugin plugin;
    private final MaintenanceCoordinator coordinator;
    private final LongSupplier clock;
    private final Component kickReason;
    private final String loginDenial;
    private volatile boolean activationPending;
    private BukkitTask task;
    private boolean stopped;

    MaintenanceAdmissionGate(JavaPlugin plugin, MaintenanceCoordinator coordinator,
                             LongSupplier clock, String kickReason, String loginDenial) {
        this.plugin = Objects.requireNonNull(plugin);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.clock = Objects.requireNonNull(clock);
        this.kickReason = INPUT.deserialize(Objects.requireNonNull(kickReason));
        this.loginDenial = OUTPUT.serialize(INPUT.deserialize(Objects.requireNonNull(loginDenial)));
    }

    void start() {
        if (stopped) throw new IllegalStateException("Cannot restart closed maintenance admission gate");
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::enforceOnline, 0L, 20L);
        enforceOnline(); // Covers online players during an administrative plugin reload.
    }

    /**
     * Close the admission window *before* requesting a durable ACTIVE transition.
     * If the disk write fails, we deliberately retain the restriction until
     * an operator resolves/cancels the maintenance attempt.
     */
    boolean beginActivation(MaintenanceSnapshot countdown) {
        if (stopped || countdown.phase() != MaintenancePhase.COUNTDOWN) return false;
        MaintenanceSnapshot actual = coordinator.snapshot();
        if (actual.phase() != MaintenancePhase.COUNTDOWN
                || !actual.operationId().equals(countdown.operationId())
                || clock.getAsLong() < actual.countdownEndsAtMillis()) return false;
        activationPending = true;
        return true;
    }

    void activationCompleted() {
        activationPending = false;
        enforceOnline();
    }

    void activationFailed() {
        // Keep the admission gate closed even if the persisted transition
        // failed before the coordinator could publish the new state.
        activationPending = true;
        enforceOnline();
    }

    boolean restricted() {
        MaintenanceSnapshot state = coordinator.snapshot();
        // Close admissions at the actual persisted deadline, even if the
        // countdown display's next once-per-second tick has not run yet.
        // The coordinator's job gate additionally fails closed on uncertain
        // maintenance metadata writes.
        return activationPending || coordinator.blocksNewJobs()
                || (state.phase() == MaintenancePhase.COUNTDOWN
                    && clock.getAsLong() >= state.countdownEndsAtMillis());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        // PlayerLoginEvent is synchronous on Paper. Checking permissions in
        // AsyncPlayerPreLoginEvent would not be safe or authoritative.
        if (!stopped && restricted() && !MaintenanceNoticeDisplay.bypass(event.getPlayer())) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, loginDenial);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        // Fail closed if activation or a permission change occurs *between*
        // Paper's login authorization and the actual player join.
        if (!stopped && restricted() && !MaintenanceNoticeDisplay.bypass(event.getPlayer())) {
            event.getPlayer().kick(kickReason);
        }
    }

    /** Only call from the server thread: Bukkit players/permissions are not async-safe. */
    void enforceOnline() {
        if (stopped) return;
        MaintenancePhase phase = coordinator.snapshot().phase();
        if (phase == MaintenancePhase.CANCELLED || phase == MaintenancePhase.IDLE) {
            activationPending = false;
        }
        if (!restricted()) return;
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            if (!MaintenanceNoticeDisplay.bypass(player)) {
                player.kick(kickReason);
            }
        }
    }

    @Override
    public void close() {
        stopped = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
