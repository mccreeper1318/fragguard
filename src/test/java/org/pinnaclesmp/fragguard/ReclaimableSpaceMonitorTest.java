package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReclaimableSpaceMonitorTest {
    private static final DatabaseHealth HEALTH =
            new DatabaseHealth(0, 100, 0, 10, 0, 0, true, "");

    private static DatabaseQuickDiagnostics.Report sample(long pages, long freePages,
                                                          Long mainBytes, Long walBytes) {
        return new DatabaseQuickDiagnostics.Report(
                DatabaseQuickDiagnostics.Mode.OVERVIEW, Instant.ofEpochSecond(100),
                Path.of("test-fragguard.db"), mainBytes, walBytes, 0L, null,
                pages, 4096L, freePages, freePages * 4096L, Database.SCHEMA_VERSION,
                null, null, null, HEALTH, 30, 30);
    }

    @Test
    void recommendsOnlyWhenBothFreelistAmountAndRatioReachThreshold() {
        var monitor = new ReclaimableSpaceMonitor(
                new ReclaimableSpaceMonitor.Settings(true, 60, 2, 25.0, 60));
        assertFalse(monitor.accept(sample(1024, 511, 4L << 20, 1L << 20), 1_000L, true),
                "under 2 MiB must not warn even if the percentage is high enough");
        assertFalse(monitor.accept(sample(4096, 512, 16L << 20, 1L << 20), 2_000L, true),
                "2 MiB is only 12.5% of the database");
        assertTrue(monitor.accept(sample(2048, 512, 8L << 20, 1L << 20), 3_000L, true),
                "the minimum amount and percentage both match");
        assertTrue(monitor.latest().recommended());
        assertEquals(25.0, monitor.latest().freeListPercent());
        assertTrue(monitor.latest().warning().contains("NOT guaranteed VACUUM INTO"));
        assertFalse(monitor.latest().warning().contains("will save"));
    }

    @Test
    void warningCooldownIsStableAcrossThresholdFlappingAndBackwardClock() {
        var monitor = new ReclaimableSpaceMonitor(
                new ReclaimableSpaceMonitor.Settings(true, 10, 1, 10, 60));
        var high = sample(2048, 512, 10L << 20, 5L << 20);
        var low = sample(2048, 0, 10L << 20, 5L << 20);
        assertTrue(monitor.accept(high, 1_000_000L, true));
        assertFalse(monitor.accept(high, 1_001_000L, true));
        assertFalse(monitor.accept(low, 1_002_000L, true));
        assertFalse(monitor.accept(high, 1_003_000L, true),
                "a dip below the threshold must not bypass the cooldown");
        assertFalse(monitor.accept(high, 999_999L, true),
                "a backwards clock jump must not bypass the cooldown");
        assertTrue(monitor.accept(high, 1_000_000L + TimeUnit.MINUTES.toMillis(60), true));
    }

    @Test
    void disabledAndManualSamplesNeverWarnButRefreshCachedRecommendation() {
        var monitor = new ReclaimableSpaceMonitor(
                new ReclaimableSpaceMonitor.Settings(false, 60, 1, 10, 60));
        var high = sample(2048, 512, 8L << 20, 2L << 20);
        assertFalse(monitor.accept(high, 0L, true));
        assertFalse(monitor.accept(high, 1000L, false));
        assertNotNull(monitor.latest());
        assertTrue(monitor.latest().recommended());
        assertTrue(monitor.latest().guiLore().stream().anyMatch(s -> s.contains("NOT guaranteed")));
        var enabled = new ReclaimableSpaceMonitor(
                new ReclaimableSpaceMonitor.Settings(true, 60, 1, 10, 60));
        assertFalse(enabled.accept(high, 0L, false), "manual checks must not emit alerts");
        assertTrue(enabled.accept(high, 1000L, true), "manual checks must not consume cooldown");
    }

    @Test
    void walSizeDoesNotPretendToBeFreeListSavingsAndGrowthTracksTotalFootprint() {
        var monitor = new ReclaimableSpaceMonitor(
                new ReclaimableSpaceMonitor.Settings(true, 60, 1, 10, 60));
        assertFalse(monitor.accept(sample(2048, 0, 200L, 1L << 33), 1000L, true),
                "a huge active WAL is not an estimate of reclaimable database pages");
        assertNull(monitor.latest().growthBytes());
        assertEquals(0L, monitor.latest().freeListBytes());
        assertFalse(monitor.latest().recommended());
        assertFalse(monitor.accept(sample(2048, 0, 300L, (1L << 33) + 100L), 2000L, true));
        assertEquals(200L, monitor.latest().growthBytes());
        assertFalse(monitor.accept(sample(2048, 0, 300L, null), 3000L, true));
        assertNull(monitor.latest().growthBytes(), "unknown WAL size gives unknown trend");
    }

    @Test
    void unknownAndOverflowingSizesNeverInventTrend() {
        var monitor = new ReclaimableSpaceMonitor(
                new ReclaimableSpaceMonitor.Settings(true, 60, Long.MAX_VALUE, 10, 60));
        assertEquals(Long.MAX_VALUE, monitor.settings().minimumReusableBytes());
        monitor.accept(sample(2048, 0, Long.MAX_VALUE, 100L), 1000L, true);
        assertNull(monitor.latest().growthBytes());
        monitor.accept(sample(2048, 0, 100L, 100L), 2000L, true);
        assertNull(monitor.latest().growthBytes());
        assertThrows(IllegalArgumentException.class, () ->
                new ReclaimableSpaceMonitor.Settings(true, 0, 1, 10, 60));
    }
}
