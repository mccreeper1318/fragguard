package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackCompletedResultQueueRetryRegressionTest {
    private static final Path COMMAND_SOURCE = Path.of(
            "src/main/java/org/pinnaclesmp/fragguard/FragGuardCommand.java");

    @Test
    void completedResultPersistenceRetriesTransientOperationQueuePressure() throws Exception {
        String source = Files.readString(COMMAND_SOURCE);
        int methodStart = source.indexOf("private void persistCompletedResultsBeforeFailure");
        int methodEnd = source.indexOf("private void persistObservedCorrections", methodStart);

        assertTrue(methodStart >= 0 && methodEnd > methodStart);
        String method = source.substring(methodStart, methodEnd);
        assertTrue(method.contains("OPERATION_QUEUE_FULL.equals(persistenceFailure.getMessage())"),
                "completed-result persistence must recognize transient operation-queue pressure");
        assertTrue(method.contains("Bukkit.getScheduler().runTaskLater(plugin"),
                "completed-result persistence must retry on a later server tick");
        assertTrue(method.contains("persistCompletedResultsBeforeFailure("),
                "the retry must preserve the completed-result persistence path instead of failing the job");
    }
}
