package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class FragGuardPlugin extends JavaPlugin {
    private Database database;

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
        getLogger().info("FragGuard enabled. Block changes are retained for " + getRetentionDays() + " days.");
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.shutdown();
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
}
