package org.pinnaclesmp.fragguard;

final class RollbackTickBudget {
    private int currentTick = Integer.MIN_VALUE;
    private long usedNanos;
    private long sliceStartedAt;
    private long maximumNanos;
    private boolean active;

    boolean begin(int tick, long nowNanos, long budgetNanos) {
        prepare(tick, budgetNanos);
        if (usedNanos >= maximumNanos) {
            return false;
        }
        sliceStartedAt = nowNanos;
        active = true;
        return true;
    }

    void beginCommitted(int tick, long nowNanos, long budgetNanos) {
        prepare(tick, budgetNanos);
        sliceStartedAt = nowNanos;
        active = true;
    }

    private void prepare(int tick, long budgetNanos) {
        if (active) {
            throw new IllegalStateException("A rollback work slice is already active.");
        }
        if (currentTick != tick) {
            currentTick = tick;
            usedNanos = 0L;
        }
        maximumNanos = Math.max(1L, budgetNanos);
    }

    boolean exhausted(long nowNanos) {
        if (!active) {
            throw new IllegalStateException("No rollback work slice is active.");
        }
        return usedNanos + Math.max(0L, nowNanos - sliceStartedAt) >= maximumNanos;
    }

    void end(long nowNanos) {
        if (!active) {
            return;
        }
        usedNanos += Math.max(0L, nowNanos - sliceStartedAt);
        active = false;
    }
}
