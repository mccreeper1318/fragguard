package org.pinnaclesmp.fragguard;

final class StorageWarningThrottle {
    private StorageWarningThrottle() {
    }

    static boolean shouldWarn(boolean warningActive, long now, long lastWarningAt, long repeatMillis) {
        return !warningActive || now - lastWarningAt >= repeatMillis;
    }
}
