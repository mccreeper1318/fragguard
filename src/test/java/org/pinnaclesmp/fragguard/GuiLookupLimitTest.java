package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuiLookupLimitTest {
    @Test
    void honorsPositiveLimitsBelowTwoHundredFifty() {
        assertEquals(1, FragGuardPlugin.resolveGuiLookupMaxRows(1));
        assertEquals(25, FragGuardPlugin.resolveGuiLookupMaxRows(25));
        assertEquals(249, FragGuardPlugin.resolveGuiLookupMaxRows(249));
    }

    @Test
    void preservesTheDefaultLimit() {
        assertEquals(5000, FragGuardPlugin.DEFAULT_GUI_LOOKUP_MAX_ROWS);
        assertEquals(5000, FragGuardPlugin.resolveGuiLookupMaxRows(5000));
    }

    @Test
    void invalidNonPositiveLimitsFallBackToTheDocumentedDefault() {
        assertEquals(5000, FragGuardPlugin.resolveGuiLookupMaxRows(0));
        assertEquals(5000, FragGuardPlugin.resolveGuiLookupMaxRows(-1));
        assertEquals(5000, FragGuardPlugin.resolveGuiLookupMaxRows(Integer.MIN_VALUE));
    }
}
