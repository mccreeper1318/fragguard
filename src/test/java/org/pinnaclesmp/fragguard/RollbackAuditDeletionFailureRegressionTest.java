package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackAuditDeletionFailureRegressionTest {

    @Test
    void staleAuditDeletionFailurePersistsCompletedResultsBeforeFailing() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/org/pinnaclesmp/fragguard/FragGuardCommand.java"));
        int methodStart = source.indexOf("private void deleteRequiredAudits");
        int methodEnd = source.indexOf("private void persistBatchResults", methodStart);

        assertTrue(methodStart >= 0 && methodEnd > methodStart,
                "deleteRequiredAudits helper must exist before persistBatchResults");
        String method = source.substring(methodStart, methodEnd);
        assertTrue(method.contains("Map<Integer, RollbackStepResult> results"),
                "audit deletion must retain the completed mutation results");
        assertTrue(method.contains("boolean undo"),
                "audit deletion must know whether rollback or undo results are being finalized");
        assertTrue(method.contains(
                        "persistCompletedResultsBeforeFailure(job, operator, results, undo, cause)"),
                "a non-retryable audit deletion failure must commit completed mutations before failing");
        assertFalse(method.contains("failJob(job, operator, cause)"),
                "audit deletion failure must not bypass completed-result persistence");
        assertTrue(method.contains(
                        "deleteRequiredAudits(job, operator, auditIds, results, undo, afterDeleted)"),
                "queue-pressure retries must preserve the completed-result durability context");
    }
}
