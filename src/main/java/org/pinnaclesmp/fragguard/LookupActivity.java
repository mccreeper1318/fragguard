package org.pinnaclesmp.fragguard;

import java.util.List;

record LookupActivity(
        String actorName,
        ChangeAction action,
        String materialKey,
        long newestAt,
        long oldestAt,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ,
        List<LookupRow> rows
) {
    LookupActivity {
        rows = List.copyOf(rows);
    }

    int eventCount() {
        return rows.size();
    }
}
