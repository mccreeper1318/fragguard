package org.pinnaclesmp.fragguard;

import java.util.List;

/**
 * Player rollback planning is intentionally separate from live world mutations.
 * Historical conflicts cannot be overridden by the executor's force option.
 */
record PlayerRollbackPlan(
        HistoricalPlayerResolver.HistoricalPlayerIdentity player,
        long targetTimestamp,
        long snapshotTimestamp,
        List<RollbackTarget> targets,
        int candidateCoordinates,
        int historicalConflicts,
        int noOps,
        int unavailable
) {
    PlayerRollbackPlan {
        targets = List.copyOf(targets);
    }

    int reversibleCoordinates() {
        return targets.size();
    }
}
