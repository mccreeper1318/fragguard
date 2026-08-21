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
        String afterData,
        byte[] beforeEntityData,
        byte[] afterEntityData
) {
    static final long UNSPECIFIED_SERVER_TICK = Long.MIN_VALUE;

    BlockChange(
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
        this(happenedAt, serverTick, actorUuid, actorName, worldName, x, y, z, action,
                beforeData, afterData, null, null);
    }

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
                afterData,
                null,
                null
        );
    }

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
            String afterData,
            byte[] beforeEntityData,
            byte[] afterEntityData
    ) {
        this(happenedAt, UNSPECIFIED_SERVER_TICK, actorUuid, actorName, worldName, x, y, z,
                action, beforeData, afterData, beforeEntityData, afterEntityData);
    }
}
