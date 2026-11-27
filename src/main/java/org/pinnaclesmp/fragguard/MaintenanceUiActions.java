package org.pinnaclesmp.fragguard;

import java.util.Locale;

/** Single source of truth for command and inventory-menu action availability. */
final class MaintenanceUiActions {
    private MaintenanceUiActions() { }

    static boolean canSchedule(MaintenanceSnapshot state) {
        return state.phase() == MaintenancePhase.IDLE
                || state.phase() == MaintenancePhase.CANCELLED;
    }

    static boolean canCompact(MaintenanceSnapshot state) {
        return state.phase() == MaintenancePhase.ACTIVE;
    }

    static boolean canVerify(MaintenanceSnapshot state) {
        return state.phase() == MaintenancePhase.VERIFYING
                && state.manifestFile().isBlank();
    }

    static boolean canApprove(MaintenanceSnapshot state) {
        return state.phase() == MaintenancePhase.VERIFYING
                && !state.manifestFile().isBlank();
    }

    static boolean canCancel(MaintenanceSnapshot state) {
        return switch (state.phase()) {
            case COUNTDOWN, ACTIVE, COMPACTING, VERIFYING, FAILED -> true;
            default -> false; // Never discard READY/ACTIVATING recovery evidence in the UI.
        };
    }

    static MaintenancePolicy requestedPolicy(String value) {
        String token = value.toLowerCase(Locale.ROOT);
        return switch (token) {
            case "strict" -> MaintenancePolicy.STRICT;
            case "snapshot" -> MaintenancePolicy.MAINTENANCE_SNAPSHOT;
            default -> throw new IllegalArgumentException("Use strict or snapshot");
        };
    }

    static long countdownSeconds(int configured) {
        return Math.max(1L, Math.min(86_400L, configured));
    }

    /**
     * SQLite VACUUM progress has no reliable total. Do not create a made-up
     * completion percent: only the countdown has a measurable denominator.
     */
    static int countdownPercent(MaintenanceSnapshot state, long now) {
        if (state.phase() != MaintenancePhase.COUNTDOWN) return -1;
        long total = state.countdownEndsAtMillis() - state.requestedAtMillis();
        if (total <= 0L) return 0;
        long remaining = Math.max(0L, state.countdownEndsAtMillis() - now);
        return (int) Math.max(0L, Math.min(100L, remaining * 100L / total));
    }
}
