package org.pinnaclesmp.fragguard;

record DatabaseHealth(
        int queuedWrites,
        int writeCapacity,
        int queuedOperations,
        int operationCapacity,
        long droppedWrites,
        long coalescedWrites,
        boolean healthy,
        String lastError
) {
    @Override
    public boolean healthy() {
        return healthy && droppedWrites == 0 && lastError.isBlank();
    }

    boolean storageAvailable() {
        return healthy;
    }

    boolean degraded() {
        return !healthy();
    }
}
