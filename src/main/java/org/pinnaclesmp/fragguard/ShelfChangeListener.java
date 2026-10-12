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
    private final Map<BlockPosition, PendingShelfChange> pendingChanges = new LinkedHashMap<>();

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

        // If another Shelf interaction reaches any of the same coordinates before the deferred comparison runs,
        // the state observed here is the exact intermediate state after the earlier interaction and before this one.
        // Close the earlier transitions now so the later interaction cannot be absorbed into the earlier actor's row.
        closeOverlappingPendingChanges(beforeStates);

        Player player = event.getPlayer();
        long happenedAt = System.currentTimeMillis();
        long serverTick = Bukkit.getCurrentTick();
        Map<BlockPosition, PendingShelfChange> scheduledChanges = new LinkedHashMap<>();
        for (Map.Entry<BlockPosition, CapturedShelfState> entry : beforeStates.entrySet()) {
            PendingShelfChange pending = new PendingShelfChange(
                    entry.getValue(),
                    happenedAt,
                    serverTick,
                    player.getUniqueId().toString(),
                    player.getName()
            );
            pendingChanges.put(entry.getKey(), pending);
            scheduledChanges.put(entry.getKey(), pending);
        }

        Bukkit.getScheduler().runTask(plugin, () -> flushPendingChanges(scheduledChanges));
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

    private void closeOverlappingPendingChanges(Map<BlockPosition, CapturedShelfState> observedStates) {
        for (Map.Entry<BlockPosition, CapturedShelfState> entry : observedStates.entrySet()) {
            PendingShelfChange pending = pendingChanges.remove(entry.getKey());
            if (pending != null) {
                writeChange(entry.getKey(), pending, entry.getValue());
            }
        }
    }

    private void flushPendingChanges(Map<BlockPosition, PendingShelfChange> scheduledChanges) {
        for (Map.Entry<BlockPosition, PendingShelfChange> entry : scheduledChanges.entrySet()) {
            BlockPosition position = entry.getKey();
            PendingShelfChange pending = entry.getValue();
            if (pendingChanges.get(position) != pending) {
                continue;
            }
            pendingChanges.remove(position);

            CapturedShelfState after = captureCurrentShelfState(position, pending.before().blockData());
            if (after != null) {
                writeChange(position, pending, after);
            }
        }
    }

    private CapturedShelfState captureCurrentShelfState(BlockPosition position, String expectedBlockData) {
        World world = Bukkit.getWorld(position.worldName());
        if (world == null) {
            return null;
        }

        Block afterBlock = world.getBlockAt(position.x(), position.y(), position.z());
        BlockData afterBlockData = afterBlock.getBlockData();
        if (!(afterBlockData instanceof org.bukkit.block.data.type.Shelf)
                || !expectedBlockData.equals(afterBlockData.getAsString())) {
            return null;
        }

        BlockState afterState = afterBlock.getState();
        if (!(afterState instanceof Shelf)) {
            return null;
        }
        return new CapturedShelfState(afterBlockData.getAsString(), BlockEntitySnapshot.capture(afterState));
    }

    private void writeChange(
            BlockPosition position,
            PendingShelfChange pending,
            CapturedShelfState after
    ) {
        CapturedShelfState before = pending.before();
        if (!before.blockData().equals(after.blockData()) || Arrays.equals(before.entityData(), after.entityData())) {
            return;
        }

        database.insertAsync(new BlockChange(
                pending.happenedAt(),
                pending.serverTick(),
                pending.actorUuid(),
                pending.actorName(),
                position.worldName(),
                position.x(),
                position.y(),
                position.z(),
                ChangeAction.PLAYER_INTERACT,
                before.blockData(),
                after.blockData(),
                before.entityData(),
                after.entityData()
        ));
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

    private record PendingShelfChange(
            CapturedShelfState before,
            long happenedAt,
            long serverTick,
            String actorUuid,
            String actorName
    ) {
    }

    private record ConnectedShelf(
            Block block,
            BlockData blockData,
            BlockState blockState,
            SideChaining.ChainPart part
    ) {
    }
}
