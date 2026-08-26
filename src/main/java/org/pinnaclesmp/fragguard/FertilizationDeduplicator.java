package org.pinnaclesmp.fragguard;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class FertilizationDeduplicator {
    private long serverTick = Long.MIN_VALUE;
    private final Set<BlockKey> bonemealedStructureBlocks = new HashSet<>();

    void rememberBonemealedStructure(long currentServerTick, Iterable<BlockState> states) {
        advanceTo(currentServerTick);
        for (BlockState state : states) {
            bonemealedStructureBlocks.add(BlockKey.from(state.getBlock()));
        }
    }

    List<BlockState> excludeBonemealedStructureBlocks(long currentServerTick, Iterable<BlockState> states) {
        advanceTo(currentServerTick);
        List<BlockState> filtered = new ArrayList<>();
        for (BlockState state : states) {
            if (!bonemealedStructureBlocks.contains(BlockKey.from(state.getBlock()))) {
                filtered.add(state);
            }
        }
        return List.copyOf(filtered);
    }

    private void advanceTo(long currentServerTick) {
        if (serverTick == currentServerTick) {
            return;
        }
        serverTick = currentServerTick;
        bonemealedStructureBlocks.clear();
    }

    private record BlockKey(String worldName, int x, int y, int z) {
        private static BlockKey from(Block block) {
            return new BlockKey(
                    block.getWorld().getName(),
                    block.getX(),
                    block.getY(),
                    block.getZ()
            );
        }
    }
}
