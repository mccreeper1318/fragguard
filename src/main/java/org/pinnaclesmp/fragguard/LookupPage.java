package org.pinnaclesmp.fragguard;

import java.util.List;

record LookupPage(
        List<LookupRow> rows,
        int page,
        int pageSize,
        int totalRows
) {
    int totalPages() {
        if (pageSize <= 0) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(totalRows / (double) pageSize));
    }
}
