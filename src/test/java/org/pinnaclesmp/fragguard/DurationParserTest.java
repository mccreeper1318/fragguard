package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationParserTest {
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1_000L;

    @Test
    void parsesNormalDurationsExactly() {
        assertEquals((2L * DAY_MILLIS) + (7L * 60L * 60L * 1_000L) + (15L * 60L * 1_000L),
                DurationParser.parseMillis(List.of("2d", "7h", "15m")));
    }

    @Test
    void rejectsNumericValuesThatDoNotFitInLong() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DurationParser.parseMillis(List.of("999999999999999999999999d")));

        assertEquals("Time value is too large.", error.getMessage());
    }

    @Test
    void rejectsUnitMultiplicationOverflow() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DurationParser.parseMillis(List.of(Long.MAX_VALUE + "d")));

        assertEquals("Time value is too large.", error.getMessage());
    }

    @Test
    void rejectsAccumulationOverflow() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DurationParser.parseMillis(List.of("9223372036854775s", "1s")));

        assertEquals("Time value is too large.", error.getMessage());
    }

    @Test
    void stopsWhenRetentionLimitIsExceeded() {
        DurationParser.DurationLimitExceededException error = assertThrows(
                DurationParser.DurationLimitExceededException.class,
                () -> DurationParser.parseMillis(List.of("30d", "1s", "999999999999999999999d"), 30L * DAY_MILLIS));

        assertEquals(30L * DAY_MILLIS, error.maximumMillis());
        assertTrue(error.getMessage().contains("retention"));
    }

    @Test
    void acceptsDurationExactlyAtRetentionLimit() {
        assertEquals(30L * DAY_MILLIS,
                DurationParser.parseMillis(List.of("30d"), 30L * DAY_MILLIS));
    }
}
