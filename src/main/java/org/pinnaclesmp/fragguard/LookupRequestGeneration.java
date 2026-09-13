package org.pinnaclesmp.fragguard;

final class LookupRequestGeneration {
    private long generation;

    long begin() {
        generation++;
        return generation;
    }

    void invalidate() {
        generation++;
    }

    boolean isCurrent(long requestGeneration) {
        return generation == requestGeneration;
    }
}
