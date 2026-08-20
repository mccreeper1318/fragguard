package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackStateGuardTest {
    @Test
    void skipsLiveChangesThatNoLongerMatchSnapshot() {
        assertEquals(RollbackStateGuard.Decision.CONFLICT,
                RollbackStateGuard.decide(
                        "minecraft:air",
                        "minecraft:stone",
                        "minecraft:dirt",
                        false));
    }

    @Test
    void permitsExpectedSnapshotStateAndRecognizesAlreadyRestoredState() {
        assertEquals(RollbackStateGuard.Decision.APPLY,
                RollbackStateGuard.decide(
                        "minecraft:stone",
                        "minecraft:stone",
                        "minecraft:dirt",
                        false));
        assertEquals(RollbackStateGuard.Decision.ALREADY_TARGET,
                RollbackStateGuard.decide(
                        "minecraft:dirt",
                        "minecraft:stone",
                        "minecraft:dirt",
                        false));
    }

    @Test
    void forceCanApplyAConflictButAuditMustStillBeRevalidated() {
        assertEquals(RollbackStateGuard.Decision.APPLY,
                RollbackStateGuard.decide(
                        "minecraft:air",
                        "minecraft:stone",
                        "minecraft:dirt",
                        true));
        assertTrue(RollbackStateGuard.stillMatchesAudit("minecraft:air", "minecraft:air"));
        assertFalse(RollbackStateGuard.stillMatchesAudit("minecraft:oak_log", "minecraft:air"));
    }
}
