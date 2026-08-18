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
}
