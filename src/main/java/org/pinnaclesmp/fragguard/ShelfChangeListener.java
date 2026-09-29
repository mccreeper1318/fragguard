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
        SideChaining.ChainPart clickedPart = shelfData.getSideChain();
        if (!shelfData.isPowered() || clickedPart == SideChaining.ChainPart.UNCONNECTED) {
            return;
        }

        BlockFace left = shelfLeftOf(shelfData.getFacing());
        if (left == null) {
            return;
        }
        BlockFace right = left.getOppositeFace();

        switch (clickedPart) {
            case LEFT -> captureFromLeftEnd(beforeStates, clickedShelf, right, shelfData.getFacing());
            case CENTER -> {
                captureExpectedPart(beforeStates, clickedShelf.getRelative(left, 1), shelfData.getFacing(),
                        SideChaining.ChainPart.LEFT);
                captureExpectedPart(beforeStates, clickedShelf.getRelative(right, 1), shelfData.getFacing(),
                        SideChaining.ChainPart.RIGHT);
            }
            case RIGHT -> captureFromRightEnd(beforeStates, clickedShelf, left, shelfData.getFacing());
            case UNCONNECTED -> {
                // Handled above; kept for exhaustiveness if Paper adds no additional chain states.
            }
        }
    }

    private void captureFromLeftEnd(
            Map<BlockPosition, CapturedShelfState> beforeStates,
            Block origin,
            BlockFace right,
            BlockFace expectedFacing
    ) {
        ConnectedShelf first = connectedShelf(origin.getRelative(right, 1), expectedFacing);
        if (first == null) {
            return;
        }
        if (first.part() == SideChaining.ChainPart.RIGHT) {
            captureConnectedShelf(beforeStates, first);
            return;
        }
        if (first.part() != SideChaining.ChainPart.CENTER) {
            return;
        }

        ConnectedShelf terminal = connectedShelf(origin.getRelative(right, 2), expectedFacing);
        if (terminal == null || terminal.part() != SideChaining.ChainPart.RIGHT) {
            return;
        }
        captureConnectedShelf(beforeStates, first);
        captureConnectedShelf(beforeStates, terminal);
    }

    private void captureFromRightEnd(
            Map<BlockPosition, CapturedShelfState> beforeStates,
            Block origin,
            BlockFace left,
            BlockFace expectedFacing
    ) {
        ConnectedShelf first = connectedShelf(origin.getRelative(left, 1), expectedFacing);
        if (first == null) {
            return;
        }
        if (first.part() == SideChaining.ChainPart.LEFT) {
            captureConnectedShelf(beforeStates, first);
            return;
        }
        if (first.part() != SideChaining.ChainPart.CENTER) {
            return;
        }

        ConnectedShelf terminal = connectedShelf(origin.getRelative(left, 2), expectedFacing);
        if (terminal == null || terminal.part() != SideChaining.ChainPart.LEFT) {
            return;
        }
        captureConnectedShelf(beforeStates, first);
        captureConnectedShelf(beforeStates, terminal);
    }

    private void captureExpectedPart(
            Map<BlockPosition, CapturedShelfState> beforeStates,
            Block block,
            BlockFace expectedFacing,
            SideChaining.ChainPart expectedPart
    ) {
        ConnectedShelf shelf = connectedShelf(block, expectedFacing);
        if (shelf != null && shelf.part() == expectedPart) {
            captureConnectedShelf(beforeStates, shelf);
        }
    }

    private ConnectedShelf connectedShelf(Block block, BlockFace expectedFacing) {
        BlockData blockData = block.getBlockData();
        if (!(blockData instanceof org.bukkit.block.data.type.Shelf shelfData)
                || !shelfData.isPowered()
                || shelfData.getFacing() != expectedFacing
                || shelfData.getSideChain() == SideChaining.ChainPart.UNCONNECTED) {
            return null;
        }

        BlockState blockState = block.getState();
        if (!(blockState instanceof Shelf)) {
            return null;
        }
        return new ConnectedShelf(block, blockData, blockState, shelfData.getSideChain());
    }

    private void captureConnectedShelf(
            Map<BlockPosition, CapturedShelfState> beforeStates,
            ConnectedShelf shelf
    ) {
        captureShelf(beforeStates, shelf.block(), shelf.blockData(), shelf.blockState());
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

    private record ConnectedShelf(
            Block block,
            BlockData blockData,
            BlockState blockState,
            SideChaining.ChainPart part
    ) {
    }
}
