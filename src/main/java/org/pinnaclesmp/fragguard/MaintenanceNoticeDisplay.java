package org.pinnaclesmp.fragguard;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Level;

/**
 * Bukkit main-thread presentation only: it reads committed maintenance state
 * and does not run SQLite, kick players or select replacement policy.
 *
 * The deadline callback is wired to the player-admission gate in issue #161.
 * Until admission control is implemented, the plugin cancels an expired
 * countdown rather than falsely claiming that players are restricted.
 */
final class MaintenanceNoticeDisplay implements Listener, AutoCloseable {
    private static final LegacyComponentSerializer LEGACY_INPUT =
            LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer LEGACY_OUTPUT =
            LegacyComponentSerializer.legacySection();

    private final JavaPlugin plugin;
    private final MaintenanceCoordinator coordinator;
    private final MaintenanceNoticeSettings settings;
    private final LongSupplier now;
    private final Consumer<MaintenanceSnapshot> onDeadline;
    private BukkitTask task;
    private BossBar countdownBar;
    private BossBar staffBar;
    private String lastOperationId = "";
    private MaintenancePhase lastPhase = MaintenancePhase.IDLE;
    private long previousRemainingMillis = Long.MAX_VALUE;
    private boolean deadlineDelivered;
    private boolean stopped;

    MaintenanceNoticeDisplay(JavaPlugin plugin, MaintenanceCoordinator coordinator,
                             MaintenanceNoticeSettings settings, LongSupplier now,
                             Consumer<MaintenanceSnapshot> onDeadline) {
        this.plugin = Objects.requireNonNull(plugin);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.settings = Objects.requireNonNull(settings);
        this.now = Objects.requireNonNull(now);
        this.onDeadline = Objects.requireNonNull(onDeadline);
    }

