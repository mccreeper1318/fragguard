package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MaintenanceUiActionsTest {
    private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final long NOW = 1_800_000_000_000L;

    @Test
    void strictByDefaultAndSnapshotRequiresExplicitSeparateActions() {
        assertEquals(MaintenancePolicy.STRICT, MaintenanceUiActions.requestedPolicy("STRICT"));
        assertEquals(MaintenancePolicy.MAINTENANCE_SNAPSHOT,
                MaintenanceUiActions.requestedPolicy("snapshot"));
        assertThrows(IllegalArgumentException.class,
                () -> MaintenanceUiActions.requestedPolicy("force"));
        assertEquals("fg maintenance start strict",
                MaintenanceConfirmAction.START_STRICT.command());
        assertEquals("fg maintenance start snapshot acknowledge",
                MaintenanceConfirmAction.START_SNAPSHOT.command());
        assertEquals("fg maintenance approve snapshot-acknowledge",
                MaintenanceConfirmAction.APPROVE_SNAPSHOT.command());
        assertTrue(MaintenanceConfirmAction.APPROVE_SNAPSHOT.warning().contains("NEVER rolls back"));
    }

    @Test
    void menuAndCommandsShareExactFailClosedStageGates() {
        MaintenanceSnapshot idle = MaintenanceSnapshot.idle();
        assertTrue(MaintenanceUiActions.canSchedule(idle));
        assertFalse(MaintenanceUiActions.canCompact(idle));
        assertFalse(MaintenanceUiActions.canCancel(idle));

        MaintenanceSnapshot scheduled = MaintenanceSnapshot.scheduled(ADMIN, NOW, NOW + 600_000,
                MaintenancePolicy.STRICT);
        assertTrue(MaintenanceUiActions.canCancel(scheduled));
        assertEquals(100, MaintenanceUiActions.countdownPercent(scheduled, NOW));
        assertEquals(50, MaintenanceUiActions.countdownPercent(scheduled, NOW + 300_000));
        assertEquals(0, MaintenanceUiActions.countdownPercent(scheduled, NOW + 600_000));
        assertEquals(-1, MaintenanceUiActions.countdownPercent(idle, NOW));
        assertEquals(1, MaintenanceUiActions.countdownSeconds(0));
        assertEquals(86_400, MaintenanceUiActions.countdownSeconds(999_999));

        MaintenanceSnapshot active = scheduled.withPhase(MaintenancePhase.ACTIVE, "active");
        assertTrue(MaintenanceUiActions.canCompact(active));
        assertTrue(MaintenanceConfirmAction.COMPACT.available(active));
        assertFalse(MaintenanceConfirmAction.COMPACT.available(scheduled));
        assertFalse(MaintenanceConfirmAction.START_SNAPSHOT.available(active));

        MaintenanceSnapshot compacting = active.withCandidate("fragguard-compact-test.db", "copying");
        assertTrue(MaintenanceUiActions.canCancel(compacting));
        MaintenanceSnapshot verifying = compacting.withCutoff(NOW + 1, "copy complete")
                .withPhase(MaintenancePhase.VERIFYING, "checking");
        assertTrue(MaintenanceUiActions.canVerify(verifying));
        assertFalse(MaintenanceUiActions.canApprove(verifying));
        MaintenanceSnapshot verified = verifying.withManifest("fragguard-compact-test.manifest",
                "verified");
        assertFalse(MaintenanceUiActions.canVerify(verified));
        assertTrue(MaintenanceUiActions.canApprove(verified));
        assertTrue(MaintenanceConfirmAction.APPROVE_STRICT.available(verified));
        assertFalse(MaintenanceConfirmAction.APPROVE_SNAPSHOT.available(verified));
        // The actual approval is only possible through #167's durable checks.
        assertFalse(MaintenanceConfirmAction.START_STRICT.available(verified));
        MaintenanceSnapshot failed = verified.withPhase(MaintenancePhase.FAILED, "verification failed");
        assertTrue(MaintenanceUiActions.canCancel(failed),
                "explicit cancellation must recover admissions from FAILED after worker shutdown");
        assertTrue(MaintenanceConfirmAction.CANCEL.available(failed));
        assertFalse(MaintenanceConfirmAction.APPROVE_STRICT.available(failed));
        assertFalse(MaintenanceConfirmAction.START_STRICT.available(failed));
        assertFalse(MaintenanceUiActions.canSchedule(failed));

    }

    @Test
    void noInventedCompactionProgressPercentageOrEmptyCandidateSavings() {
        MaintenanceSnapshot state = MaintenanceSnapshot.idle();
        var copy = new OnlineDatabaseCompaction.Progress(true, "VACUUM INTO",
                "", 0, 0, 10_000, "Total SQLite output unknown");
        var scan = new CompactionCandidateVerifier.Progress(false, "idle", 0, "none");
        var model = MaintenanceMenuView.from(state, copy, scan, 50_000, 15_000, NOW);
        assertTrue(model.copying());
        assertEquals(-1, model.countdownPercent());
        assertTrue(model.sizeSummary().contains("smaller"));
        assertEquals("Size comparison unavailable",
                MaintenanceMenuView.from(state, copy, scan, -1, -1, NOW).sizeSummary());
    }
}
