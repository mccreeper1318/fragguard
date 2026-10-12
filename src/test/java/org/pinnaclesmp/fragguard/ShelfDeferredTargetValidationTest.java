package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Shelf;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.SideChaining;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShelfDeferredTargetValidationTest {
    private static final UUID PLAYER_UUID = UUID.fromString("714ea63f-075e-4694-b2c4-ae06a79748aa");
    private static final String SHELF_DATA =
            "minecraft:shelf[facing=north,powered=false,side_chain=unconnected,waterlogged=false]";

    @Test
    void replacedShelfIsNotLoggedAsTheOriginalPlayersInteraction() {
        BlockData replacementData = mock(BlockData.class);
        when(replacementData.getAsString()).thenReturn("minecraft:air");

        assertDeferredChangeIgnored(replacementData, mock(BlockState.class));
    }

    @Test
    void structurallyChangedShelfIsNotLoggedAsTheOriginalPlayersInteraction() {
        org.bukkit.block.data.type.Shelf changedData = mock(org.bukkit.block.data.type.Shelf.class);
        when(changedData.getAsString()).thenReturn(
                "minecraft:shelf[facing=north,powered=true,side_chain=unconnected,waterlogged=false]");

        assertDeferredChangeIgnored(changedData, mock(Shelf.class));
    }

    private static void assertDeferredChangeIgnored(BlockData afterData, BlockState afterState) {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        Database database = mock(Database.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        World world = mock(World.class);
        Player player = mock(Player.class);
        Block clickedShelf = mock(Block.class);
        Block deferredBlock = mock(Block.class);
        Shelf beforeState = mock(Shelf.class);
        org.bukkit.block.data.type.Shelf beforeData = mock(org.bukkit.block.data.type.Shelf.class);
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        List<Runnable> scheduled = new ArrayList<>();

        when(beforeData.getAsString()).thenReturn(SHELF_DATA);
        when(beforeData.isPowered()).thenReturn(false);
        when(beforeData.getSideChain()).thenReturn(SideChaining.ChainPart.UNCONNECTED);

        when(clickedShelf.getWorld()).thenReturn(world);
        when(clickedShelf.getX()).thenReturn(10);
        when(clickedShelf.getY()).thenReturn(64);
        when(clickedShelf.getZ()).thenReturn(10);
        when(clickedShelf.getState()).thenReturn(beforeState);
        when(clickedShelf.getBlockData()).thenReturn(beforeData);

        when(deferredBlock.getBlockData()).thenReturn(afterData);
        when(deferredBlock.getState()).thenReturn(afterState);
        when(world.getName()).thenReturn("world");
        when(world.getBlockAt(10, 64, 10)).thenReturn(deferredBlock);

        when(player.getUniqueId()).thenReturn(PLAYER_UUID);
        when(player.getName()).thenReturn("Builder");
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.useInteractedBlock()).thenReturn(Event.Result.DEFAULT);
        when(event.useItemInHand()).thenReturn(Event.Result.DEFAULT);
        when(event.getClickedBlock()).thenReturn(clickedShelf);
        when(event.getPlayer()).thenReturn(player);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            scheduled.add(invocation.getArgument(1));
            return task;
        });

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<BlockEntitySnapshot> snapshots = mockStatic(BlockEntitySnapshot.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getCurrentTick).thenReturn(900);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            snapshots.when(() -> BlockEntitySnapshot.capture(beforeState)).thenReturn(new byte[]{1});
            snapshots.when(() -> BlockEntitySnapshot.capture(afterState)).thenReturn(new byte[]{2});

            new ShelfChangeListener(plugin, database).onShelfInteract(event);
            assertEquals(1, scheduled.size());
            scheduled.removeFirst().run();
        }

        verify(database, never()).insertAsync(any(BlockChange.class));
    }
}
