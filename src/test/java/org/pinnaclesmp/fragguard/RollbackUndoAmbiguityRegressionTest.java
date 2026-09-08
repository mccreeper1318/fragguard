package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackUndoAmbiguityRegressionTest {

    @Test
    void legacyPendingUndoDoesNotTreatTargetAsItsPreMutationState() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/org/pinnaclesmp/fragguard/FragGuardCommand.java"));
        int pendingStart = source.indexOf("if (change.pendingAuditId() != null)");
        int undoStart = source.indexOf("if (undo) {", pendingStart);

        assertTrue(pendingStart >= 0 && undoStart > pendingStart);
        String pendingRecovery = source.substring(pendingStart, undoStart);
        assertTrue(pendingRecovery.contains("undo && change.appliedData() == null"),
                "legacy pending undo recovery must explicitly handle an unknown applied state");
        assertTrue(pendingRecovery.contains(
                        "new RollbackStepResult(change.sequence(), false, true)"),
                "an ambiguous legacy pending undo must remain a retryable conflict");
        assertFalse(pendingRecovery.contains(
                        "Objects.requireNonNullElse(change.appliedData(), change.targetData())"),
                "pending undo recovery must never substitute the requested target for an unknown applied state");
    }

    @Test
    void missingAppliedEntitySnapshotCannotActAsAnUndoWildcard() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/org/pinnaclesmp/fragguard/FragGuardCommand.java"));
        int methodStart = source.indexOf("private void applyPreparedBatch");
        int undoStart = source.indexOf("if (undo) {", methodStart);
        int undoEnd = source.indexOf("continue;", undoStart);

        assertTrue(methodStart >= 0 && undoStart > methodStart && undoEnd > undoStart);
        String undoGuard = source.substring(undoStart, undoEnd);
        assertTrue(undoGuard.contains(
                        "change.appliedEntityData() == null && actualEntityData != null"),
                "a supported live block entity with a missing applied snapshot must be treated as unknown");
        assertTrue(undoGuard.contains(
                        "new RollbackStepResult(change.sequence(), false, true)"),
                "unknown applied entity state must resolve as a retryable undo conflict");
    }
}
