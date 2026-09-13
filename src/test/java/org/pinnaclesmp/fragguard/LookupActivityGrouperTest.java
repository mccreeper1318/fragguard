package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void boundsTransitiveDistanceGrowthWithoutLosingRows() {
        List<LookupRow> rows = List.of(
                row(10_000, "Kevin", 0, 64, 20),
                row(9_900, "Kevin", 6, 64, 20),
                row(9_800, "Kevin", 12, 64, 20),
                row(9_700, "Kevin", 18, 64, 20),
                row(9_600, "Kevin", 24, 64, 20),
                row(9_500, "Kevin", 30, 64, 20));

        List<LookupActivity> activities = LookupActivityGrouper.group(rows, 2_500, 6, 15_000, 18);

        assertEquals(2, activities.size(),
                "local six-block adjacency must not allow an activity to grow beyond its total span bound");
        assertEquals(6, activities.stream().mapToInt(LookupActivity::eventCount).sum(),
                "splitting a heuristic activity must preserve every exact source row");
        assertTrue(activities.stream().allMatch(activity -> activity.maxX() - activity.minX() <= 18));
    }

    @Test
    void boundsContinuousActivityDurationWithoutLosingRows() {
        List<LookupRow> rows = List.of(
                row(10_000, "Kevin", 10, 64, 20),
                row(8_000, "Kevin", 10, 63, 20),
                row(6_000, "Kevin", 10, 62, 20),
                row(4_000, "Kevin", 10, 61, 20),
                row(2_000, "Kevin", 10, 60, 20));

        List<LookupActivity> activities = LookupActivityGrouper.group(rows, 2_500, 6, 5_000, 24);

        assertEquals(2, activities.size(),
                "small inter-event gaps must not allow one heuristic activity to continue indefinitely");
        assertEquals(5, activities.stream().mapToInt(LookupActivity::eventCount).sum(),
                "duration boundaries must not hide or discard exact source rows");
        assertTrue(activities.stream().allMatch(activity -> activity.newestAt() - activity.oldestAt() <= 5_000));
    }

    private LookupRow row(long happenedAt, String actor, int x, int y, int z) {
        return new LookupRow(happenedAt, actor, "world", x, y, z, ChangeAction.BREAK,
                "minecraft:scaffolding", "minecraft:air");
    }
}
