package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlayerRollbackArgumentsTest {
    @Test
    void requiresPlayerAndExplicitTimeAndAcceptsOptionalWorldAndForce() {
        var scope = PlayerRollbackArguments.parse(
                new String[]{"u:OldName", "t:1d", "6h", "w:world_nether", "force"}, 30);
        assertEquals("OldName", scope.playerArgument());
        assertEquals("world_nether", scope.worldName());
        assertEquals(30L * 3_600_000L, scope.durationMillis());
        assertTrue(scope.force());
        assertNull(PlayerRollbackArguments.parse(new String[]{"t:1h", "u:Builder"}, 30).worldName());
        assertTrue(PlayerRollbackArguments.isPlayerScope(new String[]{"r:25", "u:Builder"}));
    }

    @Test
    void rejectsRadiusMixingAndMissingTimeWithoutChangingBlocks() {
        for (String[] args : new String[][]{
                {"u:Builder"}, {"u:Builder", "t:"}, {"u:Builder", "r:30", "t:1h"},
                {"r:30", "u:Builder", "t:1h"}, {"u:Builder", "u:Other", "t:1h"},
                {"u:Builder", "w:", "t:1h"}, {"u:Builder", "w:world", "w:other", "t:1h"},
                {"u:Builder", "t:1h", "t:2h"}, {"u:Builder", "t:2q"},
                {"u:Builder", "p:2", "t:1h"}
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> PlayerRollbackArguments.parse(args, 30), String.join(" ", args));
        }
    }

    @Test
    void respectsRetentionLimit() {
        assertThrows(DurationParser.DurationLimitExceededException.class, () ->
                PlayerRollbackArguments.parse(new String[]{"u:Builder", "t:2d"}, 1));
    }

    @Test
    void chunkReservationsAreWorldScopedAndIncludeNegativeChunks() {
        String world = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        String nether = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        var selected = RollbackJobChunk.fromTargets(List.of(
                target("world", world, -1, -1),
                target("nether", nether, -1, -1),
                target("world", world, -2, -2)
        ));
        assertEquals(2, selected.size());
        assertTrue(selected.contains(new RollbackJobChunk(world, -1, -1)));
        assertTrue(selected.contains(new RollbackJobChunk(nether, -1, -1)));
        Set<RollbackJobChunk> radius = RollbackJobChunk.radius(world, 0, 0, 16);
        assertTrue(radius.contains(new RollbackJobChunk(world, -1, -1)));
        assertTrue(radius.contains(new RollbackJobChunk(world, 1, 1)));
        assertFalse(radius.contains(new RollbackJobChunk(nether, -1, -1)));
    }

    private RollbackTarget target(String name, String uuid, int x, int z) {
        return new RollbackTarget(name, x, 64, z, "minecraft:air",
                "minecraft:stone", null, null, uuid, null, null, null, null, null);
    }
}
