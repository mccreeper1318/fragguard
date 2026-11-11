package org.pinnaclesmp.fragguard;

import java.util.List;

/** Pure countdown calculations; time comes from persisted maintenance metadata. */
final class MaintenanceCountdownClock {
    private MaintenanceCountdownClock() {}

    record Frame(long remainingMillis, long remainingSeconds, double progress, String displayTime) {
        Frame {
            if (remainingMillis < 0 || remainingSeconds < 0 || progress < 0 || progress > 1) {
                throw new IllegalArgumentException("Invalid countdown frame");
            }
        }
    }

    static Frame frame(MaintenanceSnapshot state, long nowMillis) {
        if (state.phase() != MaintenancePhase.COUNTDOWN) {
            throw new IllegalArgumentException("Countdown frame requires a scheduled operation");
        }
        long duration = state.countdownEndsAtMillis() - state.requestedAtMillis();
        long remaining = Math.max(0L, state.countdownEndsAtMillis() - nowMillis);
        double progress = Math.max(0.0, Math.min(1.0, remaining / (double) duration));
        long seconds = (remaining / 1000L) + (remaining % 1000L == 0 ? 0 : 1);
        return new Frame(remaining, seconds, progress, clock(seconds));
    }

    static String clock(long totalSeconds) {
        long seconds = Math.max(0, totalSeconds);
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    /**
     * Select the most urgent threshold crossed since the prior tick. A TPS
     * stall must not dump five historical warnings into chat all at once.
     * Initial previousRemainingMillis should be the operation duration + 1ms.
     */
    static int crossedWarning(List<Integer> descendingWarnings, long durationMillis,
                              long previousRemainingMillis, long remainingMillis) {
        if (remainingMillis <= 0) return -1;
        int due = -1;
        for (Integer seconds : descendingWarnings) {
            if (seconds == null || seconds <= 0) continue;
            long threshold = seconds * 1000L;
            if (threshold <= durationMillis && previousRemainingMillis > threshold
                    && remainingMillis <= threshold) {
                due = seconds; // Later threshold is more urgent.
            }
        }
        return due;
    }

    static String expand(String configured, String time) {
        return configured.replace("%time%", time);
    }
}
