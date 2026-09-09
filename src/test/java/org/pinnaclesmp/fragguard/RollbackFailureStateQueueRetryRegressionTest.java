package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackFailureStateQueueRetryRegressionTest {
    private static final Path COMMAND_SOURCE = Path.of(
            "src/main/java/org/pinnaclesmp/fragguard/FragGuardCommand.java");

    @Test
    void terminalFailureStateRetriesTransientOperationQueuePressure() throws Exception {
        String source = Files.readString(COMMAND_SOURCE);
        int methodStart = source.indexOf("private void failJob");
        int methodEnd = source.indexOf("private void reportJobError", methodStart);

        assertTrue(methodStart >= 0 && methodEnd > methodStart);
        String methods = source.substring(methodStart, methodEnd);
        assertTrue(methods.contains("persistFailedJobState(job, reason);"),
                "job failure handling must route terminal-state persistence through the retry helper");
        assertTrue(methods.contains("database.failRollbackJobAsync(job.id(), reason)"),
                "the retry helper must persist the durable failed job state");
        assertTrue(methods.contains("OPERATION_QUEUE_FULL.equals(failure.getMessage())"),
                "terminal failure persistence must recognize transient operation-queue pressure");
        assertTrue(methods.contains("Bukkit.getScheduler().runTaskLater(plugin"),
                "terminal failure persistence must retry on a later server tick");
        assertTrue(methods.split("persistFailedJobState\\(job, reason\\)", -1).length - 1 >= 2,
                "queue-pressure retries must re-enter the same terminal-state persistence helper");
        assertFalse(methods.contains("failRollbackJobAsync(job.id(), reason).exceptionally"),
                "terminal failure persistence must not only log and abandon queue-full failures");
    }
}
