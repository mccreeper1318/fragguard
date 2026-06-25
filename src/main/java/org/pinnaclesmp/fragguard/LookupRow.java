package org.pinnaclesmp.fragguard;

record LookupRow(
        long happenedAt,
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
