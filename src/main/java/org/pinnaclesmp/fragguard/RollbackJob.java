package org.pinnaclesmp.fragguard;

record RollbackJob(
        long id,
        long createdAt,
        String actorUuid,
        String actorName,
        String worldUuid,
        String worldName,
        int centerX,
        int centerZ,
        int radius,
        long targetTimestamp,
        long snapshotTimestamp,
        boolean force,
        String status,
        int totalBlocks,
        int processedBlocks,
        int appliedBlocks,
        int conflictBlocks,
        int undoProcessedBlocks,
        String lastError
) {
    RollbackJob(
            long id,
            long createdAt,
            String actorUuid,
            String actorName,
            String worldUuid,
            String worldName,
            int centerX,
            int centerZ,
            int radius,
            long targetTimestamp,
            String status,
            int totalBlocks,
            int processedBlocks,
            int appliedBlocks,
            int undoProcessedBlocks,
            String lastError
    ) {
        this(id, createdAt, actorUuid, actorName, worldUuid, worldName, centerX, centerZ, radius,
                targetTimestamp, createdAt, false, status, totalBlocks, processedBlocks, appliedBlocks,
                0, undoProcessedBlocks, lastError);
    }
}
