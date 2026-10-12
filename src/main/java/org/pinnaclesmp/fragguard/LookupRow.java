package org.pinnaclesmp.fragguard;

import java.util.Arrays;

record LookupRow(
        long id,
        long happenedAt,
        String actorIdentity,
        String actorName,
        String worldName,
        int x,
        int y,
        int z,
        ChangeAction action,
        String beforeData,
        String afterData,
        boolean blockEntityDataPresent,
        byte[] beforeEntityData,
        byte[] afterEntityData
) {
    LookupRow(
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
        this(-1L, happenedAt, null, actorName, worldName, x, y, z, action,
                beforeData, afterData, false, null, null);
    }

    LookupRow(
            long happenedAt,
            String actorIdentity,
            String actorName,
            String worldName,
            int x,
            int y,
            int z,
            ChangeAction action,
            String beforeData,
            String afterData
    ) {
        this(-1L, happenedAt, actorIdentity, actorName, worldName, x, y, z, action,
                beforeData, afterData, false, null, null);
    }

    LookupRow(
            long happenedAt,
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
        this(-1L, happenedAt, null, actorName, worldName, x, y, z, action,
                beforeData, afterData, beforeEntityData != null || afterEntityData != null,
                beforeEntityData, afterEntityData);
    }

    LookupRow(
            long id,
            long happenedAt,
            String actorName,
            String worldName,
            int x,
            int y,
            int z,
            ChangeAction action,
            String beforeData,
            String afterData,
            boolean blockEntityDataPresent
    ) {
        this(id, happenedAt, null, actorName, worldName, x, y, z, action,
                beforeData, afterData, blockEntityDataPresent, null, null);
    }

    LookupRow(
            long id,
            long happenedAt,
            String actorIdentity,
            String actorName,
            String worldName,
            int x,
            int y,
            int z,
            ChangeAction action,
            String beforeData,
            String afterData,
            boolean blockEntityDataPresent
    ) {
        this(id, happenedAt, actorIdentity, actorName, worldName, x, y, z, action,
                beforeData, afterData, blockEntityDataPresent, null, null);
    }

    LookupRow {
        actorIdentity = stableActorIdentity(actorIdentity, actorName);
        beforeEntityData = copy(beforeEntityData);
        afterEntityData = copy(afterEntityData);
    }

    @Override
    public byte[] beforeEntityData() {
        return copy(beforeEntityData);
    }

    @Override
    public byte[] afterEntityData() {
        return copy(afterEntityData);
    }

    boolean blockEntityChanged() {
        return !Arrays.equals(beforeEntityData, afterEntityData);
    }

    boolean blockEntityPayloadLoaded() {
        return beforeEntityData != null || afterEntityData != null;
    }

    private static String stableActorIdentity(String actorIdentity, String actorName) {
        if (actorIdentity != null && !actorIdentity.isBlank()) {
            return actorIdentity.trim();
        }
        String displayName = actorName == null || actorName.isBlank() ? "<unknown>" : actorName.trim();
        return "system:" + displayName;
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }
}
