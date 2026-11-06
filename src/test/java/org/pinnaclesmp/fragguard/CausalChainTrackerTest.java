package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CausalChainTrackerTest {
    private static final String WORLD = "world";
    private static final String FIRST = "11111111-1111-1111-1111-111111111111";
    private static final String SECOND = "22222222-2222-2222-2222-222222222222";

    private static CausalChainTracker.Position at(int x, int y, int z) {
        return new CausalChainTracker.Position(WORLD, x, y, z);
    }

    @Test
    void directTntPrimeAndExplosionKeepOriginChainAndPhysicalTypeSeparately() {
        var tracker = new CausalChainTracker();
        var player = new CausalAttribution(FIRST, "Builder", "chain-1", "DIRECT_TNT");
        var primed = tracker.prime(at(0,64,0), null, null, player, 1000);
        assertEquals(FIRST, primed.playerUuid());
        UUID tnt = UUID.randomUUID();
        assertEquals(FIRST, tracker.spawnedTnt(at(0,64,0), tnt, 1010).playerUuid());
        var blast = tracker.entityCause(tnt, 1020);
        assertEquals(primed.chainId(), blast.chainId());
        assertEquals("TNT_ENTITY", blast.causeType());
    }

    @Test
    void observedTntExplosionChainPropagatesToBAndCButNotUnrelatedTnt() {
        var t = new CausalChainTracker();
        var origin = new CausalAttribution(FIRST, "Builder", "chain-1", "PLAYER_PROJECTILE");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        t.prime(at(0,64,0), null, null, origin, 1000);
        t.spawnedTnt(at(0,64,0), a, 1001);
        var bPrime = t.prime(at(1,64,0), null, a, null, 1010);
        t.spawnedTnt(at(1,64,0), b, 1011);
        var cPrime = t.prime(at(2,64,0), null, b, null, 1020);
        t.spawnedTnt(at(2,64,0), c, 1021);
        assertEquals("chain-1", bPrime.chainId());
        assertEquals("chain-1", cPrime.chainId());
        assertEquals(FIRST, t.entityCause(c, 1030).playerUuid());
        assertNull(t.entityCause(UUID.randomUUID(), 1030));
        assertNull(t.prime(at(10,64,10), null, null, null, 1030));
    }

    @Test
    void shooterToTntKeepsShooterIdentityThroughSpawn() {
        var t = new CausalChainTracker();
        var shot = new CausalAttribution(FIRST, "Builder", "projectile", "PROJECTILE");
        t.prime(at(7,64,7), null, UUID.randomUUID(), shot, 1000);
        UUID spawned = UUID.randomUUID();
        assertEquals(FIRST, t.spawnedTnt(at(7,64,7), spawned, 1050).playerUuid());
        assertEquals("projectile", t.entityCause(spawned, 1100).chainId());
    }

    @Test
    void connectedMovingPistonMachineAttributesRepeatedTntButNotUnrelatedAreaEvents() {
        var t = new CausalChainTracker();
        t.playerActivated(at(0,64,0), FIRST, "Builder", 1000);
        var pushed = t.piston(at(1,64,0), List.of(at(2,64,0), at(3,64,0)),
                1, 0, 0, 1100);
        assertEquals(FIRST, pushed.playerUuid());
        var further = t.piston(at(4,64,0), List.of(at(5,64,0)), List.of(at(5,64,0)),
                1, 0, 0, 1200);
        assertEquals(pushed.chainId(), further.chainId());
        // A TNT duper spawning at an observed piston-moved coordinate.
        UUID spawned = UUID.randomUUID();
        assertEquals(FIRST, t.spawnedTnt(at(6,64,0), spawned, 1210).playerUuid());
        assertEquals(FIRST, t.entityCause(spawned, 1300).playerUuid());
        assertNull(t.piston(at(40,64,0), List.of(at(41,64,0)), 1, 0, 0, 1400));
        assertNull(t.spawnedTnt(at(40,64,0), UUID.randomUUID(), 1400));
    }

    @Test
    void anotherPlayerTakeoverRemovesOldMachineAndTntOwnership() {
        var t = new CausalChainTracker();
        t.playerActivated(at(0,64,0), FIRST, "First", 1000);
        t.piston(at(1,64,0), List.of(at(2,64,0)), List.of(at(2,64,0)),
                1, 0, 0, 1100);
        UUID tnt = UUID.randomUUID();
        t.spawnedTnt(at(3,64,0), tnt, 1150);
        assertNotNull(t.entityCause(tnt, 1160));
        t.playerActivated(at(1,64,0), SECOND, "Second", 1170);
        assertNull(t.entityCause(tnt, 1180));
        assertEquals(SECOND, t.piston(at(2,64,0), List.of(at(3,64,0)),
                1, 0, 0, 1200).playerUuid());
    }

    @Test
    void ordinaryPistonMovementNeverClaimsUnrelatedDispenserTnt() {
        var t = new CausalChainTracker();
        t.playerActivated(at(0,64,0), FIRST, "Builder", 1_000);
        assertNotNull(t.piston(at(1,64,0), List.of(at(2,64,0)), 1, 0, 0, 1_100));
        // Before the fix, either source or destination could spuriously claim
        // a separate dispenser's TNT entity for four seconds.
        UUID unrelated = UUID.randomUUID();
        assertNull(t.spawnedTnt(at(3,64,0), unrelated, 1_110));
        assertNull(t.spawnedTnt(at(2,64,0), UUID.randomUUID(), 1_120));
        assertNull(t.entityCause(unrelated, 1_200));
        assertNull(t.spawnedTnt(at(1,64,0), UUID.randomUUID(), 1_130),
                "touching the piston itself is not TNT-specific evidence");
    }

    @Test
    void observedTntBlockMovementCanClaimOnlyItsSourceOrDestinationBriefly() {
        var t = new CausalChainTracker();
        t.playerActivated(at(0,64,0), FIRST, "Builder", 1_000);
        t.piston(at(1,64,0), List.of(at(2,64,0), at(3,64,0)),
                List.of(at(3,64,0)), 1, 0, 0, 1_100);
        assertNull(t.spawnedTnt(at(2,64,0), UUID.randomUUID(), 1_110),
                "moving an adjacent ordinary block does not prove TNT ownership");
        UUID spawned = UUID.randomUUID();
        assertEquals(FIRST, t.spawnedTnt(at(4,64,0), spawned, 1_120).playerUuid());
        assertEquals(FIRST, t.entityCause(spawned, 1_500).playerUuid());
        assertNull(t.spawnedTnt(at(4,64,0), UUID.randomUUID(), 1_130),
                "a movement handoff is one-shot, not permanent ownership");
        assertEquals(FIRST, t.spawnedTnt(at(3,64,0), UUID.randomUUID(), 1_140).playerUuid(),
                "TNT moved from its original coordinate is also direct evidence");
    }

    @Test
    void tntMovementEvidenceExpiresAndRequiresActualMovedTnt() {
        var t = new CausalChainTracker();
        t.playerActivated(at(0,64,0), FIRST, "Builder", 1_000);
        t.piston(at(1,64,0), List.of(at(2,64,0)), List.of(at(2,64,0)),
                1, 0, 0, 1_100);
        assertNull(t.spawnedTnt(at(3,64,0), UUID.randomUUID(),
                        1_100 + CausalChainTracker.TNT_MOVEMENT_MATCH_MILLIS + 1),
                "unrelated TNT spawned later must not inherit piston ownership");

        // A caller cannot seed evidence at a point not in the moved block set.
        t.piston(at(1,64,0), List.of(at(2,64,0)), List.of(at(20,64,0)),
                1, 0, 0, 2_000);
        assertNull(t.spawnedTnt(at(20,64,0), UUID.randomUUID(), 2_010));
    }

    @Test
    void newPlayerActivationInvalidatesPendingTntMovementEvidence() {
        var t = new CausalChainTracker();
        t.playerActivated(at(0,64,0), FIRST, "First", 1_000);
        t.piston(at(1,64,0), List.of(at(2,64,0)), List.of(at(2,64,0)),
                1, 0, 0, 1_100);
        t.playerActivated(at(1,64,0), SECOND, "Second", 1_120);
        assertNull(t.spawnedTnt(at(3,64,0), UUID.randomUUID(), 1_130),
                "invalidating the original machine removes its unconsumed TNT evidence");
    }

    @Test
    void disconnectedOrAmbiguousMechanismNeverGuessesPlayer() {
        var t = new CausalChainTracker();
        assertNull(t.piston(at(10,64,10), List.of(), 1, 0, 0, 100));
        t.playerActivated(at(0,64,0), FIRST, "One", 1000);
        t.playerActivated(at(2,64,0), SECOND, "Two", 1000);
        assertNull(t.piston(at(1,64,0), List.of(), 1, 0, 0, 1100));
        assertNull(t.prime(at(50,64,0), at(50,64,1), null, null, 1100));
    }

    @Test
    void expiryWorldUnloadRestartAndBoundedMemoryFailClosed() {
        var t = new CausalChainTracker();
        t.playerActivated(at(0,64,0), FIRST, "One", 1000);
        assertNull(t.piston(at(1,64,0), List.of(), 1, 0, 0,
                1000 + CausalChainTracker.IDLE_MILLIS + 1));
        t.playerActivated(at(0,64,0), FIRST, "One", 10000);
        t.unloadWorld(WORLD);
        assertEquals(0, t.trackedCount());
        t.playerActivated(at(0,64,0), FIRST, "One", 20000);
        t.clear();
        assertNull(t.piston(at(1,64,0), List.of(), 1, 0, 0, 20100));
        for(int i=0;i<5000;i++) {
            t.playerActivated(at(i,64,0), FIRST, "One", 30000);
        }
        assertTrue(t.trackedCount() <= CausalChainTracker.MAX_BLOCK_LINKS);
    }

    @Test
    void observedTntOutlivesFourSecondMachineIdleAndCanCauseSlowFuseChain() {
        var t = new CausalChainTracker();
        var player = new CausalAttribution(FIRST, "One", "long-fuse", "DIRECT_TNT");
        t.prime(at(0, 64, 0), null, null, player, 1000);
        UUID first = UUID.randomUUID();
        assertNotNull(t.spawnedTnt(at(0, 64, 0), first, 1010));
        assertNotNull(t.entityCause(first, 20_000),
                "an observed TNT fuse can safely outlive the mechanism's 4s idle limit");
        UUID next = UUID.randomUUID();
        assertEquals("long-fuse", t.prime(at(1, 64, 0), null, first, null, 40_000).chainId());
        assertEquals("long-fuse", t.spawnedTnt(at(1, 64, 0), next, 40_100).chainId());
        assertEquals("long-fuse", t.entityCause(next, 65_000).chainId());
        assertNull(t.entityCause(first, 62_000), "even observed TNT is not tracked indefinitely");
        assertNull(t.entityCause(next, 101_000));
    }

    @Test
    void unrelatedLaterFireOrDistantMechanismHasNoOwnership() {
        var t = new CausalChainTracker();
        t.playerActivated(at(0,64,0), FIRST, "One", 1000);
        // No fire propagation API: a fire tile is never linked merely by proximity.
        assertNull(t.entityCause(UUID.randomUUID(), 1200));
        assertNull(t.prime(at(1,64,0), null, null, null, 1200));
        assertNull(t.piston(at(100,64,0), List.of(), 1, 0, 0, 1300));
    }
}
