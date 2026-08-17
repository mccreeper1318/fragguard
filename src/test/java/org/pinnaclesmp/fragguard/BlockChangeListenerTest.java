package org.pinnaclesmp.fragguard;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private record PlacedPart(
            int x,
            int y,
            int z,
            String beforeData,
            String afterData
    ) {
    }
}
