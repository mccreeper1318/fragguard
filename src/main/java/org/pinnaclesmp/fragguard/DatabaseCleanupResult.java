package org.pinnaclesmp.fragguard;

/** Total of independently committed cleanup batches (not a single transaction). */
record DatabaseCleanupResult(
        long blockRecordsDeleted,
        long rollbackJobsDeleted,
        int batchesProcessed,
        boolean batchBudgetReached,
        boolean pausedForMaintenance
) {
    DatabaseCleanupResult(long blockRecordsDeleted, long rollbackJobsDeleted,
                          int batchesProcessed, boolean batchBudgetReached) {
        this(blockRecordsDeleted, rollbackJobsDeleted, batchesProcessed, batchBudgetReached, false);
    }

    static DatabaseCleanupResult paused() {
        return new DatabaseCleanupResult(0L, 0L, 0, false, true);
    }
}
