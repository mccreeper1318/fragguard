package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiInventoryTransitionTest {
    @Test
    void firstPageRenderingFailureLeavesLookupSetupAvailableForRetry() {
        AtomicReference<String> shown = new AtomicReference<>("Lookup setup");
        AtomicInteger failureReports = new AtomicInteger();
        boolean opened = GuiInventoryTransition.attempt(
                () -> { throw new IllegalArgumentException("Invalid display metadata"); },
                shown::set, ignored -> failureReports.incrementAndGet());

        assertFalse(opened);
        assertEquals("Lookup setup", shown.get());
        assertEquals(1, failureReports.get(), "An exception must be reported once, not retried in a loop");
    }

    @Test
    void thirdPageRenderingFailureDoesNotAdvanceVisiblePageOrLosePageTwo() {
        AtomicInteger page = new AtomicInteger(2);
        AtomicReference<String> shown = new AtomicReference<>("Page 2");
        AtomicInteger errors = new AtomicInteger();

        boolean opened = GuiInventoryTransition.attempt(
                () -> { throw new IllegalStateException("Bad entry on page 3"); },
                inventory -> {
                    shown.set(inventory);
                    page.set(3);
                }, ignored -> errors.incrementAndGet());

        assertFalse(opened);
        assertEquals(2, page.get());
        assertEquals("Page 2", shown.get());
        assertEquals(1, errors.get());

        assertTrue(GuiInventoryTransition.attempt(
                () -> "Page 3", inventory -> {
                    shown.set(inventory);
                    page.set(3);
                }, ignored -> errors.incrementAndGet()));
        assertEquals(3, page.get());
        assertEquals("Page 3", shown.get());
        assertEquals(1, errors.get());
    }

    @Test
    void inventoryOpenFailureDoesNotCommitAndInvokesRecoveryExactlyOnce() {
        AtomicInteger committedPage = new AtomicInteger(1);
        AtomicInteger errors = new AtomicInteger();
        AtomicReference<String> shown = new AtomicReference<>("Page 1");

        assertFalse(GuiInventoryTransition.attempt(
                () -> "Page 2",
                inventory -> { throw new IllegalArgumentException("Cannot open inventory"); },
                ignored -> errors.incrementAndGet()));
        assertEquals(1, committedPage.get());
        assertEquals("Page 1", shown.get());
        assertEquals(1, errors.get());

        assertTrue(GuiInventoryTransition.attempt(
                () -> "Page 2", inventory -> {
                    shown.set(inventory);
                    committedPage.set(2);
                }, ignored -> errors.incrementAndGet()));
        assertEquals(2, committedPage.get());
        assertEquals("Page 2", shown.get());
    }

    @Test
    void rawAndGroupedRenderersCanFailIndependentlyWithoutCorruptingPreviousView() {
        for (String view : new String[]{"grouped", "raw"}) {
            AtomicReference<String> displayed = new AtomicReference<>("Prior " + view + " page");
            AtomicInteger count = new AtomicInteger();

            boolean opened = GuiInventoryTransition.attempt(
                    () -> { throw new IllegalStateException(view + " renderer failed"); },
                    displayed::set, failure -> count.incrementAndGet());

            assertFalse(opened);
            assertEquals("Prior " + view + " page", displayed.get());
            assertEquals(1, count.get());
        }
    }
}
