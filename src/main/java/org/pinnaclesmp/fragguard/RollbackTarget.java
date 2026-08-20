package org.pinnaclesmp.fragguard;

record RollbackTarget(
        String worldName,
        int x,
        int y,
        int z,
        String blockData,
        String expectedData
) {
    RollbackTarget(String worldName, int x, int y, int z, String blockData) {
        this(worldName, x, y, z, blockData, blockData);
    }
}
