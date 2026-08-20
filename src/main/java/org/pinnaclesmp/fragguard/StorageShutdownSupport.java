package org.pinnaclesmp.fragguard;

final class StorageShutdownSupport {
    private StorageShutdownSupport() {
    }

    static StorageShutdownReport finish(DatabaseHealth before, DatabaseHealth after,
                                        boolean workerStopped, boolean walCheckpointCompleted) {
        int drainedWrites = Math.max(0, before.queuedWrites() - after.queuedWrites());
        long lostDuringShutdown = Math.max(0L, after.droppedWrites() - before.droppedWrites());
        return new StorageShutdownReport(
                before.queuedWrites(),
                drainedWrites,
                after.queuedWrites(),
                after.queuedOperations(),
                lostDuringShutdown,
                after.droppedWrites(),
                after.storageAvailable(),
                workerStopped,
                walCheckpointCompleted
        );
    }
}

record StorageShutdownReport(
        int queuedWritesAtStart,
        int drainedWrites,
        int remainingWrites,
        int remainingOperations,
        long lostDuringShutdown,
        long totalDroppedWrites,
        boolean storageAvailable,
        boolean workerStopped,
        boolean walCheckpointCompleted
) {
    boolean clean() {
        return remainingWrites == 0
                && remainingOperations == 0
                && lostDuringShutdown == 0
                && storageAvailable
                && workerStopped
                && walCheckpointCompleted;
    }
}
