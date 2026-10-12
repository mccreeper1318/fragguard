package org.pinnaclesmp.fragguard;

/**
 * Prevents clicks on an unchanged inventory from starting another asynchronous
 * navigation while the previous request has not committed its replacement view.
 *
 * <p>Used from the server thread only. A failed request releases the guard
 * without changing the last displayed page; invalidation releases it when
 * an operator reopens the menu or disconnects.</p>
 */
final class GuiPendingTransition {
    private boolean pending;

    boolean begin() {
        if (pending) {
            return false;
        }
        pending = true;
        return true;
    }

    boolean isPending() {
        return pending;
    }

    void complete() {
        pending = false;
    }
}
