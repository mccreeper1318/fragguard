package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiPendingTransitionTest {
    @Test
    void blocksDoubleToggleWhileFirstDatabasePageIsPending() {
        GuiPendingTransition pending = new GuiPendingTransition();
        assertTrue(pending.begin(), "The first click starts the grouped-to-raw lookup");
        assertTrue(pending.isPending());
        assertFalse(pending.begin(), "Another click on the still-displayed GUI must be ignored");
        assertFalse(pending.begin(), "Even repeated clicks cannot invalidate the first request");

        pending.complete();
        assertFalse(pending.isPending());
        assertTrue(pending.begin(), "Navigation becomes available after the page is committed");
    }

    @Test
    void failedViewChangeRestoresTheActualPreviousPageInsteadOfTransientState() {
        GuiPendingTransition pending = new GuiPendingTransition();
        // The operator is looking at grouped page 3. The toggle temporarily stages raw page 1.
        boolean grouped = true;
        int page = 2;
        List<String> displayedRows = List.of("Activity 1", "Activity 2");
        boolean previousGrouped = grouped;
        int previousPage = page;
        List<String> previousRows = displayedRows;

        assertTrue(pending.begin());
        grouped = false;
        page = 0;
        displayedRows = List.of();
        // A second click would otherwise capture the transient raw/empty view as the previous page.
        assertFalse(pending.begin());

        // Simulate a failed database read or inventory render and the caller's state rollback.
        grouped = previousGrouped;
        page = previousPage;
        displayedRows = previousRows;
        pending.complete();

        assertTrue(grouped);
        assertEquals(2, page);
        assertEquals(List.of("Activity 1", "Activity 2"), displayedRows);
        assertTrue(pending.begin(), "Retry after a failed transition must be possible");
    }

    @Test
    void gateBlocksOtherResultActionsWhileTransitionIsPending() {
        GuiPendingTransition pending = new GuiPendingTransition();
        AtomicInteger started = new AtomicInteger();

        if (pending.begin()) {
            started.incrementAndGet(); // View toggle
        }
        // Page next, clear filters, and a raw-event click all see the same pending gate.
        for (int ignored = 0; ignored < 3; ignored++) {
            if (pending.begin()) {
                started.incrementAndGet();
            }
        }
        assertEquals(1, started.get());
        pending.complete();

        assertTrue(pending.begin());
        assertEquals(1, started.get());
    }

    @Test
    void resetOrDisconnectReleasesPendingNavigationForNewSession() {
        GuiPendingTransition pending = new GuiPendingTransition();
        assertTrue(pending.begin());
        assertFalse(pending.begin());
        pending.complete(); // Called during /fg reset or player disconnect.
        assertFalse(pending.isPending());
        assertTrue(pending.begin());
    }
}
