package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LookupActivityGrouperTest {
    @Test
    void groupsAdjacentScaffoldingBreaksWithoutLosingRows() {
        List<LookupRow> rows = List.of(
                row(10_000, "Kevin", 10, 64, 20),
                row(9_900, "Kevin", 10, 63, 20),
                row(9_800, "Kevin", 10, 62, 20),
                row(9_700, "Kevin", 10, 61, 20));

        List<LookupActivity> activities = LookupActivityGrouper.group(rows, 2_500, 6);

        assertEquals(1, activities.size());
        assertEquals(4, activities.getFirst().eventCount());
        assertEquals(4, activities.stream().mapToInt(LookupActivity::eventCount).sum());
        assertEquals("minecraft:scaffolding", activities.getFirst().materialKey());
    }

    @Test
    void separatesDifferentActors() {
        List<LookupRow> rows = List.of(
                row(10_000, "Kevin", 10, 64, 20),
                row(9_900, "Steve", 10, 63, 20));

        List<LookupActivity> activities = LookupActivityGrouper.group(rows, 2_500, 6);

        assertEquals(2, activities.size());
        assertEquals(2, activities.stream().mapToInt(LookupActivity::eventCount).sum());
    }

    @Test
    void separatesEventsOutsideTimeWindow() {
        List<LookupRow> rows = List.of(
                row(10_000, "Kevin", 10, 64, 20),
                row(7_000, "Kevin", 10, 63, 20));

        assertEquals(2, LookupActivityGrouper.group(rows, 2_500, 6).size());
    }

    @Test
    void separatesEventsOutsideSpatialWindow() {
        List<LookupRow> rows = List.of(
                row(10_000, "Kevin", 10, 64, 20),
                row(9_900, "Kevin", 30, 64, 20));

        assertEquals(2, LookupActivityGrouper.group(rows, 2_500, 6).size());
    }

    @Test
    void separatesDifferentActionsEvenAtSameCoordinate() {
        LookupRow broken = row(10_000, "Kevin", 10, 64, 20);
        LookupRow placed = new LookupRow(9_900, "Kevin", "world", 10, 64, 20,
                ChangeAction.PLACE, "minecraft:air", "minecraft:scaffolding");

        List<LookupActivity> activities = LookupActivityGrouper.group(List.of(broken, placed), 2_500, 6);

        assertEquals(2, activities.size());
        assertEquals(2, activities.stream().mapToInt(LookupActivity::eventCount).sum());
    }

    private LookupRow row(long happenedAt, String actor, int x, int y, int z) {
        return new LookupRow(happenedAt, actor, "world", x, y, z, ChangeAction.BREAK,
                "minecraft:scaffolding", "minecraft:air");
    }
}
