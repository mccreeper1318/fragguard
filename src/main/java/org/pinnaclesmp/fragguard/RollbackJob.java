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
        String lastError,
        String scopeType,
        String targetPlayerUuid,
        String targetPlayerName,
        String worldScopeUuid,
        String worldScopeName,
        int historicalConflicts,
        int affectedWorlds,
        int affectedChunks
) {
    RollbackJob(long id, long createdAt, String actorUuid, String actorName,
                String worldUuid, String worldName, int centerX, int centerZ, int radius,
                long targetTimestamp, long snapshotTimestamp, boolean force, String status,
                int totalBlocks, int processedBlocks, int appliedBlocks, int conflictBlocks,
                int undoProcessedBlocks, String lastError) {
        this(id, createdAt, actorUuid, actorName, worldUuid, worldName,
                centerX, centerZ, radius, targetTimestamp, snapshotTimestamp, force,
                status, totalBlocks, processedBlocks, appliedBlocks, conflictBlocks,
                undoProcessedBlocks, lastError, "RADIUS", null, null, null, null, 0, 1, 0);
    }

    boolean isPlayerScope() {
        return "PLAYER".equals(scopeType);
    }
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
