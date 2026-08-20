package org.pinnaclesmp.fragguard;

final class StorageShutdownSupport {
    private StorageShutdownSupport() {
    }

    static StorageShutdownReport finish(DatabaseHealth before, DatabaseHealth after,
                                        long completedWritesBefore, long completedWritesAfter,
                                        int unconfirmedWrites,
                                        boolean workerStopped, boolean walCheckpointCompleted) {
        long drainedWrites = Math.max(0L, completedWritesAfter - completedWritesBefore);
        long lostDuringShutdown = Math.max(0L, after.droppedWrites() - before.droppedWrites());
        return new StorageShutdownReport(
                before.queuedWrites(),
                drainedWrites,
                after.queuedWrites(),
                after.queuedOperations(),
                lostDuringShutdown,
                Math.max(0, unconfirmedWrites),
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
                && lostDuringShutdown == 0
                && unconfirmedWrites == 0
                && storageAvailable
                && workerStopped
                && walCheckpointCompleted;
    }
}
