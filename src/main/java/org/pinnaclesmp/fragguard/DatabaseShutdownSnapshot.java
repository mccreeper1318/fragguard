package org.pinnaclesmp.fragguard;

record DatabaseShutdownSnapshot(
        DatabaseHealth health,
        long completedWrites,
        int inFlightWrites
) {
    int outstandingWrites() {
        return Math.max(0, health.queuedWrites()) + Math.max(0, inFlightWrites);
    }
}
