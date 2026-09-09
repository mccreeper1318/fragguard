package org.pinnaclesmp.fragguard;

record RollbackJobChange(
        int sequence,
        String worldName,
        int x,
        int y,
        int z,
        String beforeData,
        String targetData,
        String expectedData,
        boolean processed,
        boolean applied,
        boolean conflicted,
        boolean undone,
        byte[] beforeEntityData,
        byte[] targetEntityData,
        byte[] expectedEntityData,
        String appliedData,
        byte[] appliedEntityData,
        Long pendingAuditId,
        boolean pendingAuditUndo
) {
    RollbackJobChange(
            int sequence,
            String worldName,
            int x,
            int y,
            int z,
            String beforeData,
            String targetData,
            String expectedData,
            boolean processed,
            boolean applied,
            boolean conflicted,
            boolean undone
    ) {
        this(sequence, worldName, x, y, z, beforeData, targetData, expectedData,
                processed, applied, conflicted, undone, null, null, null,
                null, null, null, false);
    }

    RollbackJobChange(
            int sequence,
            String worldName,
            int x,
            int y,
            int z,
            String beforeData,
            String targetData,
            boolean processed,
            boolean applied,
            boolean undone
    ) {
        this(sequence, worldName, x, y, z, beforeData, targetData, beforeData,
                processed, applied, false, undone, null, null, null,
                null, null, null, false);
    }

    RollbackJobChange withBeforeData(String blockData) {
        return withBeforeState(blockData, beforeEntityData);
    }

    RollbackJobChange withBeforeState(String blockData, byte[] entityData) {
        return new RollbackJobChange(sequence, worldName, x, y, z, blockData, targetData, expectedData,
                processed, applied, conflicted, undone, entityData, targetEntityData, expectedEntityData,
                appliedData, appliedEntityData, pendingAuditId, pendingAuditUndo);
    }
}
