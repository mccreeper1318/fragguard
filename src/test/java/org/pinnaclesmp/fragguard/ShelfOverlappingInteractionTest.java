package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Shelf;
import org.bukkit.block.data.SideChaining;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShelfOverlappingInteractionTest {
    private static final UUID FIRST_UUID = UUID.fromString("0e778e53-e9a5-4db1-a75a-a3b44aed44e5");
    private static final UUID SECOND_UUID = UUID.fromString("e349f91b-5115-44e2-8443-a934e7d0ae86");
    private static final String SHELF_DATA =
            "minecraft:shelf[facing=north,powered=false,side_chain=unconnected,waterlogged=false]";

    @Test
    void secondInteractionClosesFirstTransitionAtIntermediateState() {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        Database database = mock(Database.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        World world = mock(World.class);
        Block clickedShelf = mock(Block.class);
        Block deferredShelf = mock(Block.class);
        Shelf firstState = mock(Shelf.class);
        Shelf intermediateState = mock(Shelf.class);
        Shelf finalState = mock(Shelf.class);
        org.bukkit.block.data.type.Shelf shelfData = mock(org.bukkit.block.data.type.Shelf.class);
        Player firstPlayer = player(FIRST_UUID, "FirstBuilder");
        Player secondPlayer = player(SECOND_UUID, "SecondBuilder");
        PlayerInteractEvent firstEvent = event(clickedShelf, firstPlayer);
        PlayerInteractEvent secondEvent = event(clickedShelf, secondPlayer);
        List<Runnable> scheduled = new ArrayList<>();

        when(shelfData.getAsString()).thenReturn(SHELF_DATA);
        when(shelfData.isPowered()).thenReturn(false);
        when(shelfData.getSideChain()).thenReturn(SideChaining.ChainPart.UNCONNECTED);

        when(world.getName()).thenReturn("world");
        when(clickedShelf.getWorld()).thenReturn(world);
        when(clickedShelf.getX()).thenReturn(12);
        when(clickedShelf.getY()).thenReturn(70);
        when(clickedShelf.getZ()).thenReturn(-4);
        when(clickedShelf.getBlockData()).thenReturn(shelfData);
        when(clickedShelf.getState()).thenReturn(firstState, intermediateState);

        when(deferredShelf.getBlockData()).thenReturn(shelfData);
        when(deferredShelf.getState()).thenReturn(finalState);
        when(world.getBlockAt(12, 70, -4)).thenReturn(deferredShelf);

        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            scheduled.add(invocation.getArgument(1));
            return task;
        });

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<BlockEntitySnapshot> snapshots = mockStatic(BlockEntitySnapshot.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getCurrentTick).thenReturn(1_200);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            snapshots.when(() -> BlockEntitySnapshot.capture(firstState)).thenReturn(new byte[]{1});
            snapshots.when(() -> BlockEntitySnapshot.capture(intermediateState)).thenReturn(new byte[]{2});
            snapshots.when(() -> BlockEntitySnapshot.capture(finalState)).thenReturn(new byte[]{3});

            ShelfChangeListener listener = new ShelfChangeListener(plugin, database);
            listener.onShelfInteract(firstEvent);
            listener.onShelfInteract(secondEvent);

            assertEquals(2, scheduled.size());

            // The second event must have closed the first player's pending transition at A -> B already.
            ArgumentCaptor<BlockChange> firstCapture = ArgumentCaptor.forClass(BlockChange.class);
            verify(database, times(1)).insertAsync(firstCapture.capture());
            BlockChange firstChange = firstCapture.getValue();
            assertEquals(FIRST_UUID.toString(), firstChange.actorUuid());
            assertEquals("FirstBuilder", firstChange.actorName());
            assertArrayEquals(new byte[]{1}, firstChange.beforeEntityData());
            assertArrayEquals(new byte[]{2}, firstChange.afterEntityData());

            // The stale callback for player one must not read the shared final C state.
            scheduled.get(0).run();
            verify(database, times(1)).insertAsync(any(BlockChange.class));

            // Player two owns only the B -> C transition.
            scheduled.get(1).run();
        }

        ArgumentCaptor<BlockChange> allChanges = ArgumentCaptor.forClass(BlockChange.class);
        verify(database, times(2)).insertAsync(allChanges.capture());
        List<BlockChange> changes = allChanges.getAllValues();
        BlockChange secondChange = changes.get(1);
        assertEquals(SECOND_UUID.toString(), secondChange.actorUuid());
        assertEquals("SecondBuilder", secondChange.actorName());
        assertArrayEquals(new byte[]{2}, secondChange.beforeEntityData());
        assertArrayEquals(new byte[]{3}, secondChange.afterEntityData());
    }

    private static Player player(UUID uuid, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn(name);
        return player;
    }

    private static PlayerInteractEvent event(Block shelf, Player player) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.useInteractedBlock()).thenReturn(Event.Result.DEFAULT);
        when(event.useItemInHand()).thenReturn(Event.Result.DEFAULT);
        when(event.getClickedBlock()).thenReturn(shelf);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }
}
