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
        String status,
        int totalBlocks,
        int processedBlocks,
        int appliedBlocks,
        int undoProcessedBlocks,
        String lastError
) {
}
