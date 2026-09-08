package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class RollbackNormalCommitFailureRegressionTest {

    @Test
    void nonQueueCommitFailureRecommitsCompletedResultsBeforeFailureFinalization() throws Exception {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("FragGuardTest"));

        List<String> calls = new ArrayList<>();
        AtomicInteger markCalls = new AtomicInteger();
        Database database = mock(Database.class);
        when(database.markRollbackBatchAppliedAsync(eq(41L), anyList()))
                .thenAnswer(invocation -> {
                    calls.add("mark");
                    if (markCalls.incrementAndGet() == 1) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("simulated commit failure"));
                    }
                    return CompletableFuture.completedFuture(null);
                });
        when(database.failRollbackJobAsync(eq(41L), anyString()))
                .thenAnswer(invocation -> {
                    calls.add("fail");
                    return CompletableFuture.completedFuture(null);
                });

        RollbackJob job = new RollbackJob(41L, 1_000L, UUID.randomUUID().toString(), "Builder",
                UUID.randomUUID().toString(), "world", 0, 0, 20,
                500L, "RUNNING", 1, 0, 0, 0, null);
        RollbackStepResult completed = new RollbackStepResult(
                0, true, false, "minecraft:stone", null);
        FragGuardCommand command = new FragGuardCommand(plugin, database);

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTask(eq(plugin), org.mockito.ArgumentMatchers.any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return mock(BukkitTask.class);
                });

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            persistBatchResults(command, job, List.of(completed));
        }

        assertEquals(List.of("mark", "mark", "fail"), calls,
                "completed results must be re-committed before failure finalization");
    }

    private static void persistBatchResults(FragGuardCommand command, RollbackJob job,
                                            List<RollbackStepResult> results) throws Exception {
        Method method = FragGuardCommand.class.getDeclaredMethod(
                "persistBatchResults", RollbackJob.class, Player.class, List.class, List.class,
                int.class, boolean.class, int.class);
        method.setAccessible(true);
        method.invoke(command, job, null, List.<RollbackJobChange>of(), results, 0, false, -1);
    }
}
