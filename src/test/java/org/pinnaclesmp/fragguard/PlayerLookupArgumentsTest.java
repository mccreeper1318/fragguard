package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerLookupArgumentsTest {
    @Test
    void acceptsPlayerUuidWorldTimeAndPageInAnyOrder() {
        var args = PlayerLookupArguments.parse(
                new String[]{"p:2", "w:world_nether", "u:OldBuilder", "t:1d", "6h"}, 30);
        assertEquals("OldBuilder", args.playerArgument());
        assertEquals("world_nether", args.worldName());
        assertEquals(2, args.page());
        assertEquals(30L * 3_600_000L, args.durationMillis());
        assertEquals("/fg lookup u:OldBuilder w:world_nether t:1d 6h p:3",
                args.nextPageCommand(3));
        assertTrue(PlayerLookupArguments.isPlayerScope(new String[]{"r:15", "u:Builder"}));
    }

    @Test
    void noTimeMeansEntireRetainedHistoryAndDoesNotSetWorld() {
        var args = PlayerLookupArguments.parse(
                new String[]{"u:11111111-2222-3333-4444-555555555555", "p:20"}, 30);
        assertNull(args.worldName());
        assertEquals(0L, args.durationMillis());
        assertEquals("/fg lookup u:11111111-2222-3333-4444-555555555555 p:21",
                args.nextPageCommand(21));
        assertFalse(PlayerLookupArguments.isPlayerScope(new String[]{"r:30", "p:2"}));
    }

    @Test
    void rejectsMixedScopeAndInvalidOrDuplicatePlayerOptions() {
        for (String[] args : new String[][]{
                {"u:Builder", "r:30"}, {"r:30", "u:Builder"},
                {"u:"}, {"u:Builder", "u:Other"}, {"u:Builder", "w:"},
                {"u:Builder", "w:world", "w:world_nether"},
                {"u:Builder", "p:0"}, {"u:Builder", "p:two"},
                {"u:Builder", "p:2", "p:3"}, {"u:Builder", "t:"},
                {"u:Builder", "t:3d", "t:1d"}, {"u:Builder", "t:foo"},
                {"u:Builder", "t:2d", "unknown"}, {"u:Builder", "force"},
                {"u:Builder", "t:9999999999999999999d"}
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> PlayerLookupArguments.parse(args, 30),
                    "must reject invalid player syntax: " + String.join(" ", args));
        }
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> PlayerLookupArguments.parse(new String[]{"u:Builder", "r:30"}, 30))
                .getMessage().contains("either"));
    }

    @Test
    void playerTimeCannotExceedRetention() {
        assertThrows(DurationParser.DurationLimitExceededException.class,
                () -> PlayerLookupArguments.parse(new String[]{"u:Builder", "t:2d"}, 1));
        assertEquals(24L * 3_600_000L,
                PlayerLookupArguments.parse(new String[]{"u:Builder", "t:1d"}, 1)
                        .durationMillis());
    }
}
