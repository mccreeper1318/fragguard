package org.pinnaclesmp.fragguard;

record RollbackJobChange(
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
    RollbackJobChange withBeforeData(String blockData) {
        return new RollbackJobChange(sequence, worldName, x, y, z, blockData, targetData, processed, applied, undone);
    }
}
