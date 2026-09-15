package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuiLookupLimitTest {
    @Test
    void honorsPositiveFetchSizes() {
        assertEquals(1, FragGuardPlugin.resolveGuiLookupFetchSize(1));
        assertEquals(250, FragGuardPlugin.resolveGuiLookupFetchSize(250));
        assertEquals(4096, FragGuardPlugin.resolveGuiLookupFetchSize(4096));
    }

    @Test
    void preservesTheDefaultFetchSize() {
        assertEquals(1000, FragGuardPlugin.DEFAULT_GUI_LOOKUP_FETCH_SIZE);
        assertEquals(1000, FragGuardPlugin.resolveGuiLookupFetchSize(1000));
    }

    @Test
    void invalidNonPositiveFetchSizesFallBackToTheDocumentedDefault() {
        assertEquals(1000, FragGuardPlugin.resolveGuiLookupFetchSize(0));
        assertEquals(1000, FragGuardPlugin.resolveGuiLookupFetchSize(-1));
        assertEquals(1000, FragGuardPlugin.resolveGuiLookupFetchSize(Integer.MIN_VALUE));
    }
}
