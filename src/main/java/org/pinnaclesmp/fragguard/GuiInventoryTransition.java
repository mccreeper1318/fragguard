package org.pinnaclesmp.fragguard;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Builds and opens a replacement GUI before the caller commits its page state.
 * A rendering or inventory-open failure leaves the previous state available
 * for recovery instead of advancing to a page that was never displayed.
 */
final class GuiInventoryTransition {
    private GuiInventoryTransition() {
    }

    static <T> boolean attempt(Supplier<T> render, Consumer<T> open,
                               Consumer<RuntimeException> onFailure) {
        try {
            T replacement = render.get();
            open.accept(replacement);
            return true;
        } catch (RuntimeException failure) {
            onFailure.accept(failure);
            return false;
        }
    }
}
