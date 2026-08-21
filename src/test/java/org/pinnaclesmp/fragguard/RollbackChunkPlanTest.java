package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RollbackChunkPlanTest {
    @Test
    void groupsChangesByChunkWithoutChangingTheOrderWithinEachChunk() {
        RollbackJobChange firstChunkFirst = change(1, "world", 0, 15);
        RollbackJobChange secondChunk = change(2, "world", 16, 0);
        RollbackJobChange firstChunkSecond = change(3, "world", 15, 0);
        RollbackJobChange negativeChunk = change(4, "world", -1, -16);

        RollbackChunkPlan.Plan plan = RollbackChunkPlan.group(List.of(
                firstChunkFirst, secondChunk, firstChunkSecond, negativeChunk));

        assertEquals(3, plan.chunkCount());
        assertEquals(List.of(firstChunkFirst, firstChunkSecond, secondChunk, negativeChunk), plan.changes());
        assertEquals(new RollbackChunkPlan.ChunkKey("world", -1, -1),
                RollbackChunkPlan.ChunkKey.from(negativeChunk));
    }

    @Test
    void keepsMatchingChunkCoordinatesInDifferentWorldsSeparate() {
        RollbackChunkPlan.Plan plan = RollbackChunkPlan.group(List.of(
                change(1, "world", 4, 4),
                change(2, "world_nether", 4, 4)));

        assertEquals(2, plan.chunkCount());
    }

    @Test
    void countsPreviewChunksAcrossPositiveAndNegativeBoundaries() {
        List<RollbackTarget> targets = List.of(
                new RollbackTarget("world", 0, 64, 0, "minecraft:stone"),
                new RollbackTarget("world", 15, 70, 15, "minecraft:stone"),
                new RollbackTarget("world", 16, 64, 0, "minecraft:stone"),
                new RollbackTarget("world", -1, 64, -1, "minecraft:stone"));

        assertEquals(3, RollbackChunkPlan.countTargetChunks(targets));
    }

    private static RollbackJobChange change(int sequence, String worldName, int x, int z) {
        return new RollbackJobChange(sequence, worldName, x, 64, z,
                "minecraft:stone", "minecraft:air", false, false, false);
    }
}
