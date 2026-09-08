package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackPendingRecoveryConflictRegressionTest {

    @Test
    void ambiguousPendingRecoveryFailsClosedAsConflict() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/org/pinnaclesmp/fragguard/FragGuardCommand.java"));
        int methodStart = source.indexOf("private void applyPreparedBatch");
        int pendingStart = source.indexOf("if (change.pendingAuditId() != null)", methodStart);
        int normalUndoStart = source.indexOf("if (undo) {", pendingStart + 1);

        assertTrue(methodStart >= 0 && pendingStart > methodStart && normalUndoStart > pendingStart,
                "pending-audit recovery branch must exist before normal undo handling");
        String pendingRecovery = source.substring(pendingStart, normalUndoStart);

        assertTrue(pendingRecovery.contains(
                        "new RollbackStepResult(change.sequence(), false, true)"),
                "an ambiguous live state must be retained as a conflict instead of claimed as a completed mutation");
        assertFalse(pendingRecovery.contains(
                        "change.sequence(), true, false, actualData, actualEntityData"),
                "recovery must never persist an unrelated live state as FragGuard's applied result");
    }
}
