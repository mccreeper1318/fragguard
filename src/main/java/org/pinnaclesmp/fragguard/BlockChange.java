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
        byte[] afterEntityData,
        String causalPlayerUuid,
        String causalPlayerName,
        String causalChainId,
        String causalCauseType
) {
    BlockChange(
            long happenedAt, long serverTick, String actorUuid, String actorName,
            String worldName, int x, int y, int z, ChangeAction action,
            String beforeData, String afterData, byte[] beforeEntityData, byte[] afterEntityData
    ) {
        this(happenedAt, serverTick, actorUuid, actorName, worldName, x, y, z, action,
                beforeData, afterData, beforeEntityData, afterEntityData, null, null, null, null);
    }

    BlockChange withCausalAttribution(CausalAttribution attribution) {
        return attribution == null ? this : new BlockChange(
                happenedAt, serverTick, actorUuid, actorName, worldName, x, y, z, action,
                beforeData, afterData, beforeEntityData, afterEntityData,
                attribution.playerUuid(), attribution.playerName(), attribution.chainId(),
                attribution.causeType());
    }
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
