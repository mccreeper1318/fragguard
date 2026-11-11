package org.pinnaclesmp.fragguard;

import org.bukkit.boss.BarColor;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Configurable player-facing maintenance warning text, timing and bossbar style.
 * This class never starts maintenance or changes the persisted SQLite state.
 */
record MaintenanceNoticeSettings(
        int countdownSeconds,
        List<Integer> warningSeconds,
        boolean countdownBarEnabled,
        boolean staffBarEnabled,
        BarColor countdownColor,
        BarColor activeColor,
        BarColor compactingColor,
        BarColor readyColor,
        String countdownTitle,
        String activeTitle,
        String compactingTitle,
        String verifyingTitle,
        String readyTitle,
        String failedTitle,
        String warningMessage,
        String startedMessage,
        String postponedMessage) {

    private static final List<Integer> DEFAULT_WARNINGS = List.of(600, 300, 180, 60, 30, 10);

    MaintenanceNoticeSettings {
        Objects.requireNonNull(warningSeconds);
        warningSeconds = List.copyOf(warningSeconds);
        Objects.requireNonNull(countdownColor);
        Objects.requireNonNull(activeColor);
        Objects.requireNonNull(compactingColor);
        Objects.requireNonNull(readyColor);
        Objects.requireNonNull(countdownTitle);
        Objects.requireNonNull(activeTitle);
        Objects.requireNonNull(compactingTitle);
        Objects.requireNonNull(verifyingTitle);
        Objects.requireNonNull(readyTitle);
        Objects.requireNonNull(failedTitle);
        Objects.requireNonNull(warningMessage);
        Objects.requireNonNull(startedMessage);
        Objects.requireNonNull(postponedMessage);
        if (countdownSeconds < 1 || countdownSeconds > 86_400) {
            throw new IllegalArgumentException("Maintenance countdown must be 1-86400 seconds");
        }
    }

    static MaintenanceNoticeSettings from(FileConfiguration config) {
        if (config == null) throw new IllegalArgumentException("Missing FragGuard configuration");

        int seconds = clamp(config.getInt("maintenance-countdown-seconds", 600), 1, 86_400);
        List<Integer> chosen = config.contains("maintenance-warning-seconds")
                ? config.getIntegerList("maintenance-warning-seconds") : DEFAULT_WARNINGS;
        LinkedHashSet<Integer> valid = new LinkedHashSet<>();
        for (Integer time : chosen) {
            if (time != null && time > 0 && time <= seconds) valid.add(time);
        }
        List<Integer> warnings = new ArrayList<>(valid);
        warnings.sort(Collections.reverseOrder());

        return new MaintenanceNoticeSettings(
                seconds, warnings,
                config.getBoolean("maintenance-countdown-bossbar-enabled", true),
                config.getBoolean("maintenance-staff-bossbar-enabled", true),
                color(config.getString("maintenance-countdown-bossbar-color"), BarColor.YELLOW),
                color(config.getString("maintenance-active-bossbar-color"), BarColor.RED),
                color(config.getString("maintenance-compacting-bossbar-color"), BarColor.BLUE),
                color(config.getString("maintenance-ready-bossbar-color"), BarColor.GREEN),
                config.getString("maintenance-countdown-bossbar-title",
                        "&eMaintenance Begins in %time%"),
                config.getString("maintenance-active-bossbar-title",
                        "&cSERVER MAINTENANCE ACTIVE"),
                config.getString("maintenance-compacting-bossbar-title",
                        "&bDATABASE COMPACTION IN PROGRESS"),
                config.getString("maintenance-verifying-bossbar-title",
                        "&bVERIFYING COMPACTED DATABASE"),
                config.getString("maintenance-ready-bossbar-title",
                        "&aDATABASE READY - RESTART REQUIRED"),
                config.getString("maintenance-failed-bossbar-title",
                        "&cMAINTENANCE ERROR - STAFF REVIEW REQUIRED"),
                config.getString("maintenance-warning-message",
                        "&eServer maintenance begins in &f%time%&e. Please finish up."),
                config.getString("maintenance-start-message",
                        "&cServer maintenance has started."),
                config.getString("maintenance-postponed-message",
                        "&eMaintenance could not start safely and has been cancelled."));
    }

    static BarColor color(String configured, BarColor fallback) {
        if (configured == null) return fallback;
        try {
            return BarColor.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
