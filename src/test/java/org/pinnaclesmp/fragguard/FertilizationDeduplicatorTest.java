package org.pinnaclesmp.fragguard;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FertilizationDeduplicatorTest {
    @Test
    void excludesOnlyBlocksClaimedByBonemealedStructureGrowthInTheSameTick() {
        FertilizationDeduplicator deduplicator = new FertilizationDeduplicator();
        BlockState trunk = state(10, 64, 10);
        BlockState leaves = state(10, 65, 10);
        BlockState crop = state(20, 64, 20);

        deduplicator.rememberBonemealedStructure(500L, List.of(trunk, leaves));

        assertEquals(
                List.of(crop),
                deduplicator.excludeBonemealedStructureBlocks(500L, List.of(trunk, crop, leaves))
        );
    }

    @Test
    void forgetsStructureCoordinatesWhenTheServerTickChanges() {
        FertilizationDeduplicator deduplicator = new FertilizationDeduplicator();
        BlockState trunk = state(30, 64, 30);

        deduplicator.rememberBonemealedStructure(500L, List.of(trunk));

        assertEquals(
                List.of(trunk),
                deduplicator.excludeBonemealedStructureBlocks(501L, List.of(trunk))
        );
    }

    @Test
    void leavesOrdinaryFertilizationUntouchedWithoutARecordedStructure() {
        FertilizationDeduplicator deduplicator = new FertilizationDeduplicator();
        BlockState crop = state(40, 64, 40);

        assertEquals(
                List.of(crop),
                deduplicator.excludeBonemealedStructureBlocks(500L, List.of(crop))
        );
    }

    private BlockState state(int x, int y, int z) {
        World world = mock(World.class);
        Block block = mock(Block.class);
        BlockState state = mock(BlockState.class);
        when(world.getName()).thenReturn("world");
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        when(state.getBlock()).thenReturn(block);
        return state;
    }
}
