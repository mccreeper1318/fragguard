package org.pinnaclesmp.fragguard;

import java.util.Arrays;

record LookupRow(
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
        this(happenedAt, actorName, worldName, x, y, z, action, beforeData, afterData, null, null);
    }

    LookupRow {
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

    private static byte[] copy(byte[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }
}
