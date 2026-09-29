package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Shelf;
import org.bukkit.block.data.SideChaining;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShelfInventory;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class ShelfChainBoundaryTest {
    private static final UUID PLAYER_UUID = UUID.fromString("714ea63f-075e-4694-b2c4-ae06a79748aa");

    @Test
    void adjacentMaximumLengthChainsDoNotCrossActorAttributionBoundary() {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        Database database = mock(Database.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        World world = mock(World.class);
        Player player = mock(Player.class);
        List<Runnable> scheduled = new ArrayList<>();
        Map<Integer, Block> blocks = new HashMap<>();

        when(world.getName()).thenReturn("world");
        when(world.getBlockAt(any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenAnswer(invocation -> blocks.get(invocation.<Integer>getArgument(0)));
        when(player.getUniqueId()).thenReturn(PLAYER_UUID);
        when(player.getName()).thenReturn("Builder");
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            scheduled.add(invocation.getArgument(1));
            return task;
        });

        ItemStack[] leftBefore = contents();
        ItemStack[] centerBefore = contents();
        ItemStack[] rightBefore = contents();
        ItemStack[] neighboringBefore = contents();
        ItemStack[] leftAfter = contents();
        ItemStack[] centerAfter = contents();
        ItemStack[] rightAfter = contents();
        ItemStack[] neighboringAfter = contents();

        Block left = shelf(blocks, world, 9, SideChaining.ChainPart.LEFT, leftBefore, leftAfter);
        Block center = shelf(blocks, world, 10, SideChaining.ChainPart.CENTER, centerBefore, centerAfter);
        Block right = shelf(blocks, world, 11, SideChaining.ChainPart.RIGHT, rightBefore, rightAfter);
        Block neighboringLeftEnd = shelf(
                blocks, world, 12, SideChaining.ChainPart.LEFT, neighboringBefore, neighboringAfter);

        when(center.getRelative(BlockFace.WEST)).thenReturn(left);
        when(center.getRelative(BlockFace.EAST)).thenReturn(right);
        when(center.getRelative(BlockFace.WEST, 1)).thenReturn(left);
        when(center.getRelative(BlockFace.EAST, 1)).thenReturn(right);
        when(center.getRelative(BlockFace.EAST, 2)).thenReturn(neighboringLeftEnd);

        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.useInteractedBlock()).thenReturn(Event.Result.DEFAULT);
        when(event.useItemInHand()).thenReturn(Event.Result.DEFAULT);
        when(event.getClickedBlock()).thenReturn(center);
        when(event.getPlayer()).thenReturn(player);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<ItemStack> itemStacks = mockStatic(ItemStack.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getCurrentTick).thenReturn(700);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

            stubSerialization(itemStacks, leftBefore, 1);
            stubSerialization(itemStacks, centerBefore, 2);
            stubSerialization(itemStacks, rightBefore, 3);
            stubSerialization(itemStacks, neighboringBefore, 4);
            stubSerialization(itemStacks, leftAfter, 5);
            stubSerialization(itemStacks, centerAfter, 6);
            stubSerialization(itemStacks, rightAfter, 7);
            stubSerialization(itemStacks, neighboringAfter, 8);

            new ShelfChangeListener(plugin, database).onShelfInteract(event);
            assertEquals(1, scheduled.size());
            scheduled.removeFirst().run();
        }

        ArgumentCaptor<BlockChange> changes = ArgumentCaptor.forClass(BlockChange.class);
        org.mockito.Mockito.verify(database, times(3)).insertAsync(changes.capture());
        assertEquals(Set.of(9, 10, 11),
                changes.getAllValues().stream().map(BlockChange::x).collect(Collectors.toSet()),
                "the adjacent LEFT shelf starts a separate chain and must not inherit the first player's attribution");
    }

    private static Block shelf(
            Map<Integer, Block> blocks,
            World world,
            int x,
            SideChaining.ChainPart part,
            ItemStack[] beforeContents,
            ItemStack[] afterContents
    ) {
        Block block = mock(Block.class);
        org.bukkit.block.data.type.Shelf data = mock(org.bukkit.block.data.type.Shelf.class);
        Shelf before = shelfState(beforeContents);
        Shelf after = shelfState(afterContents);

        when(data.getAsString()).thenReturn("minecraft:shelf[facing=north,powered=true,side_chain="
                + part.name().toLowerCase() + ",waterlogged=false]");
        when(data.getFacing()).thenReturn(BlockFace.NORTH);
        when(data.isPowered()).thenReturn(true);
        when(data.getSideChain()).thenReturn(part);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(10);
        when(block.getBlockData()).thenReturn(data);
        when(block.getState()).thenReturn(before, after);
        blocks.put(x, block);
        return block;
    }

    private static Shelf shelfState(ItemStack[] contents) {
        Shelf shelf = mock(Shelf.class);
        ShelfInventory inventory = mock(ShelfInventory.class);
        when(shelf.getSnapshotInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenReturn(contents);
        return shelf;
    }

    private static ItemStack[] contents() {
        return new ItemStack[]{mock(ItemStack.class), null, null};
    }

    private static void stubSerialization(
            MockedStatic<ItemStack> itemStacks,
            ItemStack[] contents,
            int marker
    ) {
        itemStacks.when(() -> ItemStack.serializeItemsAsBytes(contents))
                .thenReturn(new byte[]{1, 0, 0, 0, 3, marker});
    }
}
