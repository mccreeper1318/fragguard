package org.pinnaclesmp.fragguard;

record DatabaseCleanupResult(
        int blockRecordsDeleted,
        int rollbackJobsDeleted
) {
}
