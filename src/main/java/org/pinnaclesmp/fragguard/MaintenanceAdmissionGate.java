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

import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.logging.Level;

/**
 * Main-thread player admission gate. PlayerLoginEvent is synchronous, unlike
 * AsyncPlayerPreLoginEvent, so live Bukkit permissions are safe to inspect.
 * A join guard and periodic sweep also handle permission changes and plugins
 * that alter a login decision after our login listener has run.
 */
final class MaintenanceAdmissionGate implements Listener, AutoCloseable {
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private final JavaPlugin plugin;
    private final MaintenanceCoordinator coordinator;
    private final LongSupplier clock;
    private final Component kickMessage;
    private final Component loginMessage;
    private BukkitTask sweepTask;
    private boolean activationPending;
    private boolean closed;

    MaintenanceAdmissionGate(JavaPlugin plugin, MaintenanceCoordinator coordinator,
                             LongSupplier clock, String kickText, String loginText) {
        this.plugin = Objects.requireNonNull(plugin);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.clock = Objects.requireNonNull(clock);
        this.kickMessage = LEGACY.deserialize(Objects.requireNonNull(kickText));
        this.loginMessage = LEGACY.deserialize(Objects.requireNonNull(loginText));
    }

    void start() {
        if (closed) throw new IllegalStateException("Maintenance admission gate is closed");
        if (sweepTask != null) return;
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 0L, 20L);
    }

    /**
     * Called by the countdown display on the primary server thread. Close the
     * gate before the asynchronous durable ACTIVE transition to prevent a gap
     * where players can enter after the advertised deadline.
     */
    void activateAtDeadline(MaintenanceSnapshot expired) {
        if (closed || activationPending) return;
        MaintenanceSnapshot current = coordinator.snapshot();
        if (current.phase() != MaintenancePhase.COUNTDOWN
                || !current.operationId().equals(expired.operationId())
                || clock.getAsLong() < current.countdownEndsAtMillis()) return;
        activationPending = true;
        sweep();
        coordinator.activate().whenComplete((saved, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not persist maintenance activation; denying ordinary access until "
                                + "an administrator resolves the maintenance state.", failure);
            }
            // State is published only after the durable commit. Player state,
            // notifications and permissions must be touched on the main thread.
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, this::sweep);
            }
        });
    }

    boolean admissionRestricted() {
        MaintenanceSnapshot state = coordinator.snapshot();
        return activationPending || switch (state.phase()) {
            case ACTIVE, COMPACTING, VERIFYING, READY_TO_SWAP, FAILED -> true;
            case COUNTDOWN -> clock.getAsLong() >= state.countdownEndsAtMillis();
            default -> false;
        };
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        if (!closed && admissionRestricted() && !MaintenanceNoticeDisplay.bypass(event.getPlayer())) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, loginMessage);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        if (!closed && admissionRestricted() && !MaintenanceNoticeDisplay.bypass(event.getPlayer())) {
            event.getPlayer().kick(kickMessage);
        }
    }

    void sweep() {
        if (closed || !admissionRestricted()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!MaintenanceNoticeDisplay.bypass(player)) {
                player.kick(kickMessage);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
    }
}
