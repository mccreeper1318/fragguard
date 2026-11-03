package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SelectivePlayerRollbackPlannerTest {
    private static final String STEVE = "11111111-2222-3333-4444-555555555555";
    private static final String ALEX = "11111111-2222-3333-4444-666666666666";
    private static final String AIR = "minecraft:air";
    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";
    private static final String GLASS = "minecraft:glass";

    private SelectivePlayerRollbackPlanner.Decision evaluate(
            SelectivePlayerRollbackPlanner.HistoryRow... rows) {
        var scanner = new SelectivePlayerRollbackPlanner.CoordinateScanner(STEVE, 8, 64, -9);
        for (var row : rows) {
            scanner.add(row);
        }
        return scanner.finish();
    }

    private SelectivePlayerRollbackPlanner.HistoryRow row(
            int id, String actor, String causal, String before, String after) {
        return row(id, actor, causal, before, after, null, null);
    }

    private SelectivePlayerRollbackPlanner.HistoryRow row(
            int id, String actor, String causal, String before, String after,
            byte[] beforeEntity, byte[] afterEntity) {
        return new SelectivePlayerRollbackPlanner.HistoryRow(id, actor, causal,
                "world", before, after, beforeEntity, afterEntity);
    }

    @Test
    void isolatedPlayerPlacementCanBeReversed() {
        var decision = evaluate(row(1, STEVE, null, AIR, STONE));
        assertEquals(SelectivePlayerRollbackPlanner.Status.REVERSIBLE, decision.status());
        assertEquals(AIR, decision.target().blockData());
        assertEquals(STONE, decision.target().expectedData());
        assertEquals(8, decision.target().x());
        assertEquals(-9, decision.target().z());
        assertFalse(decision.protectedOlderChanges());
    }

    @Test
    void consecutivePlayerEventsReturnToEarliestBeforeState() {
        var decision = evaluate(
                row(3, STEVE, null, DIRT, GLASS),
                row(2, STEVE, null, STONE, DIRT),
                row(1, STEVE, null, AIR, STONE));
        assertEquals(SelectivePlayerRollbackPlanner.Status.REVERSIBLE, decision.status());
        assertEquals(AIR, decision.target().blockData());
        assertEquals(GLASS, decision.target().expectedData());
    }

    @Test
    void laterOtherPlayerWorkBlocksUndoingEarlierTargetChange() {
        var decision = evaluate(
                row(2, ALEX, null, STONE, DIRT),
                row(1, STEVE, null, AIR, STONE));
        assertEquals(SelectivePlayerRollbackPlanner.Status.HISTORICAL_CONFLICT, decision.status());
        assertNull(decision.target());
        assertTrue(decision.protectedOlderChanges());
    }

    @Test
    void laterTargetAfterOtherPlayerRevertsOnlyToOwnershipBoundary() {
        var decision = evaluate(
                row(3, STEVE, null, DIRT, GLASS),
                row(2, ALEX, null, STONE, DIRT),
                row(1, STEVE, null, AIR, STONE));
        assertEquals(SelectivePlayerRollbackPlanner.Status.REVERSIBLE, decision.status());
        assertEquals(DIRT, decision.target().blockData());
        assertEquals(GLASS, decision.target().expectedData());
        assertTrue(decision.protectedOlderChanges());
    }

    @Test
    void alternatingPlayerAndEnvironmentalEventsProtectOlderChanges() {
        var decision = evaluate(
                row(5, STEVE, null, STONE, GLASS),
                row(4, STEVE, null, DIRT, STONE),
                row(3, "SYSTEM", null, AIR, DIRT),
                row(2, ALEX, null, STONE, AIR),
                row(1, STEVE, null, AIR, STONE));
        assertEquals(SelectivePlayerRollbackPlanner.Status.REVERSIBLE, decision.status());
        assertEquals(DIRT, decision.target().blockData());
        assertTrue(decision.protectedOlderChanges());
    }

    @Test
    void directAndCausalTntChainsAreSelectedByPlayerUuidOnly() {
        // Directly ignited TNT, TNT->TNT and projectile-triggered TNT are
        // all physical entity actions with the target's causal UUID.
        var decision = evaluate(
                row(4, "tnt-entity-c", STEVE, DIRT, GLASS),
                row(3, "tnt-entity-b", STEVE, STONE, DIRT),
                row(2, "tnt-entity-a", STEVE, AIR, STONE));
        assertEquals(SelectivePlayerRollbackPlanner.Status.REVERSIBLE, decision.status());
        assertEquals(AIR, decision.target().blockData());
        assertEquals(GLASS, decision.target().expectedData());

        var takeover = evaluate(
                row(4, "tnt-entity-alex", ALEX, DIRT, GLASS),
                row(3, "tnt-entity-steve", STEVE, STONE, DIRT));
        assertEquals(SelectivePlayerRollbackPlanner.Status.HISTORICAL_CONFLICT,
                takeover.status());
    }

    @Test
    void flyingMachineAndPistonConsequencesRespectTransferAndExpiry() {
        var attributedMachine = evaluate(
                row(4, "SYSTEM", STEVE, DIRT, GLASS),
                row(3, "SYSTEM", STEVE, STONE, DIRT),
                row(2, "SYSTEM", ALEX, AIR, STONE),
                row(1, "SYSTEM", STEVE, STONE, AIR));
        assertEquals(SelectivePlayerRollbackPlanner.Status.REVERSIBLE, attributedMachine.status());
        assertEquals(STONE, attributedMachine.target().blockData());
        assertTrue(attributedMachine.protectedOlderChanges());

        var expiredAttribution = evaluate(
                row(4, "SYSTEM", null, DIRT, GLASS),
                row(3, "SYSTEM", STEVE, STONE, DIRT));
        assertEquals(SelectivePlayerRollbackPlanner.Status.HISTORICAL_CONFLICT,
                expiredAttribution.status());
    }

    @Test
    void noOpSuffixDoesNotCreateTarget() {
        var decision = evaluate(
                row(2, STEVE, null, STONE, AIR),
                row(1, STEVE, null, AIR, STONE));
        assertEquals(SelectivePlayerRollbackPlanner.Status.NO_OP, decision.status());
        assertNull(decision.target());
    }

    @Test
    void missingOrMismatchedIntermediateHistoryFailsClosed() {
        var disconnected = evaluate(
                row(3, STEVE, null, DIRT, GLASS),
                row(1, STEVE, null, AIR, STONE));
        assertEquals(SelectivePlayerRollbackPlanner.Status.UNAVAILABLE, disconnected.status());
        assertNull(disconnected.target());
    }

    @Test
    void containerPayloadsAreRestoredOnlyAcrossContinuousPlayerOwnedEvents() {
        byte[] original = {1, 2, 3};
        byte[] middle = {4, 5};
        byte[] last = {6, 7};
        var owned = evaluate(
                row(3, STEVE, null, "minecraft:chest", "minecraft:chest",
                        middle, last),
                row(2, "tnt-entity", STEVE, "minecraft:chest", "minecraft:chest",
                        original, middle.clone()));
        assertEquals(SelectivePlayerRollbackPlanner.Status.REVERSIBLE, owned.status());
        assertArrayEquals(original, owned.target().targetEntityData());
        assertArrayEquals(last, owned.target().expectedEntityData());

        var boundary = evaluate(
                row(4, STEVE, null, "minecraft:chest", "minecraft:chest",
                        middle, last),
                row(3, ALEX, null, "minecraft:chest", "minecraft:chest",
                        original, middle),
                row(2, STEVE, null, AIR, "minecraft:chest",
                        null, original));
        assertEquals(SelectivePlayerRollbackPlanner.Status.REVERSIBLE, boundary.status());
        assertArrayEquals(middle, boundary.target().targetEntityData());
        assertTrue(boundary.protectedOlderChanges());
        assertFalse(Arrays.equals(original, boundary.target().targetEntityData()));
    }

    @Test
    void missingLegacyContainerAndSignSnapshotsAreUnavailable() {
        for (String block : List.of("minecraft:chest", "minecraft:oak_sign",
                "minecraft:lectern", "minecraft:barrel",
                "minecraft:shulker_box", "minecraft:blue_shulker_box",
                "minecraft:oak_shelf", "minecraft:bamboo_shelf")) {
            var decision = evaluate(row(1, STEVE, null, block, AIR, null, null));
            assertEquals(SelectivePlayerRollbackPlanner.Status.UNAVAILABLE, decision.status(),
                    "missing historical entity data must never be treated as empty: " + block);
        }
    }

    @Test
    void intermediateMissingContainerSnapshotsInvalidateOtherwiseSafeEndpoints() {
        var missingChest = evaluate(
                row(2, STEVE, null, "minecraft:chest", DIRT, null, null),
                row(1, STEVE, null, STONE, "minecraft:chest", null, null));
        assertEquals(SelectivePlayerRollbackPlanner.Status.UNAVAILABLE, missingChest.status(),
                "intermediate chest inventory cannot be inferred from two ordinary endpoints");
        assertNull(missingChest.target());

        var missingShelf = evaluate(
                row(2, "piston", STEVE, "minecraft:oak_shelf", DIRT, null, null),
                row(1, STEVE, null, AIR, "minecraft:oak_shelf", null, null));
        assertEquals(SelectivePlayerRollbackPlanner.Status.UNAVAILABLE, missingShelf.status(),
                "causally attributed intermediate shelf contents must remain protected");
    }

    @Test
    void intermediateFullyCapturedContainerCanStillBeReverted() {
        byte[] chest = {4, 5, 6};
        var decision = evaluate(
                row(2, STEVE, null, "minecraft:chest", DIRT, chest, null),
                row(1, STEVE, null, STONE, "minecraft:chest", null, chest.clone()));
        assertEquals(SelectivePlayerRollbackPlanner.Status.REVERSIBLE, decision.status());
        assertEquals(STONE, decision.target().blockData());
        assertEquals(DIRT, decision.target().expectedData());
    }

    @Test
    void historyScanIsBoundedWithoutReturningPartialRollbackTargets() {
        var scanner = new SelectivePlayerRollbackPlanner.CoordinateScanner(STEVE, 0, 64, 0);
        for (int i = 0; i < SelectivePlayerRollbackPlanner.MAX_HISTORY_ROWS_PER_COORDINATE; i++) {
            scanner.add(row(i, STEVE, null, AIR, AIR));
        }
        assertThrows(SelectivePlayerRollbackPlanner.HistoryLimitExceededException.class,
                () -> scanner.add(row(9000, STEVE, null, AIR, STONE)));
    }
}
