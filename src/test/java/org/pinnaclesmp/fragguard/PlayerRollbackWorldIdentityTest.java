package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerRollbackWorldIdentityTest {
    private static final UUID EXPECTED = UUID.fromString("99999999-2222-3333-4444-555555555555");
    private static final UUID REUSED = UUID.fromString("99999999-2222-3333-4444-666666666666");

    @Test
    void resolvesRenamedWorldByUuidInsteadOfReusedOldName() throws Exception {
        FragGuardCommand command = new FragGuardCommand(mock(FragGuardPlugin.class), mock(Database.class));
        World unrelated = mock(World.class);
        World actual = mock(World.class);
        when(unrelated.getUID()).thenReturn(REUSED);
        when(actual.getUID()).thenReturn(EXPECTED);
        when(actual.getName()).thenReturn("renamed_world");

        Method resolve = FragGuardCommand.class.getDeclaredMethod(
                "resolveChunkWorld", RollbackChunkPlan.ChunkKey.class);
        resolve.setAccessible(true);
        var key = new RollbackChunkPlan.ChunkKey("old_world", EXPECTED.toString(), 0, 0);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("old_world")).thenReturn(unrelated);
            bukkit.when(() -> Bukkit.getWorld(EXPECTED)).thenReturn(actual);
            assertSame(actual, resolve.invoke(command, key),
                    "world name reuse must never redirect a player rollback to an unrelated UUID");
            bukkit.when(() -> Bukkit.getWorld(EXPECTED)).thenReturn(null);
            assertNull(resolve.invoke(command, key),
                    "unloaded original world must not silently fall back to reused name");
        }
    }

    @Test
    void normalizedWorldNameRetainsOriginalStableUuidAndCause() {
        var change = new RollbackJobChange(0, "old_world", 3, 64, 4,
                "minecraft:stone", "minecraft:air", "minecraft:stone",
                false, false, false, false, null, null, null, null, null,
                null, false, EXPECTED.toString(), "tnt-entity", "Explosion: TNT",
                "target-player", "chain-2", "TNT_CHAIN");
        var refreshed = change.withWorldName("renamed_world");
        assertEquals("renamed_world", refreshed.worldName());
        assertEquals(EXPECTED.toString(), refreshed.worldUuid());
        assertEquals("tnt-entity", refreshed.physicalActorUuid());
        assertEquals("target-player", refreshed.causalPlayerUuid());
        assertEquals("TNT_CHAIN", refreshed.causalCauseType());
    }

    @Test
    void bothMutationPathsUseVerifiedWorldResolutionNotHistoricalName() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/org/pinnaclesmp/fragguard/FragGuardCommand.java"));
        assertFalse(source.contains("Bukkit.getWorld(change.worldName())"));
        assertEquals(2, source.split("queued.withWorldName\\(world.getName\\(\\)\\)", -1).length - 1,
                "both normal and forced mutation paths must propagate current world names into audits");
        int prepare = source.indexOf("private void applyPreparedBatch");
        int force = source.indexOf("private void retryForcedChangesSlice");
        assertTrue(prepare > 0 && force > prepare);
        assertTrue(source.substring(prepare, force).contains(
                "resolveChunkWorld(RollbackChunkPlan.ChunkKey.from(queued))"));
        assertTrue(source.substring(force).contains(
                "resolveChunkWorld(RollbackChunkPlan.ChunkKey.from(queued))"));
    }
}
