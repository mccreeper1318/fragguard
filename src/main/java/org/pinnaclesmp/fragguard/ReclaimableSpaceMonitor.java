package org.pinnaclesmp.fragguard;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Read-only, advisory-only storage sampling. SQLite reads are performed by
 * DatabaseQuickDiagnostics on its bounded worker, never by this class.
 * A WAL file is NOT equivalent to recoverable main-database space.
 */
final class ReclaimableSpaceMonitor {
    record Settings(boolean enabled, long intervalMinutes, long minimumReusableMiB,
                    double minimumFreelistPercent, long warningCooldownMinutes) {
        Settings {
            if (intervalMinutes < 1 || minimumReusableMiB < 1
                    || !Double.isFinite(minimumFreelistPercent)
                    || minimumFreelistPercent < 0 || minimumFreelistPercent > 100
                    || warningCooldownMinutes < 1) {
                throw new IllegalArgumentException("Invalid reclaimable-space monitoring settings");
            }
        }

        long minimumReusableBytes() {
            return minimumReusableMiB > Long.MAX_VALUE / (1024L * 1024L)
                    ? Long.MAX_VALUE : minimumReusableMiB * 1024L * 1024L;
        }
    }

    record Snapshot(Instant measuredAt, long freeListBytes, double freeListPercent,
                    Long walBytes, Long growthBytes, boolean recommended) {
        Snapshot {
            Objects.requireNonNull(measuredAt);
        }

        String warning() {
            return "SQLite free-list estimate: " + size(freeListBytes)
                    + " (" + percent(freeListPercent) + " of database pages); WAL: "
                    + size(walBytes) + "; DB+WAL growth since prior sample: "
                    + growth(growthBytes) + ". Consider reviewing /fg diagnostics "
                    + "and /fg diagnostics preflight for MANUAL maintenance. "
                    + "This is NOT guaranteed VACUUM INTO savings. No compaction was started.";
        }

        List<String> guiLore() {
            List<String> lines = new ArrayList<>();
            lines.add("&7Free-list estimate: &f" + size(freeListBytes));
            lines.add("&7Pages reusable: &f" + percent(freeListPercent));
            lines.add("&7WAL size (separate): &f" + size(walBytes));
            lines.add("&7DB+WAL trend: &f" + growth(growthBytes));
            lines.add(recommended
                    ? "&eReview manual compaction when convenient."
                    : "&7No compaction recommendation at this threshold.");
            lines.add("&7Estimate is NOT guaranteed VACUUM savings.");
            lines.add("&8Measured: " + measuredAt);
            lines.add("&eClick for a fresh read-only overview.");
            return List.copyOf(lines);
        }

        private static String growth(Long bytes) {
            if (bytes == null) return "not enough samples";
            return (bytes > 0 ? "+" : "") + size(bytes);
        }
    }

    private final Settings settings;
    private volatile Snapshot latest;
    private Long previousTotalBytes;
    private Long lastWarningAtMillis;

    ReclaimableSpaceMonitor(Settings settings) {
        this.settings = Objects.requireNonNull(settings);
    }

    Settings settings() {
        return settings;
    }

    Snapshot latest() {
        return latest;
    }

    /**
     * Returns true exactly when a configured warning should be dispatched.
     * A manual diagnostic may update the GUI cache but cannot trigger alerts.
     */
    synchronized boolean accept(DatabaseQuickDiagnostics.Report report, long nowMillis,
                                boolean allowWarning) {
        Objects.requireNonNull(report);
        double fraction = report.pageCount() == 0 ? 0.0
                : report.freelistCount() * 100.0 / report.pageCount();
        boolean worthwhile = report.recyclableBytes() >= settings.minimumReusableBytes()
                && fraction >= settings.minimumFreelistPercent();

        // Track file growth independently. SQLite WAL bytes can be recycled without
        // shrinking, so they must not be added to the free-list savings estimate.
        Long total = addNonNegative(report.mainBytes(), report.walBytes());
        Long delta = subtract(total, previousTotalBytes);
        if (total != null) previousTotalBytes = total;
        latest = new Snapshot(report.measuredAt(), report.recyclableBytes(), fraction,
                report.walBytes(), delta, worthwhile);

        if (!allowWarning || !settings.enabled() || !worthwhile) return false;
        long cooldown = TimeUnit.MINUTES.toMillis(settings.warningCooldownMinutes());
        if (lastWarningAtMillis != null && nowMillis >= lastWarningAtMillis
                && nowMillis - lastWarningAtMillis < cooldown) return false;
        // A backwards wall-clock jump cannot defeat the configured cooldown.
        if (lastWarningAtMillis != null && nowMillis < lastWarningAtMillis) return false;
        lastWarningAtMillis = nowMillis;
        return true;
    }

    private static Long addNonNegative(Long a, Long b) {
        if (a == null || b == null || a < 0 || b < 0) return null;
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException ignored) {
            return null;
        }
    }

    private static Long subtract(Long current, Long previous) {
        if (current == null || previous == null) return null;
        try {
            return Math.subtractExact(current, previous);
        } catch (ArithmeticException ignored) {
            return null;
        }
    }

    private static String size(Long bytes) {
        if (bytes == null) return "unknown";
        return size(bytes.longValue());
    }

    private static String size(long bytes) {
        // Long.MIN_VALUE cannot be negated; no file size can be that negative.
        return (bytes < 0 ? "-" : "")
                + MigrationStoragePolicy.formatBytes(bytes == Long.MIN_VALUE
                        ? Long.MAX_VALUE : Math.abs(bytes));
    }

    private static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.1f%%", fraction);
    }
}
