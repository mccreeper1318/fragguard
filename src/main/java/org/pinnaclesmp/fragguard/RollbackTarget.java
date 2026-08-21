package org.pinnaclesmp.fragguard;

record RollbackTarget(
        String worldName,
        int x,
        int y,
        int z,
        String blockData,
        String expectedData,
        byte[] targetEntityData,
        byte[] expectedEntityData
) {
    RollbackTarget(String worldName, int x, int y, int z, String blockData, String expectedData) {
        this(worldName, x, y, z, blockData, expectedData, null, null);
    }

    RollbackTarget(String worldName, int x, int y, int z, String blockData) {
        this(worldName, x, y, z, blockData, blockData, null, null);
    }
}
