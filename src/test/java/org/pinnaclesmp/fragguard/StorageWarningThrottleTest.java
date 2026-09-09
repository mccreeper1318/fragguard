package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageWarningThrottleTest {
    @Test
    void firstDegradedWarningIsImmediate() {
        assertTrue(StorageWarningThrottle.shouldWarn(false, 1_000L, 0L, 60_000L));
    }

    @Test
    void repeatedWarningWaitsForConfiguredInterval() {
        assertFalse(StorageWarningThrottle.shouldWarn(true, 6_000L, 1_000L, 60_000L),
                "changing health details such as dropped-write totals must not bypass the repeat interval");
        assertTrue(StorageWarningThrottle.shouldWarn(true, 61_000L, 1_000L, 60_000L));
    }
}
