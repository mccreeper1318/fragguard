package org.pinnaclesmp.fragguard;

import java.util.List;

final class LookupResultSnapshot {
    private static final LookupResultSnapshot EMPTY = new LookupResultSnapshot(List.of(), List.of());

    private final List<LookupRow> rows;
    private final List<LookupActivity> activities;

    private LookupResultSnapshot(List<LookupRow> rows, List<LookupActivity> activities) {
        this.rows = rows;
        this.activities = activities;
    }

    static LookupResultSnapshot empty() {
        return EMPTY;
    }

    static LookupResultSnapshot fromRows(List<LookupRow> rows, long maxGapMillis, int maxDistance) {
        return fromRows(rows, maxGapMillis, maxDistance,
                LookupActivityGrouper.DEFAULT_MAX_DURATION_MILLIS,
                LookupActivityGrouper.DEFAULT_MAX_SPAN);
    }

    static LookupResultSnapshot fromRows(List<LookupRow> rows, long maxGapMillis, int maxDistance,
                                         long maxDurationMillis, int maxSpan) {
        List<LookupRow> rowSnapshot = List.copyOf(rows);
        List<LookupActivity> activities = LookupActivityGrouper.group(
                rowSnapshot, maxGapMillis, maxDistance, maxDurationMillis, maxSpan);
        return new LookupResultSnapshot(rowSnapshot, List.copyOf(activities));
    }

    List<LookupRow> rows() {
        return rows;
    }

    List<LookupActivity> activities() {
        return activities;
    }
}
