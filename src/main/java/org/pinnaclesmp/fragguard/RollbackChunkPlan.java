package org.pinnaclesmp.fragguard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RollbackChunkPlan {
    private RollbackChunkPlan() {
    }

    static Plan group(List<RollbackJobChange> changes) {
        Map<ChunkKey, List<RollbackJobChange>> chunks = new LinkedHashMap<>();
        for (RollbackJobChange change : changes) {
            chunks.computeIfAbsent(ChunkKey.from(change), ignored -> new ArrayList<>()).add(change);
        }

        List<RollbackJobChange> grouped = new ArrayList<>(changes.size());
        for (List<RollbackJobChange> chunkChanges : chunks.values()) {
            grouped.addAll(chunkChanges);
        }
        return new Plan(List.copyOf(grouped), chunks.size());
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
