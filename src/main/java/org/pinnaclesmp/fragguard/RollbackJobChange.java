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
        boolean undone
) {
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
                processed, applied, false, undone);
    }

    RollbackJobChange withBeforeData(String blockData) {
        return new RollbackJobChange(sequence, worldName, x, y, z, blockData, targetData, expectedData,
                processed, applied, conflicted, undone);
    }
}
