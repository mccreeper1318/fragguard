package org.pinnaclesmp.fragguard;

import java.util.List;

record PlayerLookupPage(
        HistoricalPlayerResolver.HistoricalPlayerIdentity player,
        List<PlayerLookupRow> rows,
        int page,
        int pageSize,
        int totalRows
) {
    PlayerLookupPage {
        rows = List.copyOf(rows);
    }

    int totalPages() {
        return Math.max(1, (int) Math.ceil(totalRows / (double) pageSize));
    }
}
