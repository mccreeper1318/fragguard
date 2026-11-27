package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A normal host stop and a plugin-only disable are not the same event. */
class MaintenanceShutdownGateTest {
    @Test
    void ordinaryPaperStopNeverReportsMaintenanceAsUnsafePluginDisable() {
        assertFalse(FragGuardPlugin.mustStopPaperOnRestrictedDisable(true, true));
        assertFalse(FragGuardPlugin.mustStopPaperOnRestrictedDisable(false, true));
    }

    @Test
    void livePluginReloadWhileMaintenanceIsRestrictedRemainsFailClosed() {
        assertTrue(FragGuardPlugin.mustStopPaperOnRestrictedDisable(true, false));
        assertFalse(FragGuardPlugin.mustStopPaperOnRestrictedDisable(false, false));
    }
}
