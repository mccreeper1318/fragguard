package org.pinnaclesmp.fragguard;

/** Total of independently committed cleanup batches (not a single transaction). */
record DatabaseCleanupResult(
        long blockRecordsDeleted,
        long rollbackJobsDeleted,
        int batchesProcessed,
        boolean batchBudgetReached
) { }
