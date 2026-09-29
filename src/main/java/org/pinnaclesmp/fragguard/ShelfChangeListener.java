package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Shelf;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.SideChaining;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks the Paper 26.3 Shelf interaction path.
 *
 * <p>Shelves mutate their tile inventory directly when clicked. Their slot contents are not represented in
 * block data, and a powered connected shelf can mutate up to three shelf inventories from one interaction.
 * Ordinary inventory-opening interactions stay excluded by {@link BlockChangeListener}; this listener only
 * observes the Shelf-specific direct-swap behavior.</p>
 */
final class ShelfChangeListener implements Listener {
    private final FragGuardPlugin plugin;
    private final Database database;

    ShelfChangeListener(FragGuardPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onShelfInteract(PlayerInteractEvent event) {
        if (BlockLoggingSuppression.isSuppressed() || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.useInteractedBlock() == Event.Result.DENY && event.useItemInHand() == Event.Result.DENY) {
            return;
        }

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) {
            return;
        }

        BlockState clickedState = clickedBlock.getState();
        BlockData clickedData = clickedBlock.getBlockData();
        if (!(clickedState instanceof Shelf)
                || !(clickedData instanceof org.bukkit.block.data.type.Shelf shelfData)) {
            return;
        }

        Map<BlockPosition, CapturedShelfState> beforeStates = new LinkedHashMap<>();
        captureShelf(beforeStates, clickedBlock, clickedData, clickedState);
        captureConnectedShelves(beforeStates, clickedBlock, shelfData);

        if (beforeStates.isEmpty()) {
            return;
        }

        Player player = event.getPlayer();
        long happenedAt = System.currentTimeMillis();
        long serverTick = Bukkit.getCurrentTick();
        Bukkit.getScheduler().runTask(plugin, () -> writeChanges(
                beforeStates,
                happenedAt,
                serverTick,
                player.getUniqueId().toString(),
                player.getName()
        ));
    }

    private void captureConnectedShelves(
            Map<BlockPosition, CapturedShelfState> beforeStates,
            Block clickedShelf,
            org.bukkit.block.data.type.Shelf shelfData
    ) {
        if (!shelfData.isPowered() || shelfData.getSideChain() == SideChaining.ChainPart.UNCONNECTED) {
            return;
        }

        BlockFace left = shelfLeftOf(shelfData.getFacing());
        if (left == null) {
            return;
        }
        captureShelfChain(beforeStates, clickedShelf, left, shelfData.getFacing());
        captureShelfChain(beforeStates, clickedShelf, left.getOppositeFace(), shelfData.getFacing());
    }

    private void captureShelfChain(
            Map<BlockPosition, CapturedShelfState> beforeStates,
            Block origin,
            BlockFace direction,
            BlockFace expectedFacing
    ) {
        for (int distance = 1; distance <= 2; distance++) {
            Block shelfBlock = origin.getRelative(direction, distance);
            if (shelfBlock == null) {
                return;
            }
            BlockData shelfBlockData = shelfBlock.getBlockData();
            if (!(shelfBlockData instanceof org.bukkit.block.data.type.Shelf adjacentData)
                    || adjacentData.getFacing() != expectedFacing
                    || adjacentData.getSideChain() == SideChaining.ChainPart.UNCONNECTED) {
                return;
            }

            BlockState shelfState = shelfBlock.getState();
            if (!(shelfState instanceof Shelf)) {
                return;
            }
            captureShelf(beforeStates, shelfBlock, shelfBlockData, shelfState);
        }
    }

    private void captureShelf(
            Map<BlockPosition, CapturedShelfState> beforeStates,
            Block block,
            BlockData blockData,
            BlockState state
    ) {
        beforeStates.putIfAbsent(
                positionOf(block),
                new CapturedShelfState(blockData.getAsString(), BlockEntitySnapshot.capture(state))
        );
    }

    private void writeChanges(
            Map<BlockPosition, CapturedShelfState> beforeStates,
            long happenedAt,
            long serverTick,
            String actorUuid,
            String actorName
    ) {
        for (Map.Entry<BlockPosition, CapturedShelfState> entry : beforeStates.entrySet()) {
            BlockPosition position = entry.getKey();
            World world = Bukkit.getWorld(position.worldName());
            if (world == null) {
                continue;
            }

            Block afterBlock = world.getBlockAt(position.x(), position.y(), position.z());
            String afterData = afterBlock.getBlockData().getAsString();
            byte[] afterEntityData = BlockEntitySnapshot.capture(afterBlock);
            CapturedShelfState before = entry.getValue();
            if (before.blockData().equals(afterData) && Arrays.equals(before.entityData(), afterEntityData)) {
                continue;
            }

            database.insertAsync(new BlockChange(
                    happenedAt,
                    serverTick,
                    actorUuid,
                    actorName,
                    position.worldName(),
                    position.x(),
                    position.y(),
                    position.z(),
                    ChangeAction.PLAYER_INTERACT,
                    before.blockData(),
                    afterData,
                    before.entityData(),
                    afterEntityData
            ));
        }
    }

    private BlockFace shelfLeftOf(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.WEST;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            case WEST -> BlockFace.SOUTH;
            default -> null;
        };
    }

    private BlockPosition positionOf(Block block) {
        return new BlockPosition(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private record BlockPosition(String worldName, int x, int y, int z) {
    }

    private record CapturedShelfState(String blockData, byte[] entityData) {
    }
}
