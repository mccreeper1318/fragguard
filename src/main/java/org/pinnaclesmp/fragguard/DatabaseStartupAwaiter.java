package org.pinnaclesmp.fragguard;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class DatabaseStartupAwaiter {
    private DatabaseStartupAwaiter() {
    }

    static void await(CompletableFuture<Void> started,
                      long warningMillis,
                      long timeoutMillis,
                      Supplier<String> stageSupplier,
                      Consumer<String> warningSink)
            throws InterruptedException, ExecutionException, TimeoutException {
        long safeWarningMillis = Math.max(1L, warningMillis);
        long safeTimeoutMillis = Math.max(0L, timeoutMillis);
        long startedAt = System.nanoTime();
        long warningNanos = TimeUnit.MILLISECONDS.toNanos(safeWarningMillis);
        long nextWarning = addSaturated(startedAt, warningNanos);
        long deadline = safeTimeoutMillis == 0L
                ? Long.MAX_VALUE
                : addSaturated(startedAt, TimeUnit.MILLISECONDS.toNanos(safeTimeoutMillis));

        while (true) {
            long now = System.nanoTime();
            long nextWake = Math.min(nextWarning, deadline);
            long waitNanos = Math.max(1L, nextWake - now);
            try {
                started.get(waitNanos, TimeUnit.NANOSECONDS);
                return;
            } catch (TimeoutException exception) {
                now = System.nanoTime();
                if (safeTimeoutMillis > 0L && now >= deadline) {
                    throw exception;
                }
                if (now >= nextWarning) {
                    long elapsedSeconds = Math.max(1L,
                            TimeUnit.NANOSECONDS.toSeconds(Math.max(0L, now - startedAt)));
                    String stage = Objects.requireNonNullElse(stageSupplier.get(), "unknown step");
                    warningSink.accept("FragGuard SQLite startup is still running after " + elapsedSeconds
                            + " second(s) (" + stage + "). Server startup will continue once initialization finishes.");
                    nextWarning = addSaturated(now, warningNanos);
                }
            }
        }
    }

    private static long addSaturated(long value, long increment) {
        if (increment > 0L && value > Long.MAX_VALUE - increment) {
            return Long.MAX_VALUE;
        }
        return value + increment;
    }
}
