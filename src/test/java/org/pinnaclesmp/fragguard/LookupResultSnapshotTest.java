package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LookupResultSnapshotTest {
    @Test
    void cachesActivitiesAgainstAnImmutableCopyOfTheSourceRows() {
        List<LookupRow> source = new ArrayList<>(List.of(
                row(3_000L, "Builder", 0, 64, 0),
                row(2_000L, "Builder", 1, 64, 0),
                row(1_000L, "OtherBuilder", 2, 64, 0)));

        LookupResultSnapshot snapshot = LookupResultSnapshot.fromRows(source, 2_500L, 6);
        List<LookupActivity> cachedActivities = snapshot.activities();

        assertEquals(3, snapshot.rows().size());
        assertEquals(2, cachedActivities.size());
        assertEquals(3, cachedActivities.stream().mapToInt(LookupActivity::eventCount).sum());
        assertSame(cachedActivities, snapshot.activities(),
                "paging and view toggles should reuse the same cached activity list");

        source.clear();
        assertEquals(3, snapshot.rows().size(),
                "later mutations of the source list must not invalidate the cached GUI snapshot");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.rows().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.activities().clear());
    }

    @Test
    void emptySnapshotReusesStableEmptyLists() {
        LookupResultSnapshot snapshot = LookupResultSnapshot.empty();
        assertEquals(List.of(), snapshot.rows());
        assertEquals(List.of(), snapshot.activities());
        assertSame(snapshot.rows(), LookupResultSnapshot.empty().rows());
        assertSame(snapshot.activities(), LookupResultSnapshot.empty().activities());
    }

    private LookupRow row(long happenedAt, String actor, int x, int y, int z) {
        return new LookupRow(happenedAt, actor, "world", x, y, z, ChangeAction.BREAK,
                "minecraft:scaffolding", "minecraft:air");
    }
}
