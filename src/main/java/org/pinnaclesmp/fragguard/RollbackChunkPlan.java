package org.pinnaclesmp.fragguard;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class RollbackChunkPlan {
    private RollbackChunkPlan() {
    }

    static Plan group(List<RollbackJobChange> changes) {
        Set<ChunkKey> chunks = new HashSet<>();
        for (RollbackJobChange change : changes) {
            chunks.add(ChunkKey.from(change));
        }

        // The executor groups only consecutive chunk runs so saved sequence order survives chunk revisits.
        return new Plan(List.copyOf(changes), chunks.size());
    }

    static int countTargetChunks(List<RollbackTarget> targets) {
        return (int) targets.stream()
                .map(target -> new ChunkKey(target.worldName(), target.x() >> 4, target.z() >> 4))
                .distinct()
                .count();
    }

    record ChunkKey(String worldName, int x, int z) {
        static ChunkKey from(RollbackJobChange change) {
            return new ChunkKey(change.worldName(), change.x() >> 4, change.z() >> 4);
        }
    }

    record Plan(List<RollbackJobChange> changes, int chunkCount) {
    }
}
