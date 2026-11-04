package org.pinnaclesmp.fragguard;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Stable world UUID + chunk coordinates reserved by active rollback/undo jobs. */
record RollbackJobChunk(String worldUuid, int x, int z) {
    RollbackJobChunk {
        Objects.requireNonNull(worldUuid, "world UUID is required for overlap protection");
    }

    static Set<RollbackJobChunk> fromTargets(List<RollbackTarget> targets) {
        Set<RollbackJobChunk> keys = new HashSet<>();
        for (RollbackTarget target : targets) {
            keys.add(new RollbackJobChunk(Objects.requireNonNull(target.worldUuid(),
                    "target world UUID is required"), target.x() >> 4, target.z() >> 4));
        }
        return Set.copyOf(keys);
    }

    /**
     * A radius job reserves every chunk intersecting its bounding square.
     * This intentionally over-reserves to prevent concurrent edits.
     */
    static Set<RollbackJobChunk> radius(String uuid, int x, int z, int radius) {
        int lowX = Math.floorDiv((long) x - radius > Integer.MIN_VALUE
                ? x - radius : Integer.MIN_VALUE, 16);
        int highX = Math.floorDiv((long) x + radius < Integer.MAX_VALUE
                ? x + radius : Integer.MAX_VALUE, 16);
        int lowZ = Math.floorDiv((long) z - radius > Integer.MIN_VALUE
                ? z - radius : Integer.MIN_VALUE, 16);
        int highZ = Math.floorDiv((long) z + radius < Integer.MAX_VALUE
                ? z + radius : Integer.MAX_VALUE, 16);
        long total = ((long) highX - lowX + 1L) * ((long) highZ - lowZ + 1L);
        if (total > 200_000) {
            throw new IllegalArgumentException("Radius is too large to reserve rollback chunks safely.");
        }
        Set<RollbackJobChunk> keys = new HashSet<>();
        for (int cx = lowX; cx <= highX; cx++) {
            for (int cz = lowZ; cz <= highZ; cz++) {
                keys.add(new RollbackJobChunk(uuid, cx, cz));
            }
        }
        return Set.copyOf(keys);
    }
}
