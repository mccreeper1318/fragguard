package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackMutationDurabilityRegressionTest {
    private static final Path COMMAND_SOURCE = Path.of(
            "src/main/java/org/pinnaclesmp/fragguard/FragGuardCommand.java");

    @Test
    void entityRestoreFailurePreservesTheCurrentPendingAudit() throws Exception {
        String source = Files.readString(COMMAND_SOURCE);
        int methodStart = source.indexOf("private void applyPersistedCandidates");
        int methodEnd = source.indexOf("private void persistCompletedResultsBeforeFailure", methodStart);

        assertTrue(methodStart >= 0 && methodEnd > methodStart);
        String method = source.substring(methodStart, methodEnd);
        int firstRestore = method.indexOf("BlockEntitySnapshot.restore(");
        int recordUnknown = method.indexOf("recordUnknownAppliedEntityState(candidate, block, results)");
        assertTrue(firstRestore >= 0 && recordUnknown > firstRestore,
                "entity restoration failures must record the current mutation before the outer failure cleanup runs");
        assertTrue(method.split("recordUnknownAppliedEntityState\\(candidate, block, results\\)", -1).length - 1 >= 2,
                "both restore failures and post-mutation capture failures must preserve the current audit as unknown");
    }

    @Test
    void normalBatchResultPersistenceRetriesTransientOperationQueuePressure() throws Exception {
        String source = Files.readString(COMMAND_SOURCE);
        int methodStart = source.indexOf("private void persistBatchResults");
        int methodEnd = source.indexOf("private void failJob", methodStart);

        assertTrue(methodStart >= 0 && methodEnd > methodStart);
        String method = source.substring(methodStart, methodEnd);
        assertTrue(method.contains("OPERATION_QUEUE_FULL.equals(cause.getMessage())"),
                "normal batch-result persistence must recognize transient operation-queue pressure");
        assertTrue(method.contains("persistBatchResults(job, operator, changes, results"),
                "normal batch-result persistence must retry the same durable result set before advancing or failing");
    }

    @Test
    void explicitUnknownEntityMarkerNeverMatchesALiveState() throws Exception {
        Method matchesState = FragGuardCommand.class.getDeclaredMethod(
                "matchesState", String.class, byte[].class, String.class, byte[].class);
        matchesState.setAccessible(true);

        byte[] unknownMarker = new byte[]{0};
        assertFalse((boolean) matchesState.invoke(null,
                        "minecraft:chest", new byte[]{1, 2, 3}, "minecraft:chest", unknownMarker),
                "a persisted unknown entity snapshot must conflict with a concrete live entity state");
        assertFalse((boolean) matchesState.invoke(null,
                        "minecraft:chest", null, "minecraft:chest", unknownMarker),
                "a persisted unknown entity snapshot must also conflict with no live entity snapshot");
    }
}
