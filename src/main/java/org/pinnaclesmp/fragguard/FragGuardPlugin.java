package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class FragGuardPlugin extends JavaPlugin {
    static final int DEFAULT_GUI_LOOKUP_FETCH_SIZE = 1000;

    private MaintenanceCoordinator maintenance;
    private MaintenanceNoticeDisplay maintenanceNoticeDisplay;
    private MaintenanceAdmissionGate maintenanceAdmissionGate;
    private Database database;
    private DatabaseQuickDiagnostics quickDiagnostics;
    private DatabaseDeepDiagnostics deepDiagnostics;
    private CompactionStoragePreflight compactionPreflight;
    private OnlineDatabaseCompaction onlineCompaction;
    private FragGuardCommand commandExecutor;
    private BlockChangeListener changeListener;
    private boolean storageWarningActive;
    private long lastStorageWarningAt;
    private int guiLookupFetchSize = DEFAULT_GUI_LOOKUP_FETCH_SIZE;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            maintenance = MaintenanceCoordinator.open(getDataFolder().toPath(), getLogger()::info);
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE,
                    "Could not read safe maintenance metadata; do not edit or delete the file blindly. "
                    + "FragGuard will not open the database until this is resolved.", exception);
            // Unknown maintenance metadata can represent a locked server.
            // Fail closed rather than silently allowing ordinary players.
            Bukkit.shutdown();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (maintenance.snapshot().phase() != MaintenancePhase.IDLE) {
            getLogger().warning("FragGuard maintenance state: " + maintenance.snapshot().phase()
                    + " — " + maintenance.snapshot().detail()
                    + ". The database has not been automatically replaced or reset.");
        }
        int configuredGuiLookupFetchSize = getConfig().getInt(
                "gui-lookup-fetch-size", DEFAULT_GUI_LOOKUP_FETCH_SIZE);
        guiLookupFetchSize = resolveGuiLookupFetchSize(configuredGuiLookupFetchSize);
        if (configuredGuiLookupFetchSize <= 0) {
            getLogger().warning("Invalid gui-lookup-fetch-size value " + configuredGuiLookupFetchSize
                    + "; the value must be at least 1. Using the default of "
                    + DEFAULT_GUI_LOOKUP_FETCH_SIZE + ".");
        }
        database = new Database(this);
        try {
            database.init();
        } catch (SQLException | ClassNotFoundException exception) {
            getLogger().log(Level.SEVERE, "FragGuard could not start because SQLite failed to initialize.", exception);
            if (maintenance.blocksNewJobs()) {
                getLogger().severe("Cannot protect active maintenance with FragGuard disabled. Stopping Paper.");
                Bukkit.shutdown();
            }
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        quickDiagnostics = new DatabaseQuickDiagnostics(
                getDataFolder().toPath().resolve("fragguard.db"));
        deepDiagnostics = new DatabaseDeepDiagnostics(getDataFolder().toPath());
        compactionPreflight = new CompactionStoragePreflight(getDataFolder().toPath());
        onlineCompaction = new OnlineDatabaseCompaction(
                getDataFolder().toPath(), maintenance, compactionPreflight);
        try {
            maintenanceAdmissionGate = new MaintenanceAdmissionGate(this, maintenance,
                    System::currentTimeMillis,
                    getConfig().getString("maintenance-kick-message",
                            "&cMaintenance is active. Please return later."),
                    getConfig().getString("maintenance-login-denied-message",
                            "&cThe server is undergoing database maintenance. Please return later."));
            Bukkit.getPluginManager().registerEvents(maintenanceAdmissionGate, this);
            maintenanceAdmissionGate.start();
            maintenanceNoticeDisplay = new MaintenanceNoticeDisplay(
                    this, maintenance, MaintenanceNoticeSettings.from(getConfig()),
                    System::currentTimeMillis, this::activateMaintenanceAtDeadline);
            Bukkit.getPluginManager().registerEvents(maintenanceNoticeDisplay, this);
            maintenanceNoticeDisplay.start();
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "FragGuard maintenance admission protection failed to initialize.", exception);
            if (maintenance.blocksNewJobs()) {
                getLogger().severe("Maintenance access must not run without an admission gate. Stopping Paper.");
                Bukkit.shutdown();
            }
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        changeListener = new BlockChangeListener(this, database);
        Bukkit.getPluginManager().registerEvents(changeListener, this);
        Bukkit.getPluginManager().registerEvents(new ShelfChangeListener(this, database), this);
        Bukkit.getPluginManager().registerEvents(new FragGuardGui(this, database), this);
        commandExecutor = new FragGuardCommand(this, database);
        Bukkit.getPluginManager().registerEvents(commandExecutor, this);
        PluginCommand command = Objects.requireNonNull(getCommand("fg"), "Command /fg is missing from plugin.yml");
        command.setExecutor(commandExecutor);
        command.setTabCompleter(commandExecutor);
        commandExecutor.resumeInterruptedJobs();
        scheduleCleanup();
        scheduleStorageHealthMonitor();
        getLogger().info("FragGuard enabled. Block changes are retained for " + getRetentionDays() + " days.");
    }

    @Override
    public void onDisable() {
        if (maintenanceAdmissionGate != null) {
            boolean wasRestricted = maintenanceAdmissionGate.restricted();
            maintenanceAdmissionGate.close();
            maintenanceAdmissionGate = null;
            if (wasRestricted) {
                getLogger().severe("FragGuard disabled while maintenance access is restricted. "
                        + "Stopping Paper rather than exposing an unguarded server.");
                Bukkit.shutdown();
            }
        }
        if (maintenanceNoticeDisplay != null) {
            maintenanceNoticeDisplay.close();
            maintenanceNoticeDisplay = null;
        }
        if (changeListener != null) {
            changeListener.clearCausalAttribution();
            changeListener = null;
        }
        if (commandExecutor != null) {
            commandExecutor.clearRollbackPreviews();
            commandExecutor = null;
        }

        // Stop VACUUM INTO first, before the preflight reservation guard, the
        // state coordinator, and the live database writer are shut down.
        if (onlineCompaction != null) {
            try {
                onlineCompaction.close();
            } catch (IllegalStateException exception) {
                getLogger().log(Level.SEVERE, "Online SQLite compaction could not be stopped safely. "
                        + "Preserve the active DB, WAL/SHM and unverified candidate.", exception);
                Bukkit.shutdown();
            } finally {
                onlineCompaction = null;
            }
        }
        if (compactionPreflight != null) {
            compactionPreflight.close();
            compactionPreflight = null;
        }
        // Stop full-history read transactions before closing the live SQLite
        // writer, so long readers cannot retain WAL frames at shutdown.
        if (deepDiagnostics != null) {
            deepDiagnostics.close();
            deepDiagnostics = null;
        }
        // Close any read-only diagnostics before the SQLite writer shuts down
        // and checkpoints its WAL. This does not alter the live database.
        if (quickDiagnostics != null) {
            quickDiagnostics.close();
            quickDiagnostics = null;
        }

        // Keep maintenance and its blocking state available while Database
        // drains queued operations. They recheck maintenanceBlocksNewJobs()
        // even during shutdown. Clearing it before shutdown could turn a
        // queued, forbidden rollback or undo into an accepted job.
        try {
            if (database != null) {
                DatabaseShutdownSnapshot before = database.shutdown();
                DatabaseHealth after = database.health();
                StorageShutdownReport report = StorageShutdownSupport.finish(
                        before, after, database.completedWrites(),
                        database.workerStopped() ? 0 : database.inFlightWrites(),
                        database.workerStopped(), database.walCheckpointCompleted());
                String summary = "FragGuard storage shutdown: queued=" + report.queuedWritesAtStart()
                        + ", drained=" + report.drainedWrites()
                        + ", remaining=" + report.remainingWrites()
                        + ", remaining operations=" + report.remainingOperations()
                        + ", unconfirmed active operations=" + report.unconfirmedOperations()
                        + ", lost during shutdown=" + report.lostDuringShutdown()
                        + ", unconfirmed in-flight writes=" + report.unconfirmedWrites()
                        + ", total dropped this session=" + report.totalDroppedWrites()
                        + ", worker stopped=" + report.workerStopped()
                        + ", WAL checkpoint=" + (report.walCheckpointCompleted() ? "complete" : "FAILED");
                if (report.clean()) getLogger().info(summary);
                else {
                    getLogger().warning(summary);
                    if (!after.lastError().isBlank())
                        getLogger().warning("FragGuard final storage error: " + after.lastError());
                }
            }
        } finally {
            if (maintenance != null) {
                try {
                    maintenance.close();
                } catch (IllegalStateException exception) {
                    getLogger().log(Level.SEVERE, "Could not flush maintenance metadata on shutdown", exception);
                }
                // Do not null the reference. If the database worker failed to
                // stop, any lingering queued operation must still see the
                // active maintenance gate until the JVM actually terminates.
            }
        }
    }

    /**
     * Close logins on the main thread before requesting the persisted
     * COUNTDOWN -> ACTIVE transition on the maintenance state worker.
     */
    private void activateMaintenanceAtDeadline(MaintenanceSnapshot expired) {
        MaintenanceAdmissionGate gate = maintenanceAdmissionGate;
        if (gate == null || !gate.beginActivation(expired)) return;
        maintenance.activate().whenComplete((active, failure) -> {
            if (!isEnabled()) return;
            try {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (maintenanceAdmissionGate == null) return;
                    if (failure != null || active == null || active.phase() != MaintenancePhase.ACTIVE) {
                        getLogger().log(Level.SEVERE, "Could not persist maintenance activation; "
                                + "admissions remain closed until an operator resolves the failure", failure);
                        maintenanceAdmissionGate.activationFailed();
                        return;
                    }
                    // Announce committed activation and update staff bars;
                    // ordinary players were already removed at the deadline.
                    if (maintenanceNoticeDisplay != null) maintenanceNoticeDisplay.tick();
                    maintenanceAdmissionGate.activationCompleted();
                });
            } catch (RuntimeException schedulingFailure) {
                getLogger().log(Level.SEVERE, "Could not complete maintenance activation on the "
                        + "main thread; stopping Paper to prevent unguarded access", schedulingFailure);
                Bukkit.shutdown();
            }
        });
    }

    int getRetentionDays() {
        return Math.max(1, getConfig().getInt("retention-days", 30));
    }

    int getRollbackJobRetentionDays() {
        return Math.max(1, getConfig().getInt("rollback-job-retention-days", getRetentionDays()));
    }

    MaintenanceCoordinator getMaintenanceCoordinator() {
        return maintenance;
    }

    DatabaseQuickDiagnostics getQuickDiagnostics() {
        return quickDiagnostics;
    }

    DatabaseDeepDiagnostics getDeepDiagnostics() {
        return deepDiagnostics;
    }

    CompactionStoragePreflight getCompactionPreflight() {
        return compactionPreflight;
    }

    OnlineDatabaseCompaction getOnlineCompaction() {
        return onlineCompaction;
    }

    boolean maintenanceBlocksNewJobs() {
        return maintenance != null && maintenance.blocksNewJobs();
    }

    FragGuardCommand getFragGuardCommand() {
        return commandExecutor;
    }

    int getGuiLookupFetchSize() {
        return guiLookupFetchSize;
    }

    static int resolveGuiLookupFetchSize(int configuredValue) {
        return configuredValue > 0 ? configuredValue : DEFAULT_GUI_LOOKUP_FETCH_SIZE;
    }

    private void scheduleCleanup() {
        long ticks = TimeUnit.MINUTES.toSeconds(Math.max(1, getConfig().getInt("cleanup-interval-minutes", 60))) * 20L;
        Runnable cleanup = () -> database.cleanupOldRecordsAsync(getRetentionDays(), getRollbackJobRetentionDays())
                .whenComplete((deleted, throwable) -> {
                    if (throwable != null) {
                        getLogger().log(Level.WARNING, "FragGuard retention cleanup failed.", throwable);
                        return;
                    }
                    if (deleted.blockRecordsDeleted() > 0)
                        getLogger().info("Deleted " + deleted.blockRecordsDeleted() + " old block log records.");
                    if (deleted.rollbackJobsDeleted() > 0)
                        getLogger().info("Deleted " + deleted.rollbackJobsDeleted() + " expired rollback job(s) and their saved snapshots.");
                    if (deleted.batchesProcessed() > 0
                            && (deleted.blockRecordsDeleted() > 0 || deleted.rollbackJobsDeleted() > 0))
                        getLogger().info("Retention cleanup committed " + deleted.batchesProcessed()
                                + " bounded batch(es); writes were allowed between commits.");
                    if (deleted.batchBudgetReached())
                        getLogger().info("Retention cleanup hit its per-run batch budget; "
                                + "remaining expired rows, if any, will be handled by the next run.");
                });
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, cleanup, 20L * 30L, ticks);
    }

    private void scheduleStorageHealthMonitor() {
        long ticks = Math.max(1L, getConfig().getLong("database-health-check-interval-seconds", 5L)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::checkStorageHealth, ticks, ticks);
    }

    private void checkStorageHealth() {
        if (database == null) return;
        DatabaseHealth health = database.health();
        if (!health.degraded()) {
            storageWarningActive = false;
            return;
        }
        long now = System.currentTimeMillis();
        long repeat = TimeUnit.SECONDS.toMillis(Math.max(5L,
                getConfig().getLong("database-operator-warning-interval-seconds", 60L)));
        if (!StorageWarningThrottle.shouldWarn(storageWarningActive, now, lastStorageWarningAt, repeat)) return;
        storageWarningActive = true;
        lastStorageWarningAt = now;
        String availability = health.storageAvailable() ? "DEGRADED" : "UNAVAILABLE";
        String message = "FragGuard logging is " + availability + ". Dropped writes: " + health.droppedWrites()
                + "; queued writes: " + health.queuedWrites() + "/" + health.writeCapacity()
                + (health.lastError().isBlank() ? "." : "; last error: " + health.lastError());
        getLogger().warning(message);
        for (Player player : Bukkit.getOnlinePlayers())
            if (player.isOp() || player.hasPermission("fragguard.admin"))
                player.sendMessage(Component.text("[FragGuard] " + message, NamedTextColor.RED));
    }
}
