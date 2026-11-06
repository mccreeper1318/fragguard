package org.pinnaclesmp.fragguard;

record RollbackTarget(
        String worldName,
        int x,
        int y,
        int z,
        String blockData,
        String expectedData,
        byte[] targetEntityData,
        byte[] expectedEntityData,
        String worldUuid,
        String physicalActorUuid,
        String physicalActorName,
        String causalPlayerUuid,
        String causalChainId,
        String causalCauseType
) {
    RollbackTarget(String worldName, int x, int y, int z, String blockData,
                   String expectedData, byte[] targetEntityData, byte[] expectedEntityData) {
        this(worldName, x, y, z, blockData, expectedData, targetEntityData,
                expectedEntityData, null, null, null, null, null, null);
    }
    RollbackTarget(String worldName, int x, int y, int z, String blockData, String expectedData) {
        this(worldName, x, y, z, blockData, expectedData, null, null);
    }

    RollbackTarget(String worldName, int x, int y, int z, String blockData) {
        this(worldName, x, y, z, blockData, blockData, null, null);
    }
}
