package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadiusParserTest {
    @Test
    void missingRadiusRemainsOptional() {
        assertTrue(RadiusParser.parse(new String[]{"p:2"}).isEmpty());
    }

    @Test
    void parsesShortAndLongRadiusOptions() {
        assertEquals(OptionalInt.of(30), RadiusParser.parse(new String[]{"r:30"}));
        assertEquals(OptionalInt.of(45), RadiusParser.parse(new String[]{"radius:45"}));
    }

    @Test
    void malformedRadiusIsAnError() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RadiusParser.parse(new String[]{"r:not-a-number"}));

        assertTrue(error.getMessage().contains("Invalid radius"));
    }

    @Test
    void overflowingRadiusIsAnError() {
        assertThrows(IllegalArgumentException.class,
                () -> RadiusParser.parse(new String[]{"radius:999999999999999999999"}));
    }
}
