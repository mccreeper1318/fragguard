package org.pinnaclesmp.fragguard;

import java.util.Objects;

/** Immutable, non-blocking menu snapshot; no SQLite reads from the Paper thread. */
record MaintenanceMenuView(
        MaintenancePhase phase, MaintenancePolicy policy, String detail,
        String operationId, String candidate, boolean verified,
        long secondsLeft, int countdownPercent,
        boolean copying, String copyStage, long copyBytes,
        boolean scanning, String scanStage, long scannedRows,
        long sourceBytes, long candidateBytes) {

    MaintenanceMenuView {
        Objects.requireNonNull(phase);
        Objects.requireNonNull(policy);
        Objects.requireNonNull(detail);
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(copyStage);
        Objects.requireNonNull(scanStage);
    }

    static MaintenanceMenuView from(MaintenanceSnapshot state,
                                    OnlineDatabaseCompaction.Progress copy,
                                    CompactionCandidateVerifier.Progress verify,
                                    long sourceBytes, long candidateBytes, long now) {
        long remaining = state.phase() == MaintenancePhase.COUNTDOWN
                ? Math.max(0L, (state.countdownEndsAtMillis() - now + 999L) / 1000L) : -1L;
        return new MaintenanceMenuView(state.phase(), state.policy(),
                state.detail(), state.operationId(), state.candidateFile(),
                !state.manifestFile().isBlank(), remaining,
                MaintenanceUiActions.countdownPercent(state, now),
                copy.running(), copy.stage(), Math.max(0L, copy.candidateBytes()),
                verify.running(), verify.stage(), Math.max(0L, verify.recordsScanned()),
                sourceBytes, candidateBytes);
    }

    boolean canSchedule() { return phase == MaintenancePhase.IDLE || phase == MaintenancePhase.CANCELLED; }
    boolean canCompact() { return phase == MaintenancePhase.ACTIVE; }
    boolean canVerify() { return phase == MaintenancePhase.VERIFYING && !verified; }
    boolean canApprove() { return phase == MaintenancePhase.VERIFYING && verified; }
    boolean canCancel() {
        return phase == MaintenancePhase.COUNTDOWN || phase == MaintenancePhase.ACTIVE
                || phase == MaintenancePhase.COMPACTING || phase == MaintenancePhase.VERIFYING;
    }
    String sizeSummary() {
        if (sourceBytes < 0 || candidateBytes < 0) return "Size comparison unavailable";
        return "Original " + MigrationStoragePolicy.formatBytes(sourceBytes)
                + ", candidate " + MigrationStoragePolicy.formatBytes(candidateBytes)
                + "; " + MigrationStoragePolicy.formatBytes(Math.abs(sourceBytes - candidateBytes))
                + (candidateBytes <= sourceBytes ? " smaller" : " larger")
                + " (WAL/SHM excluded)";
    }
}
