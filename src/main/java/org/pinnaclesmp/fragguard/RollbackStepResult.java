package org.pinnaclesmp.fragguard;

record RollbackStepResult(
        int sequence,
        boolean changed,
        boolean conflicted,
        String appliedData,
        byte[] appliedEntityData
) {
    RollbackStepResult(int sequence, boolean changed, boolean conflicted) {
        this(sequence, changed, conflicted, null, null);
    }

    RollbackStepResult(int sequence, boolean changed) {
        this(sequence, changed, false);
    }
}
