package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuiPlayerQueryTimeoutTest {
    @TempDir Path directory;

    @Test
    void deadlineInterruptsRegisteredSqlStatementAndStopsWorker() throws Exception {
        GuiPlayerHistoryStore store = new GuiPlayerHistoryStore(directory.toFile(), 1);
        PreparedStatement statement = mock(PreparedStatement.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch statementCancelled = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        doAnswer(invocation -> {
            statementCancelled.countDown();
            return null;
        }).when(statement).cancel();

        CompletableFuture<String> future = store.boundedQueryAsync("player-selector", () -> {
            try {
                store.trackStatement(statement);
                entered.countDown();
                if (!statementCancelled.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("SQLite cancellation was not requested");
                }
                store.ensureQueryActive();
                return "expired query should never return a value";
            } catch (SQLException | InterruptedException error) {
                throw new CompletionException(error);
            } finally {
                finished.countDown();
            }
        });

        assertTrue(entered.await(2, TimeUnit.SECONDS), "worker did not register the statement");
        CompletionException timeout = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(TimeoutException.class, timeout.getCause());
        assertTrue(statementCancelled.await(2, TimeUnit.SECONDS),
                "GUI deadline must cancel the actual SQL statement, not only the future");
        assertTrue(finished.await(2, TimeUnit.SECONDS),
                "timed-out background worker must stop after statement cancellation");
        verify(statement, atLeastOnce()).cancel();
    }

    @Test
    void cancelledRequestRejectsSubsequentSqlAndNewRequestsRemainIndependent() throws Exception {
        GuiPlayerHistoryStore.QueryCancellation cancellation = new GuiPlayerHistoryStore.QueryCancellation();
        PreparedStatement first = mock(PreparedStatement.class);
        cancellation.track(first);
        cancellation.cancel();
        assertTrue(cancellation.isCancelled());
        verify(first).cancel();

        PreparedStatement later = mock(PreparedStatement.class);
        assertThrows(SQLException.class, () -> cancellation.track(later),
                "after a timeout the old worker must not start another filter or page query");
        verify(later).cancel();

        GuiPlayerHistoryStore store = new GuiPlayerHistoryStore(directory.toFile(), 1);
        PreparedStatement fresh = mock(PreparedStatement.class);
        assertEquals("ok", store.boundedQueryAsync("new-player-page", () -> {
            try {
                store.trackStatement(fresh);
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
            store.ensureQueryActive();
            return "ok";
        }).join());
        verify(fresh, never()).cancel();
    }
}
