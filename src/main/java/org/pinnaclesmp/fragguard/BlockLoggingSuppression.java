package org.pinnaclesmp.fragguard;

final class BlockLoggingSuppression {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private BlockLoggingSuppression() {
    }

    static boolean isSuppressed() {
        return DEPTH.get() > 0;
    }

    static void runSuppressed(Runnable action) {
        int previousDepth = DEPTH.get();
        DEPTH.set(previousDepth + 1);
        try {
            action.run();
        } finally {
            if (previousDepth == 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(previousDepth);
            }
        }
    }
}
