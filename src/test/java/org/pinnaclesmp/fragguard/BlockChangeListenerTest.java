package org.pinnaclesmp.fragguard;

import io.papermc.paper.block.TileStateInventoryHolder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Lectern;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlockChangeListenerTest {
    private static final UUID PLAYER_UUID = UUID.fromString("f7afdf9f-a9f7-4e30-88bc-614cf037d006");

    @ParameterizedTest(name = "{0}")
    @MethodSource("multiBlockPlacements")
    void logsEveryAffectedCoordinate(
            String placementName,
            List<PlacedPart> parts
    ) {
        Database database = mock(Database.class);
        BlockChangeListener listener = new BlockChangeListener(null, database);
        Player player = mock(Player.class);
        BlockMultiPlaceEvent event = mock(BlockMultiPlaceEvent.class);

        when(player.getUniqueId()).thenReturn(PLAYER_UUID);
        when(player.getName()).thenReturn("Builder");
        when(event.getPlayer()).thenReturn(player);

        List<BlockState> replacedStates = new ArrayList<>();
        for (PlacedPart part : parts) {
            replacedStates.add(mockReplacedState(part));
        }
        when(event.getReplacedBlockStates()).thenReturn(replacedStates);

        listener.onBlockPlace(event);

        ArgumentCaptor<BlockChange> changes = ArgumentCaptor.forClass(BlockChange.class);
        verify(database, times(parts.size())).insertAsync(changes.capture());
        verify(event, never()).getBlockPlaced();
        verify(event, never()).getBlockReplacedState();

        for (int index = 0; index < parts.size(); index++) {
            PlacedPart expected = parts.get(index);
            BlockChange actual = changes.getAllValues().get(index);

            assertEquals(PLAYER_UUID.toString(), actual.actorUuid());
            assertEquals("Builder", actual.actorName());
            assertEquals("world", actual.worldName());
            assertEquals(expected.x(), actual.x());
            assertEquals(expected.y(), actual.y());
            assertEquals(expected.z(), actual.z());
            assertEquals(ChangeAction.PLACE, actual.action());
            assertEquals(expected.beforeData(), actual.beforeData());
            assertEquals(expected.afterData(), actual.afterData());
        }
    }

    @Test
    void keepsSingleBlockPlacementOnTheOrdinaryBranch() {
        Database database = mock(Database.class);
        BlockChangeListener listener = new BlockChangeListener(null, database);
        Player player = mock(Player.class);
        org.bukkit.event.block.BlockPlaceEvent event =
                mock(org.bukkit.event.block.BlockPlaceEvent.class);
        PlacedPart part = new PlacedPart(
                4, 72, -3,
                "minecraft:air",
                "minecraft:stone"
        );
        BlockState replacedState = mockReplacedState(part);

        when(player.getUniqueId()).thenReturn(PLAYER_UUID);
        when(player.getName()).thenReturn("Builder");
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlockReplacedState()).thenReturn(replacedState);

        listener.onBlockPlace(event);

        ArgumentCaptor<BlockChange> change = ArgumentCaptor.forClass(BlockChange.class);
        verify(database).insertAsync(change.capture());
        assertEquals(part.afterData(), change.getValue().afterData());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("postBreakTransitions")
    void recordsTheActualStateAfterTheServerBreaksTheBlock(
            String caseName,
            String beforeData,
            String afterData
    ) {
        try (BreakHarness harness = new BreakHarness()) {
            Neighborhood neighborhood = harness.neighborhood(
                    5,
                    64,
                    5,
                    beforeData,
                    afterData,
                    Map.of()
            );

            harness.listener.onBlockBreak(
                    harness.breakEvent(neighborhood.center())
            );

            verify(harness.database, never()).insertAsync(any());
            harness.runNextTick();

            ArgumentCaptor<BlockChange> change =
                    ArgumentCaptor.forClass(BlockChange.class);
            verify(harness.database).insertAsync(change.capture());
            assertEquals(beforeData, change.getValue().beforeData());
            assertEquals(afterData, change.getValue().afterData());
            assertEquals(ChangeAction.BREAK, change.getValue().action());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pairedStructures")
    void recordsSecondaryBlocksRemovedWithPairedStructures(
            String structureName,
            BlockFace secondaryFace,
            String primaryData,
            String secondaryData
    ) {
        try (BreakHarness harness = new BreakHarness()) {
            Neighborhood neighborhood = harness.neighborhood(
                    10,
                    70,
                    10,
                    primaryData,
                    "minecraft:air",
                    Map.of(
                            secondaryFace,
                            new StateChange(
                                    secondaryData,
                                    "minecraft:air"
                            )
                    )
            );

            harness.listener.onBlockBreak(
                    harness.breakEvent(neighborhood.center())
            );
            harness.runNextTick();

            ArgumentCaptor<BlockChange> changes =
                    ArgumentCaptor.forClass(BlockChange.class);
            verify(harness.database, times(2))
                    .insertAsync(changes.capture());

            List<BlockChange> logged = changes.getAllValues();
            assertEquals(
                    List.of(primaryData, secondaryData),
                    logged.stream().map(BlockChange::beforeData).toList()
            );
            assertEquals(
                    List.of("minecraft:air", "minecraft:air"),
                    logged.stream().map(BlockChange::afterData).toList()
            );
        }
    }

    @Test
    void coalescesPhysicsChangesIntoThePendingPlayerBreak() {
        try (BreakHarness harness = new BreakHarness()) {
            Neighborhood neighborhood = harness.neighborhood(
                    20,
                    80,
                    20,
                    "minecraft:stone",
                    "minecraft:air",
                    Map.of(
                            BlockFace.EAST,
                            new StateChange(
                                    "minecraft:stone",
                                    "minecraft:stone"
                            )
                    )
            );
            Block physicsBlock = harness.block(
                    22,
                    80,
                    20,
                    "minecraft:wall_torch[facing=east]",
                    "minecraft:air"
            );

            harness.listener.onBlockBreak(
                    harness.breakEvent(neighborhood.center())
            );

            BlockPhysicsEvent physicsEvent = harness.physicsEvent(
                    physicsBlock,
                    neighborhood.neighbors().get(BlockFace.EAST)
            );
            harness.listener.onBlockPhysics(physicsEvent);
            harness.listener.onBlockPhysics(physicsEvent);

            assertEquals(1, harness.scheduledTasks.size());
            harness.runNextTick();

            ArgumentCaptor<BlockChange> changes =
                    ArgumentCaptor.forClass(BlockChange.class);
            verify(harness.database, times(2))
                    .insertAsync(changes.capture());
            assertEquals(
                    List.of("minecraft:stone", "minecraft:wall_torch[facing=east]"),
                    changes.getAllValues().stream()
                            .map(BlockChange::beforeData)
                            .toList()
            );
        }
    }

    @Test
    void coalescesMultipleBreakEventsFromTheSamePlayerInOneTick() {
        try (BreakHarness harness = new BreakHarness()) {
            Neighborhood first = harness.neighborhood(
                    30,
                    64,
                    30,
                    "minecraft:stone",
                    "minecraft:air",
                    Map.of()
            );
            Neighborhood second = harness.neighborhood(
                    40,
                    64,
                    40,
                    "minecraft:dirt",
                    "minecraft:air",
                    Map.of()
            );

            harness.listener.onBlockBreak(
                    harness.breakEvent(first.center())
            );
            harness.listener.onBlockBreak(
                    harness.breakEvent(second.center())
            );

            assertEquals(1, harness.scheduledTasks.size());
            harness.runNextTick();
            verify(harness.database, times(2)).insertAsync(any());
        }
    }

    @Test
    void recordsInitialIgnitionAfterApplicationWithPlayerAttribution() {
        try (DeferredChangeHarness harness = new DeferredChangeHarness()) {
            Block target = harness.block(
                    5, 65, 5,
                    "minecraft:air",
                    "minecraft:fire[age=0,east=false,north=false,south=false,up=false,west=false]"
            );
            BlockIgniteEvent event = mock(BlockIgniteEvent.class);
            when(event.getBlock()).thenReturn(target);
            when(event.getCause()).thenReturn(BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL);
            when(event.getIgnitingEntity()).thenReturn(harness.player);

            harness.listener.onBlockIgnite(event);

            verify(harness.database, never()).insertAsync(any());
            harness.runNextTick();

            BlockChange change = harness.captureSingleChange();
            assertEquals(ChangeAction.FIRE_IGNITE, change.action());
            assertEquals(PLAYER_UUID.toString(), change.actorUuid());
            assertEquals("Builder", change.actorName());
            assertEquals("minecraft:air", change.beforeData());
            assertEquals("minecraft:fire[age=0,east=false,north=false,south=false,up=false,west=false]",
                    change.afterData());
        }
    }

    @Test
    void recordsEverySpongeAffectedBlockIncludingTheSponge() {
        try (DeferredChangeHarness harness = new DeferredChangeHarness()) {
            Block sponge = harness.block(10, 64, 10, "minecraft:sponge", "minecraft:wet_sponge");
            Block firstWater = harness.block(11, 64, 10, "minecraft:water[level=0]", "minecraft:air");
            Block waterlogged = harness.block(
                    10, 64, 11,
                    "minecraft:oak_slab[type=bottom,waterlogged=true]",
                    "minecraft:oak_slab[type=bottom,waterlogged=false]"
            );
            SpongeAbsorbEvent event = mock(SpongeAbsorbEvent.class);
            BlockState firstWaterState = harness.state(firstWater);
            BlockState waterloggedState = harness.state(waterlogged);
            when(event.getBlock()).thenReturn(sponge);
            when(event.getBlocks()).thenReturn(List.of(
                    firstWaterState,
                    waterloggedState
            ));

            harness.listener.onSpongeAbsorb(event);
            harness.runNextTick();

            ArgumentCaptor<BlockChange> changes = ArgumentCaptor.forClass(BlockChange.class);
            verify(harness.database, times(3)).insertAsync(changes.capture());
            assertEquals(
                    List.of(ChangeAction.SPONGE_ABSORB, ChangeAction.SPONGE_ABSORB,
                            ChangeAction.SPONGE_ABSORB),
                    changes.getAllValues().stream().map(BlockChange::action).toList()
            );
            assertEquals(
                    List.of("minecraft:wet_sponge", "minecraft:air",
                            "minecraft:oak_slab[type=bottom,waterlogged=false]"),
                    changes.getAllValues().stream().map(BlockChange::afterData).toList()
            );
        }
    }

    @Test
    void recordsDispenserBucketChangesAtTheFacingBlock() {
        try (DeferredChangeHarness harness = new DeferredChangeHarness()) {
            Block dispenser = mock(Block.class);
            Directional dispenserData = mock(Directional.class);
            Block target = harness.block(21, 70, 20, "minecraft:air", "minecraft:water[level=0]");
            ItemStack bucket = mock(ItemStack.class);
            BlockDispenseEvent event = mock(BlockDispenseEvent.class);

            when(dispenser.getBlockData()).thenReturn(dispenserData);
            when(dispenserData.getFacing()).thenReturn(BlockFace.EAST);
            when(dispenser.getRelative(BlockFace.EAST)).thenReturn(target);
            when(bucket.getType()).thenReturn(Material.WATER_BUCKET);
            when(event.getBlock()).thenReturn(dispenser);
            when(event.getItem()).thenReturn(bucket);

            harness.listener.onBlockDispense(event);
            harness.runNextTick();

            BlockChange change = harness.captureSingleChange();
            assertEquals(ChangeAction.DISPENSER_LIQUID_PLACE, change.action());
            assertEquals("SYSTEM", change.actorUuid());
            assertEquals("Dispenser: Water Bucket", change.actorName());
            assertEquals(21, change.x());
            assertEquals("minecraft:water[level=0]", change.afterData());
        }
    }

    @Test
    void recordsEntityBlockChangesWithEntityIdentityAndCause() {
        try (DeferredChangeHarness harness = new DeferredChangeHarness()) {
            UUID entityUuid = UUID.fromString("f9c6b634-0586-429d-b4e7-d50e16ab6d8c");
            Entity entity = mock(Entity.class);
            Block target = harness.block(30, 80, 30, "minecraft:grass_block", "minecraft:air");
            EntityChangeBlockEvent event = mock(EntityChangeBlockEvent.class);

            when(entity.getUniqueId()).thenReturn(entityUuid);
            when(entity.getType()).thenReturn(EntityType.ENDERMAN);
            when(event.getEntity()).thenReturn(entity);
            when(event.getBlock()).thenReturn(target);

            harness.listener.onEntityChangeBlock(event);
            harness.runNextTick();

            BlockChange change = harness.captureSingleChange();
            assertEquals(ChangeAction.ENTITY_CHANGE_BLOCK, change.action());
            assertEquals(entityUuid.toString(), change.actorUuid());
            assertEquals("Entity Block Change: Enderman", change.actorName());
            assertEquals("minecraft:air", change.afterData());
        }
    }

    @Test
    void recordsNaturalGrowthFadeFormSpreadAndDecayAsActualTransitions() {
        try (DeferredChangeHarness harness = new DeferredChangeHarness()) {
            Block grown = harness.block(40, 64, 40, "minecraft:wheat[age=6]", "minecraft:wheat[age=7]");
            Block faded = harness.block(41, 64, 40, "minecraft:ice", "minecraft:water[level=0]");
            Block formed = harness.block(42, 64, 40, "minecraft:water[level=0]", "minecraft:ice");
            Block spread = harness.block(43, 64, 40, "minecraft:air", "minecraft:vine[east=true]");
            Block source = mock(Block.class);
            BlockState spreadState = mock(BlockState.class);
            Block decayed = harness.block(44, 64, 40, "minecraft:oak_leaves[persistent=false]", "minecraft:air");

            BlockGrowEvent growEvent = mock(BlockGrowEvent.class);
            when(growEvent.getBlock()).thenReturn(grown);
            BlockFadeEvent fadeEvent = mock(BlockFadeEvent.class);
            when(fadeEvent.getBlock()).thenReturn(faded);
            BlockFormEvent formEvent = mock(BlockFormEvent.class);
            when(formEvent.getBlock()).thenReturn(formed);
            BlockSpreadEvent spreadEvent = mock(BlockSpreadEvent.class);
            when(spreadEvent.getBlock()).thenReturn(spread);
            when(spreadEvent.getSource()).thenReturn(source);
            when(spreadEvent.getNewState()).thenReturn(spreadState);
            when(source.getType()).thenReturn(Material.VINE);
            when(spreadState.getType()).thenReturn(Material.VINE);
            LeavesDecayEvent decayEvent = mock(LeavesDecayEvent.class);
            when(decayEvent.getBlock()).thenReturn(decayed);

            harness.listener.onBlockGrow(growEvent);
            harness.listener.onBlockFade(fadeEvent);
            harness.listener.onBlockForm(formEvent);
            harness.listener.onFireSpread(spreadEvent);
            harness.listener.onLeavesDecay(decayEvent);
            harness.runAllTasks();

            ArgumentCaptor<BlockChange> changes = ArgumentCaptor.forClass(BlockChange.class);
            verify(harness.database, times(5)).insertAsync(changes.capture());
            assertEquals(
                    List.of(ChangeAction.BLOCK_GROW, ChangeAction.BLOCK_FADE, ChangeAction.BLOCK_FORM,
                            ChangeAction.BLOCK_SPREAD, ChangeAction.LEAVES_DECAY),
                    changes.getAllValues().stream().map(BlockChange::action).toList()
            );
        }
    }

    @Test
    void recordsStructureAndFertilizationChangesWithAvailablePlayerAttribution() {
        try (DeferredChangeHarness harness = new DeferredChangeHarness()) {
            Block trunk = harness.block(50, 64, 50, "minecraft:air", "minecraft:oak_log[axis=y]");
            Block leaves = harness.block(50, 65, 50, "minecraft:air", "minecraft:oak_leaves[persistent=false]");
            StructureGrowEvent structureEvent = mock(StructureGrowEvent.class);
            BlockState trunkState = harness.state(trunk);
            BlockState leavesState = harness.state(leaves);
            when(structureEvent.getBlocks()).thenReturn(List.of(trunkState, leavesState));
            when(structureEvent.isFromBonemeal()).thenReturn(false);

            Block crop = harness.block(60, 64, 60, "minecraft:wheat[age=2]", "minecraft:wheat[age=4]");
            BlockFertilizeEvent fertilizeEvent = mock(BlockFertilizeEvent.class);
            BlockState cropState = harness.state(crop);
            when(fertilizeEvent.getBlocks()).thenReturn(List.of(cropState));
            when(fertilizeEvent.getPlayer()).thenReturn(harness.player);

            harness.listener.onStructureGrow(structureEvent);
            harness.listener.onBlockFertilize(fertilizeEvent);
            harness.runAllTasks();

            ArgumentCaptor<BlockChange> changes = ArgumentCaptor.forClass(BlockChange.class);
            verify(harness.database, times(3)).insertAsync(changes.capture());
            assertEquals(
                    List.of(ChangeAction.STRUCTURE_GROW, ChangeAction.STRUCTURE_GROW,
                            ChangeAction.FERTILIZE),
                    changes.getAllValues().stream().map(BlockChange::action).toList()
            );
            BlockChange fertilized = changes.getAllValues().getLast();
            assertEquals(PLAYER_UUID.toString(), fertilized.actorUuid());
            assertEquals("Builder", fertilized.actorName());
        }
    }

    @Test
    void recordsPlayerInteractionsThatActuallyChangeBlockData() {
        try (DeferredChangeHarness harness = new DeferredChangeHarness()) {
            Block door = harness.block(
                    70, 64, 70,
                    "minecraft:oak_door[half=lower,open=false]",
                    "minecraft:oak_door[half=lower,open=true]"
            );
            PlayerInteractEvent event = harness.interactEvent(door);

            harness.listener.onPlayerInteract(event);
            harness.runNextTick();

            BlockChange change = harness.captureSingleChange();
            assertEquals(ChangeAction.PLAYER_INTERACT, change.action());
            assertEquals(PLAYER_UUID.toString(), change.actorUuid());
            assertEquals("Builder", change.actorName());
            assertEquals("minecraft:oak_door[half=lower,open=true]", change.afterData());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inventoryBearingStructuralInteractions")
    void preservesInventorySnapshotsWhenPlayerInteractionsChangeBlockData(
            String interactionName,
            String beforeData,
            String afterData,
            boolean lectern,
            boolean beforeHasBook
    ) {
        try (DeferredChangeHarness harness = new DeferredChangeHarness()) {
            Block interactable = harness.block(75, 64, 75, beforeData, afterData);
            TileStateInventoryHolder beforeState = lectern
                    ? mock(Lectern.class)
                    : mock(TileStateInventoryHolder.class);
            TileStateInventoryHolder afterState = lectern
                    ? mock(Lectern.class)
                    : mock(TileStateInventoryHolder.class);
            Inventory beforeInventory = mock(Inventory.class);
            Inventory afterInventory = mock(Inventory.class);
            ItemStack[] bookContents = new ItemStack[]{mock(ItemStack.class)};
            ItemStack[] emptyContents = new ItemStack[]{null};
            ItemStack[] beforeContents = beforeHasBook ? bookContents : emptyContents;
            ItemStack[] afterContents = beforeHasBook ? emptyContents : bookContents;
            byte[] serializedBefore = beforeHasBook ? new byte[]{1, 2, 3} : new byte[]{4, 5, 6};
            byte[] serializedAfter = beforeHasBook ? new byte[]{4, 5, 6} : new byte[]{1, 2, 3};

            when(beforeState.getSnapshotInventory()).thenReturn(beforeInventory);
            when(beforeInventory.getContents()).thenReturn(beforeContents);
            when(afterState.getSnapshotInventory()).thenReturn(afterInventory);
            when(afterInventory.getContents()).thenReturn(afterContents);
            when(interactable.getState()).thenReturn(beforeState, afterState);

            try (MockedStatic<ItemStack> itemStacks = mockStatic(ItemStack.class)) {
                itemStacks.when(() -> ItemStack.serializeItemsAsBytes(beforeContents))
                        .thenReturn(serializedBefore);
                itemStacks.when(() -> ItemStack.serializeItemsAsBytes(afterContents))
                        .thenReturn(serializedAfter);
                itemStacks.when(() -> ItemStack.deserializeItemsFromBytes(serializedBefore))
                        .thenReturn(beforeContents);
                itemStacks.when(() -> ItemStack.deserializeItemsFromBytes(serializedAfter))
                        .thenReturn(afterContents);

                harness.listener.onPlayerInteract(harness.interactEvent(interactable));
                harness.runNextTick();

                BlockChange change = harness.captureSingleChange();
                assertEquals(ChangeAction.PLAYER_INTERACT, change.action());
                assertEquals(beforeData, change.beforeData());
                assertEquals(afterData, change.afterData());
                assertNotNull(change.beforeEntityData());
                assertNotNull(change.afterEntityData());
                assertFalse(Arrays.equals(change.beforeEntityData(), change.afterEntityData()));

                Block restorationBlock = mock(Block.class);
                TileStateInventoryHolder restorationState = lectern
                        ? mock(Lectern.class)
                        : mock(TileStateInventoryHolder.class);
                Inventory restorationInventory = mock(Inventory.class);
                when(restorationState.getSnapshotInventory()).thenReturn(restorationInventory);
                when(restorationState.update(true, false)).thenReturn(true);
                when(restorationBlock.getState()).thenReturn(restorationState);

                BlockEntitySnapshot.restore(restorationBlock, change.beforeEntityData());
                verify(restorationInventory).setContents(beforeContents);
                BlockEntitySnapshot.restore(restorationBlock, change.afterEntityData());
                verify(restorationInventory).setContents(afterContents);
            }
        }
    }

    @Test
    void doesNotLogAnUnchangedContainerWhenAPlayerOnlyOpensIt() {
        try (DeferredChangeHarness harness = new DeferredChangeHarness()) {
            Block chest = harness.block(
                    80, 64, 80,
                    "minecraft:chest[facing=north,type=single,waterlogged=false]",
                    "minecraft:chest[facing=north,type=single,waterlogged=false]"
            );
            TileStateInventoryHolder beforeState = mock(TileStateInventoryHolder.class);
            TileStateInventoryHolder afterState = mock(TileStateInventoryHolder.class);
            Inventory beforeInventory = mock(Inventory.class);
            ItemStack[] beforeContents = new ItemStack[]{mock(ItemStack.class)};
            when(beforeState.getSnapshotInventory()).thenReturn(beforeInventory);
            when(beforeInventory.getContents()).thenReturn(beforeContents);
            when(chest.getState()).thenReturn(beforeState, afterState);

            try (MockedStatic<ItemStack> itemStacks = mockStatic(ItemStack.class)) {
                itemStacks.when(() -> ItemStack.serializeItemsAsBytes(beforeContents))
                        .thenReturn(new byte[]{7, 8, 9});

                harness.listener.onPlayerInteract(harness.interactEvent(chest));
                harness.runNextTick();

                verify(harness.database, never()).insertAsync(any());
                verify(afterState, never()).getSnapshotInventory();
            }
        }
    }

    private static Stream<Arguments> inventoryBearingStructuralInteractions() {
        return Stream.of(
                Arguments.of(
                        "remove a book from a chiseled bookshelf",
                        "minecraft:chiseled_bookshelf[slot_0_occupied=true]",
                        "minecraft:chiseled_bookshelf[slot_0_occupied=false]",
                        false,
                        true
                ),
                Arguments.of(
                        "insert a book into a chiseled bookshelf",
                        "minecraft:chiseled_bookshelf[slot_0_occupied=false]",
                        "minecraft:chiseled_bookshelf[slot_0_occupied=true]",
                        false,
                        false
                ),
                Arguments.of(
                        "remove a book from a lectern",
                        "minecraft:lectern[facing=north,has_book=true,powered=false]",
                        "minecraft:lectern[facing=north,has_book=false,powered=false]",
                        true,
                        true
                ),
                Arguments.of(
                        "insert a book into a lectern",
                        "minecraft:lectern[facing=north,has_book=false,powered=false]",
                        "minecraft:lectern[facing=north,has_book=true,powered=false]",
                        true,
                        false
                )
        );
    }

    private static Stream<Arguments> postBreakTransitions() {
        return Stream.of(
                Arguments.of(
                        "waterlogged block",
                        "minecraft:oak_slab[type=bottom,waterlogged=true]",
                        "minecraft:water[level=0]"
                ),
                Arguments.of(
                        "ice becoming water",
                        "minecraft:ice",
                        "minecraft:water[level=0]"
                )
        );
    }

    private static Stream<Arguments> pairedStructures() {
        return Stream.of(
                Arguments.of(
                        "door",
                        BlockFace.UP,
                        "minecraft:oak_door[half=lower]",
                        "minecraft:oak_door[half=upper]"
                ),
                Arguments.of(
                        "bed",
                        BlockFace.NORTH,
                        "minecraft:red_bed[part=foot]",
                        "minecraft:red_bed[part=head]"
                )
        );
    }

    private static BlockState mockReplacedState(PlacedPart part) {
        World world = mock(World.class);
        Block block = mock(Block.class);
        BlockState replacedState = mock(BlockState.class);
        BlockData beforeData = mock(BlockData.class);
        BlockData afterData = mock(BlockData.class);

        when(world.getName()).thenReturn("world");
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(part.x());
        when(block.getY()).thenReturn(part.y());
        when(block.getZ()).thenReturn(part.z());
        when(block.getBlockData()).thenReturn(afterData);
        when(afterData.getAsString()).thenReturn(part.afterData());
        when(replacedState.getBlock()).thenReturn(block);
        when(replacedState.getBlockData()).thenReturn(beforeData);
        when(beforeData.getAsString()).thenReturn(part.beforeData());

        return replacedState;
    }

    private static Stream<Arguments> multiBlockPlacements() {
        return Stream.of(
                Arguments.of("bed", List.of(
                        new PlacedPart(10, 64, 10, "minecraft:air",
                                "minecraft:red_bed[facing=north,occupied=false,part=foot]"),
                        new PlacedPart(10, 64, 9, "minecraft:air",
                                "minecraft:red_bed[facing=north,occupied=false,part=head]")
                )),
                Arguments.of("door", List.of(
                        new PlacedPart(20, 64, 20, "minecraft:air",
                                "minecraft:oak_door[facing=east,half=lower,hinge=left,open=false,powered=false]"),
                        new PlacedPart(20, 65, 20, "minecraft:air",
                                "minecraft:oak_door[facing=east,half=upper,hinge=left,open=false,powered=false]")
                )),
                Arguments.of("tall plant", List.of(
                        new PlacedPart(30, 64, 30, "minecraft:air",
                                "minecraft:sunflower[half=lower]"),
                        new PlacedPart(30, 65, 30, "minecraft:air",
                                "minecraft:sunflower[half=upper]")
                )),
                Arguments.of("pointed dripstone", List.of(
                        new PlacedPart(40, 70, 40, "minecraft:air",
                                "minecraft:pointed_dripstone[thickness=base,vertical_direction=down]"),
                        new PlacedPart(40, 69, 40, "minecraft:air",
                                "minecraft:pointed_dripstone[thickness=tip,vertical_direction=down]")
                ))
        );
    }

    private static final class DeferredChangeHarness implements AutoCloseable {
        private final FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        private final Database database = mock(Database.class);
        private final FileConfiguration config = mock(FileConfiguration.class);
        private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        private final BukkitTask task = mock(BukkitTask.class);
        private final World world = mock(World.class);
        private final Player player = mock(Player.class);
        private final Map<BlockCoordinate, Block> blocks = new HashMap<>();
        private final List<Runnable> scheduledTasks = new ArrayList<>();
        private final MockedStatic<Bukkit> bukkit = mockStaticBukkit();
        private final BlockChangeListener listener = new BlockChangeListener(plugin, database);

        private DeferredChangeHarness() {
            when(plugin.getConfig()).thenReturn(config);
            when(config.getBoolean("log-fire-spread", true)).thenReturn(true);
            when(config.getBoolean("log-liquid-flow", true)).thenReturn(true);
            when(world.getName()).thenReturn("world");
            when(player.getUniqueId()).thenReturn(PLAYER_UUID);
            when(player.getName()).thenReturn("Builder");
            when(world.getBlockAt(anyInt(), anyInt(), anyInt()))
                    .thenAnswer(invocation -> blocks.get(new BlockCoordinate(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2)
                    )));
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
                scheduledTasks.add(invocation.getArgument(1));
                return task;
            });
        }

        private MockedStatic<Bukkit> mockStaticBukkit() {
            MockedStatic<Bukkit> mockedBukkit = org.mockito.Mockito.mockStatic(Bukkit.class);
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            mockedBukkit.when(Bukkit::getCurrentTick).thenReturn(500);
            mockedBukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            return mockedBukkit;
        }

        private Block block(int x, int y, int z, String beforeData, String afterData) {
            Block block = mock(Block.class);
            BlockData before = mock(BlockData.class);
            BlockData after = mock(BlockData.class);
            BlockState state = mock(BlockState.class);

            when(before.getAsString()).thenReturn(beforeData);
            when(after.getAsString()).thenReturn(afterData);
            when(block.getWorld()).thenReturn(world);
            when(block.getX()).thenReturn(x);
            when(block.getY()).thenReturn(y);
            when(block.getZ()).thenReturn(z);
            when(block.getBlockData()).thenReturn(before, after);
            when(block.getState()).thenReturn(state);
            blocks.put(new BlockCoordinate(x, y, z), block);
            return block;
        }

        private BlockState state(Block block) {
            BlockState state = mock(BlockState.class);
            when(state.getBlock()).thenReturn(block);
            return state;
        }

        private PlayerInteractEvent interactEvent(Block block) {
            PlayerInteractEvent event = mock(PlayerInteractEvent.class);
            when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
            when(event.useInteractedBlock()).thenReturn(Event.Result.DEFAULT);
            when(event.useItemInHand()).thenReturn(Event.Result.DEFAULT);
            when(event.getClickedBlock()).thenReturn(block);
            when(event.getPlayer()).thenReturn(player);
            return event;
        }

        private void runNextTick() {
            assertEquals(1, scheduledTasks.size());
            scheduledTasks.removeFirst().run();
        }

        private void runAllTasks() {
            while (!scheduledTasks.isEmpty()) {
                scheduledTasks.removeFirst().run();
            }
        }

        private BlockChange captureSingleChange() {
            ArgumentCaptor<BlockChange> change = ArgumentCaptor.forClass(BlockChange.class);
            verify(database).insertAsync(change.capture());
            return change.getValue();
        }

        @Override
        public void close() {
            bukkit.close();
        }
    }

    private static final class BreakHarness implements AutoCloseable {
        private static final StateChange UNCHANGED_AIR =
                new StateChange("minecraft:air", "minecraft:air");
        private static final BlockFace[] NEIGHBOR_FACES = {
                BlockFace.UP,
                BlockFace.DOWN,
                BlockFace.NORTH,
                BlockFace.SOUTH,
                BlockFace.EAST,
                BlockFace.WEST
        };

        private final FragGuardPlugin plugin =
                mock(FragGuardPlugin.class);
        private final Database database = mock(Database.class);
        private final BukkitScheduler scheduler =
                mock(BukkitScheduler.class);
        private final BukkitTask task = mock(BukkitTask.class);
        private final World world = mock(World.class);
        private final Player player = mock(Player.class);
        private final Map<BlockCoordinate, Block> blocks =
                new HashMap<>();
        private final List<Runnable> scheduledTasks =
                new ArrayList<>();
        private final MockedStatic<Bukkit> bukkit =
                mockStaticBukkit();
        private final BlockChangeListener listener =
                new BlockChangeListener(plugin, database);

        private BreakHarness() {
            when(world.getName()).thenReturn("world");
            when(player.getUniqueId()).thenReturn(PLAYER_UUID);
            when(player.getName()).thenReturn("Builder");
            when(world.getBlockAt(anyInt(), anyInt(), anyInt()))
                    .thenAnswer(invocation -> blocks.get(
                            new BlockCoordinate(
                                    invocation.getArgument(0),
                                    invocation.getArgument(1),
                                    invocation.getArgument(2)
                            )
                    ));
            when(scheduler.runTask(
                    eq(plugin),
                    any(Runnable.class)
            )).thenAnswer(invocation -> {
                scheduledTasks.add(invocation.getArgument(1));
                return task;
            });
        }

        private MockedStatic<Bukkit> mockStaticBukkit() {
            MockedStatic<Bukkit> mockedBukkit =
                    org.mockito.Mockito.mockStatic(Bukkit.class);
            mockedBukkit.when(Bukkit::getScheduler)
                    .thenReturn(scheduler);
            mockedBukkit.when(() -> Bukkit.getWorld("world"))
                    .thenReturn(world);
            return mockedBukkit;
        }

        private Neighborhood neighborhood(
                int x,
                int y,
                int z,
                String beforeData,
                String afterData,
                Map<BlockFace, StateChange> changedNeighbors
        ) {
            Block center = block(
                    x,
                    y,
                    z,
                    beforeData,
                    afterData
            );
            Map<BlockFace, Block> neighbors =
                    new EnumMap<>(BlockFace.class);

            for (BlockFace face : NEIGHBOR_FACES) {
                StateChange state = changedNeighbors.getOrDefault(
                        face,
                        UNCHANGED_AIR
                );
                Block neighbor = block(
                        x + face.getModX(),
                        y + face.getModY(),
                        z + face.getModZ(),
                        state.beforeData(),
                        state.afterData()
                );
                neighbors.put(face, neighbor);
                when(center.getRelative(face)).thenReturn(neighbor);
            }

            return new Neighborhood(center, neighbors);
        }

        private Block block(
                int x,
                int y,
                int z,
                String beforeData,
                String afterData
        ) {
            Block block = mock(Block.class);
            BlockData before = mock(BlockData.class);
            BlockData after = mock(BlockData.class);

            when(before.getAsString()).thenReturn(beforeData);
            when(after.getAsString()).thenReturn(afterData);
            when(block.getWorld()).thenReturn(world);
            when(block.getX()).thenReturn(x);
            when(block.getY()).thenReturn(y);
            when(block.getZ()).thenReturn(z);
            when(block.getBlockData()).thenReturn(before, after);
            blocks.put(new BlockCoordinate(x, y, z), block);
            return block;
        }

        private BlockBreakEvent breakEvent(Block block) {
            BlockBreakEvent event = mock(BlockBreakEvent.class);
            when(event.getPlayer()).thenReturn(player);
            when(event.getBlock()).thenReturn(block);
            return event;
        }

        private BlockPhysicsEvent physicsEvent(
                Block affectedBlock,
                Block sourceBlock
        ) {
            BlockPhysicsEvent event =
                    mock(BlockPhysicsEvent.class);
            when(event.getBlock()).thenReturn(affectedBlock);
            when(event.getSourceBlock()).thenReturn(sourceBlock);
            return event;
        }

        private void runNextTick() {
            assertEquals(1, scheduledTasks.size());
            scheduledTasks.removeFirst().run();
        }

        @Override
        public void close() {
            bukkit.close();
        }
    }

    private record Neighborhood(
            Block center,
            Map<BlockFace, Block> neighbors
    ) {
    }

    private record StateChange(
            String beforeData,
            String afterData
    ) {
    }

    private record BlockCoordinate(int x, int y, int z) {
    }

    private record PlacedPart(
            int x,
            int y,
            int z,
            String beforeData,
            String afterData
    ) {
    }
}
