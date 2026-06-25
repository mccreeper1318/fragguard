package org.pinnaclesmp.fragguard;

record RollbackTarget(
        String worldName,
        int x,
        int y,
        int z,
        String blockData
) {
}
