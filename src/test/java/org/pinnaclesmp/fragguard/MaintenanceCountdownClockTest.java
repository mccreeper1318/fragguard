package org.pinnaclesmp.fragguard;

import org.bukkit.boss.BarColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MaintenanceCountdownClockTest {
    private static final long START = 1_800_000_000_000L;
    private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void defaultSettingsMatchCountdownChatAndStaffColors() {
        var settings = MaintenanceNoticeSettings.from(new YamlConfiguration());
        assertEquals(600, settings.countdownSeconds());
        assertEquals(List.of(600, 300, 180, 60, 30, 10), settings.warningSeconds());
        assertTrue(settings.countdownBarEnabled());
        assertTrue(settings.staffBarEnabled());
        assertEquals(BarColor.YELLOW, settings.countdownColor());
        assertEquals(BarColor.RED, settings.activeColor());
        assertEquals(BarColor.BLUE, settings.compactingColor());
        assertEquals(BarColor.GREEN, settings.readyColor());
    }

    @Test
    void countdownBarShrinksUsingPersistedAbsoluteDeadline() {
        var state = MaintenanceSnapshot.scheduled(ADMIN, START, START + 600_000L,
                MaintenancePolicy.STRICT);
        var full = MaintenanceCountdownClock.frame(state, START);
        assertEquals(1.0, full.progress(), 0.00001);
        assertEquals("10:00", full.displayTime());
        assertEquals(600, full.remainingSeconds());

        var halfway = MaintenanceCountdownClock.frame(state, START + 300_000L);
        assertEquals(0.5, halfway.progress(), 0.00001);
        assertEquals("05:00", halfway.displayTime());

        var small = MaintenanceCountdownClock.frame(state, START + 599_500L);
        assertEquals(0.000833333, small.progress(), 0.000001);
        assertEquals("00:01", small.displayTime(), "never show zero before actual expiration");

        var zero = MaintenanceCountdownClock.frame(state, START + 600_000L);
        assertEquals(0.0, zero.progress());
        assertEquals("00:00", zero.displayTime());
        assertEquals(0, zero.remainingSeconds());
        assertEquals(0.0, MaintenanceCountdownClock.frame(state, START + 700_000L).progress());
    }

    @Test
    void lateJoinersUseSameDeadlineRatherThanReceivingNewTenMinutes() {
        var state = MaintenanceSnapshot.scheduled(ADMIN, START, START + 600_000L,
                MaintenancePolicy.STRICT);
        var first = MaintenanceCountdownClock.frame(state, START + 60_000L);
        var later = MaintenanceCountdownClock.frame(state, START + 360_000L);
        assertEquals("09:00", first.displayTime());
        assertEquals("04:00", later.displayTime());
        assertEquals(0.4, later.progress(), 1e-9);
    }

    @Test
    void warningsOnlyFireOnceWhenCrossingThresholdsAndNoLateBurst() {
        List<Integer> intervals = List.of(600, 300, 180, 60, 30, 10);
        long duration = 600_000L;
        assertEquals(600, MaintenanceCountdownClock.crossedWarning(
                intervals, duration, 600_001L, 599_500L));
        assertEquals(-1, MaintenanceCountdownClock.crossedWarning(
                intervals, duration, 599_500L, 598_500L));
        assertEquals(300, MaintenanceCountdownClock.crossedWarning(
                intervals, duration, 301_000L, 299_000L));
        assertEquals(10, MaintenanceCountdownClock.crossedWarning(
                intervals, duration, 301_000L, 5_000L),
                "after a major server stall, send only the most urgent reminder");
        assertEquals(-1, MaintenanceCountdownClock.crossedWarning(
                intervals, duration, 11_000L, 0L),
                "don't broadcast a stale ten-second warning when maintenance begins");
    }

    @Test
    void countdownSettingsValidateAndDeduplicateWarnings() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("maintenance-countdown-seconds", 180);
        config.set("maintenance-warning-seconds", List.of(600, 180, 180, 50, 10, -1, 0));
        config.set("maintenance-countdown-bossbar-color", "light_blue");
        config.set("maintenance-active-bossbar-color", "reD");
        var settings = MaintenanceNoticeSettings.from(config);
        assertEquals(180, settings.countdownSeconds());
        assertEquals(List.of(180, 50, 10), settings.warningSeconds());
        assertEquals(BarColor.YELLOW, settings.countdownColor(),
                "invalid configured Boss Bar color uses safe default");
        assertEquals(BarColor.RED, settings.activeColor());
        assertEquals("02:05", MaintenanceCountdownClock.clock(125));
        assertEquals("Maintenance in 01:00",
                MaintenanceCountdownClock.expand("Maintenance in %time%", "01:00"));
    }
}
