package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackLaterSliceFailureRegressionTest {
    private static final Path COMMAND_SOURCE = Path.of(
            "src/main/java/org/pinnaclesmp/fragguard/FragGuardCommand.java");

    @Test
    void pendingAuditInsertionFailurePersistsEarlierCompletedSlices() throws Exception {
        String source = Files.readString(COMMAND_SOURCE);
        int methodStart = source.indexOf("private void persistPendingAudits");
        int methodEnd = source.indexOf("private void deleteRequiredAudits", methodStart);

        assertTrue(methodStart >= 0 && methodEnd > methodStart);
        String method = source.substring(methodStart, methodEnd);
        assertTrue(method.contains("Map<Integer, RollbackStepResult> results"),
                "pending-audit persistence must retain access to completed earlier-slice results");
        assertTrue(method.contains(
                        "persistCompletedResultsBeforeFailure(job, operator, results, undo, cause)"),
                "non-queue audit insertion failures must commit completed slices before failing the job");
        assertFalse(method.contains("failJob(job, operator, cause)"),
                "pending-audit insertion failure must not bypass completed-result persistence");
    }

    @Test
    void laterSlicePreparationFailuresUseTheSameDurabilityBoundary() throws Exception {
        String source = Files.readString(COMMAND_SOURCE);
        int sliceStart = source.indexOf("private void persistAndApplyCandidateSlice");
        int applyStart = source.indexOf("private void applyPersistedCandidates", sliceStart);

        assertTrue(sliceStart >= 0 && applyStart > sliceStart);
        String preparation = source.substring(sliceStart, applyStart);
        assertTrue(preparation.contains(
                        "persistCompletedResultsBeforeFailure(job, operator, results, undo, throwable)"),
                "database preparation failure on a later slice must commit completed earlier slices");
        assertTrue(preparation.contains(
                        "persistCompletedResultsBeforeFailure(job, operator, results, undo, exception)"),
                "audit construction failure on a later slice must commit completed earlier slices");
        assertTrue(preparation.contains(
                        "persistPendingAudits(job, operator, undo, audits, results"),
                "pending-audit insertion must receive the accumulated results map");
    }
}
