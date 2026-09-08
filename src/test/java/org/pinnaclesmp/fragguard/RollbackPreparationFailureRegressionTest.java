package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackPreparationFailureRegressionTest {

    @Test
    void recoveredResultsArePersistedBeforePreparationFailure() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/org/pinnaclesmp/fragguard/FragGuardCommand.java"));
        int methodStart = source.indexOf("private void applyPreparedBatch");
        int failureStart = source.indexOf("if (failure != null)", methodStart);
        int methodContinuation = source.indexOf("int preparedNextIndex", failureStart);

        assertTrue(methodStart >= 0 && failureStart > methodStart && methodContinuation > failureStart);
        String failureBranch = source.substring(failureStart, methodContinuation);
        assertTrue(failureBranch.contains(
                        "persistCompletedResultsBeforeFailure(job, operator, results, undo, failure)"),
                "restart-recovered results must be committed before preparation failure marks the job failed");
        assertFalse(failureBranch.contains("failJob(job, operator, failure)"),
                "preparation failure must not bypass recovered-result persistence");
    }

    @Test
    void completedForceResultsArePersistedBeforeRetryPreparationFailure() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/org/pinnaclesmp/fragguard/FragGuardCommand.java"));
        int methodStart = source.indexOf("private void retryForcedChangesSlice");
        int failureStart = source.indexOf("if (failure != null)", methodStart);
        int methodContinuation = source.indexOf("if (index < changes.size())", failureStart);

        assertTrue(methodStart >= 0 && failureStart > methodStart && methodContinuation > failureStart);
        String failureBranch = source.substring(failureStart, methodContinuation);
        assertTrue(failureBranch.contains(
                        "persistCompletedResultsBeforeFailure(job, operator, results, false, failure)"),
                "completed force mutations must be committed before retry preparation failure");
        assertFalse(failureBranch.contains("failJob(job, operator, failure)"),
                "force retry preparation failure must not bypass completed-result persistence");
    }
}
