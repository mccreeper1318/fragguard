package org.pinnaclesmp.fragguard;

final class StorageShutdownSupport {
    private StorageShutdownSupport() {
    }

    static StorageShutdownReport finish(DatabaseShutdownSnapshot before, DatabaseHealth after,
                                        long completedWritesAfter,
                                        int unconfirmedWrites,
                                        boolean workerStopped, boolean walCheckpointCompleted) {
        DatabaseHealth beforeHealth = before.health();
        long drainedWrites = Math.max(0L, completedWritesAfter - before.completedWrites());
        long lostDuringShutdown = Math.max(0L, after.droppedWrites() - beforeHealth.droppedWrites());
        int safeUnconfirmedWrites = Math.max(0, unconfirmedWrites);
        int unconfirmedOperations = !workerStopped && safeUnconfirmedWrites == 0 ? 1 : 0;
        int remainingOperations = after.queuedOperations() + unconfirmedOperations;
        return new StorageShutdownReport(
                before.outstandingWrites(),
                drainedWrites,
                after.queuedWrites(),
                remainingOperations,
                unconfirmedOperations,
                lostDuringShutdown,
                safeUnconfirmedWrites,
                after.droppedWrites(),
                after.storageAvailable(),
                workerStopped,
                walCheckpointCompleted
        );
    }
}

record StorageShutdownReport(
        int queuedWritesAtStart,
        long drainedWrites,
        int remainingWrites,
        int remainingOperations,
        int unconfirmedOperations,
        long lostDuringShutdown,
        int unconfirmedWrites,
        long totalDroppedWrites,
        boolean storageAvailable,
        boolean workerStopped,
        boolean walCheckpointCompleted
) {
    boolean clean() {
        return remainingWrites == 0
                && remainingOperations == 0
                && unconfirmedOperations == 0
                && lostDuringShutdown == 0
                && unconfirmedWrites == 0
                && totalDroppedWrites == 0
                && storageAvailable
                && workerStopped
                && walCheckpointCompleted;
    }
}
