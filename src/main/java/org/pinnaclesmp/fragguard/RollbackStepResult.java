package org.pinnaclesmp.fragguard;

record RollbackStepResult(int sequence, boolean changed, boolean conflicted) {
    RollbackStepResult(int sequence, boolean changed) {
        this(sequence, changed, false);
    }
}
