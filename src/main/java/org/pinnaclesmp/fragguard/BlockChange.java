package org.pinnaclesmp.fragguard;

record BlockChange(
        long happenedAt,
        String actorUuid,
        String actorName,
        String worldName,
        int x,
        int y,
        int z,
        ChangeAction action,
        String beforeData,
        String afterData
) {
}
