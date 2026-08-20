package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class FragGuardPlugin extends JavaPlugin {
    private Database database;
    private boolean storageWarningActive;
    private long lastStorageWarningAt;
    private long lastReportedDroppedWrites = -1L;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        database = new Database(this);
        try {
            database.init();
        } catch (SQLException | ClassNotFoundException exception) {
            getLogger().log(Level.SEVERE, "FragGuard could not start because SQLite failed to initialize.", exception);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(new BlockChangeListener(this, database), this);

        FragGuardCommand commandExecutor = new FragGuardCommand(this, database);
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
        if (database == null) {
            return;
        }

        DatabaseHealth before = database.health();
        database.shutdown();
        DatabaseHealth after = database.health();
        StorageShutdownReport report = StorageShutdownSupport.finish(
                new File(getDataFolder(), "fragguard.db"), before, after);

        String summary = "FragGuard storage shutdown: queued=" + report.queuedWritesAtStart()
                + ", drained=" + report.drainedWrites()
                + ", remaining=" + report.remainingWrites()
                + ", remaining operations=" + report.remainingOperations()
                + ", lost during shutdown=" + report.lostDuringShutdown()
                + ", total dropped this session=" + report.totalDroppedWrites()
                + ", WAL checkpoint=" + (report.checkpoint().success() ? "complete" : "FAILED");
        if (report.clean()) {
            getLogger().info(summary);
        } else {
            getLogger().warning(summary);
            if (!report.checkpoint().success() && !report.checkpoint().error().isBlank()) {
                getLogger().warning("FragGuard WAL checkpoint error: " + report.checkpoint().error());
            }
        }
    }

    int getRetentionDays() {
        return Math.max(1, getConfig().getInt("retention-days", 30));
    }

    private void scheduleCleanup() {
        int intervalMinutes = Math.max(1, getConfig().getInt("cleanup-interval-minutes", 60));
        long ticks = TimeUnit.MINUTES.toSeconds(intervalMinutes) * 20L;

        Runnable cleanup = () -> database.cleanupOldRecordsAsync(getRetentionDays()).thenAccept(deleted -> {
            if (deleted > 0) {
                getLogger().info("Deleted " + deleted + " old block log records.");
            }
        });

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, cleanup, 20L * 30L, ticks);
    }

    private void scheduleStorageHealthMonitor() {
        long checkSeconds = Math.max(1L, getConfig().getLong("database-health-check-interval-seconds", 5L));
        long ticks = checkSeconds * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::checkStorageHealth, ticks, ticks);
    }

    private void checkStorageHealth() {
        if (database == null) {
            return;
        }

        DatabaseHealth health = database.health();
        if (!health.degraded()) {
            storageWarningActive = false;
            lastReportedDroppedWrites = health.droppedWrites();
            return;
        }

        long now = System.currentTimeMillis();
        long repeatMillis = TimeUnit.SECONDS.toMillis(Math.max(5L,
                getConfig().getLong("database-operator-warning-interval-seconds", 60L)));
        boolean droppedChanged = health.droppedWrites() != lastReportedDroppedWrites;
        if (storageWarningActive && !droppedChanged && now - lastStorageWarningAt < repeatMillis) {
            return;
        }

        storageWarningActive = true;
        lastStorageWarningAt = now;
        lastReportedDroppedWrites = health.droppedWrites();

        String availability = health.storageAvailable() ? "DEGRADED" : "UNAVAILABLE";
        String message = "FragGuard logging is " + availability
                + ". Dropped writes: " + health.droppedWrites()
                + "; queued writes: " + health.queuedWrites() + "/" + health.writeCapacity()
                + (health.lastError().isBlank() ? "." : "; last error: " + health.lastError());
        getLogger().warning(message);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOp() || player.hasPermission("fragguard.admin")) {
                player.sendMessage(ChatColor.RED + "[FragGuard] " + message);
            }
        }
    }
}
