package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseStartupAwaiterTest {
    @Test
    void warningDoesNotAbortStartupWhenHardTimeoutIsDisabled() throws Exception {
        CompletableFuture<Void> started = new CompletableFuture<>();
        List<String> warnings = new ArrayList<>();
        Thread completer = Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(80L);
                started.complete(null);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                started.completeExceptionally(exception);
            }
        });

        DatabaseStartupAwaiter.await(started, 10L, 0L, () -> "creating migration backup", warnings::add);
        completer.join();

        assertFalse(warnings.isEmpty(), "slow startup should report progress instead of silently hanging");
        assertTrue(warnings.stream().anyMatch(message -> message.contains("creating migration backup")));
    }

    @Test
    void optionalHardTimeoutStillStopsAnUnboundedStartupWait() {
        CompletableFuture<Void> started = new CompletableFuture<>();

        assertThrows(TimeoutException.class, () -> DatabaseStartupAwaiter.await(
                started, 10L, 40L, () -> "opening SQLite database", ignored -> {
                }));
    }

    @Test
    void workerFailureIsPropagatedImmediately() {
        CompletableFuture<Void> started = new CompletableFuture<>();
        started.completeExceptionally(new IllegalStateException("boom"));

        ExecutionException exception = assertThrows(ExecutionException.class,
                () -> DatabaseStartupAwaiter.await(started, 10L, 0L, () -> "schema migration", ignored -> {
                }));
        assertTrue(exception.getCause() instanceof IllegalStateException);
    }
}
