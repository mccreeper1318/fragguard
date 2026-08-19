package org.pinnaclesmp.fragguard;

record BlockChange(
        long happenedAt,
        long serverTick,
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
    static final long UNSPECIFIED_SERVER_TICK = Long.MIN_VALUE;

    BlockChange(
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
        this(
                happenedAt,
                UNSPECIFIED_SERVER_TICK,
                actorUuid,
                actorName,
                worldName,
                x,
                y,
                z,
                action,
                beforeData,
                afterData
        );
    }
}
