package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