    void start() {
        if (stopped) throw new IllegalStateException("Cannot restart a closed maintenance display");
        if (task != null) return;
        // One synchronous update per second. All Boss Bar and Bukkit player
        // interactions stay on the main server thread.
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 20L);
    }

    /** Called only on Paper's main thread (scheduled task or unit test). */
    void tick() {
        if (stopped) return;
        MaintenanceSnapshot snapshot = coordinator.snapshot();
        if (!snapshot.operationId().equals(lastOperationId)) {
            lastOperationId = snapshot.operationId();
            previousRemainingMillis = Math.max(0L,
                    snapshot.countdownEndsAtMillis() - snapshot.requestedAtMillis()) + 1L;
            deadlineDelivered = false;
            lastPhase = MaintenancePhase.IDLE;
        }

        switch (snapshot.phase()) {
            case COUNTDOWN -> renderCountdown(snapshot);
            case ACTIVE, COMPACTING, VERIFYING, READY_TO_SWAP, FAILED -> {
                if (lastPhase == MaintenancePhase.COUNTDOWN
                        && snapshot.phase() == MaintenancePhase.ACTIVE) {
                    broadcast(settings.startedMessage());
                }
                renderStaff(snapshot.phase());
            }
            case CANCELLED, IDLE -> clearBars();
        }
        lastPhase = snapshot.phase();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (stopped) return;
        Player player = event.getPlayer();
        MaintenanceSnapshot snapshot = coordinator.snapshot();
        if (snapshot.phase() == MaintenancePhase.COUNTDOWN && settings.countdownBarEnabled()) {
            ensureCountdownBar();
            MaintenanceCountdownClock.Frame frame =
                    MaintenanceCountdownClock.frame(snapshot, now.getAsLong());
            updateCountdownBar(frame);
            countdownBar.addPlayer(player);
        } else if (staffStage(snapshot.phase()) && settings.staffBarEnabled() && bypass(player)) {
            ensureStaffBar();
            updateStaffBar(snapshot.phase());
            staffBar.addPlayer(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (countdownBar != null) countdownBar.removePlayer(event.getPlayer());
        if (staffBar != null) staffBar.removePlayer(event.getPlayer());
    }

    private void renderCountdown(MaintenanceSnapshot snapshot) {
        if (staffBar != null) staffBar.removeAll();
        MaintenanceCountdownClock.Frame frame =
                MaintenanceCountdownClock.frame(snapshot, now.getAsLong());
        if (settings.countdownBarEnabled()) {
            ensureCountdownBar();
            updateCountdownBar(frame);
            syncViewers(countdownBar, Bukkit.getOnlinePlayers());
        } else if (countdownBar != null) {
            countdownBar.removeAll();
        }

        int due = MaintenanceCountdownClock.crossedWarning(settings.warningSeconds(),
                snapshot.countdownEndsAtMillis() - snapshot.requestedAtMillis(),
                previousRemainingMillis, frame.remainingMillis());
        if (due >= 0) {
            broadcast(MaintenanceCountdownClock.expand(settings.warningMessage(), frame.displayTime()));
        }
        previousRemainingMillis = frame.remainingMillis();

        if (frame.remainingMillis() == 0 && !deadlineDelivered) {
            deadlineDelivered = true; // Never deliver the same deadline twice.
            try {
                onDeadline.accept(snapshot);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "Maintenance countdown deadline handler failed", exception);
                coordinator.fail("Maintenance deadline handler failed; administrator review required");
            }
        }
    }

    private void renderStaff(MaintenancePhase phase) {
        if (countdownBar != null) countdownBar.removeAll();
        if (!settings.staffBarEnabled()) {
            if (staffBar != null) staffBar.removeAll();
            return;
        }
        ensureStaffBar();
        updateStaffBar(phase);
        List<? extends Player> allowed = Bukkit.getOnlinePlayers().stream()
                .filter(MaintenanceNoticeDisplay::bypass)
                .toList();
        syncViewers(staffBar, allowed);
    }

    private void ensureCountdownBar() {
        if (countdownBar == null) {
            countdownBar = Bukkit.createBossBar("", settings.countdownColor(), BarStyle.SOLID);
        }
    }

    private void ensureStaffBar() {
        if (staffBar == null) {
            staffBar = Bukkit.createBossBar("", settings.activeColor(), BarStyle.SOLID);
        }
    }

    private void updateCountdownBar(MaintenanceCountdownClock.Frame frame) {
        countdownBar.setColor(settings.countdownColor());
        countdownBar.setProgress(frame.progress());
        countdownBar.setTitle(legacy(MaintenanceCountdownClock.expand(
                settings.countdownTitle(), frame.displayTime())));
    }

    private void updateStaffBar(MaintenancePhase phase) {
        BarColor color = switch (phase) {
            case COMPACTING, VERIFYING -> settings.compactingColor();
            case READY_TO_SWAP -> settings.readyColor();
            default -> settings.activeColor();
        };
        String title = switch (phase) {
            case COMPACTING -> settings.compactingTitle();
            case VERIFYING -> settings.verifyingTitle();
            case READY_TO_SWAP -> settings.readyTitle();
            case FAILED -> settings.failedTitle();
            default -> settings.activeTitle();
        };
        staffBar.setColor(color);
        // A status Boss Bar is not a progress meter: never show an invented
        // percentage for a long-running VACUUM/verification operation.
        staffBar.setProgress(1.0);
        staffBar.setTitle(legacy(title));
    }

    private static void syncViewers(BossBar bar, Collection<? extends Player> expected) {
        for (Player old : List.copyOf(bar.getPlayers())) {
            if (!expected.contains(old)) bar.removePlayer(old);
        }
        for (Player player : expected) {
            if (!bar.getPlayers().contains(player)) bar.addPlayer(player);
        }
    }

    static boolean bypass(Player player) {
        return player.isOp() || player.hasPermission("fragguard.maintenance.bypass");
    }

    private static boolean staffStage(MaintenancePhase phase) {
        return switch (phase) {
            case ACTIVE, COMPACTING, VERIFYING, READY_TO_SWAP, FAILED -> true;
            default -> false;
        };
    }

    void announcePostponed() {
        if (!stopped) broadcast(settings.postponedMessage());
    }

    private void broadcast(String configured) {
        if (configured == null || configured.isBlank()) return;
        Bukkit.getServer().broadcast(LEGACY_INPUT.deserialize(configured));
    }

    private static String legacy(String text) {
        return LEGACY_OUTPUT.serialize(LEGACY_INPUT.deserialize(text));
    }

    private void clearBars() {
        if (countdownBar != null) countdownBar.removeAll();
        if (staffBar != null) staffBar.removeAll();
    }

    @Override
    public void close() {
        stopped = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
        clearBars();
        countdownBar = null;
        staffBar = null;
    }
}
