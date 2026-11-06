package org.pinnaclesmp.fragguard;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Read-only rollback job browser used by the inventory GUI. */
final class GuiRollbackStore {
    private final File databaseFile;
    private final int queryTimeoutSeconds;

    GuiRollbackStore(File dataFolder, int queryTimeoutSeconds) {
        databaseFile = new File(dataFolder, "fragguard.db");
        this.queryTimeoutSeconds = Math.max(1, queryTimeoutSeconds);
    }

    CompletableFuture<List<GuiRollbackJob>> loadUndoableJobsAsync() {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                    SELECT id, created_at, actor_name, world, radius, target_timestamp,
                           status, total_blocks, applied_blocks, conflict_blocks, last_error,
                           scope_type, target_player_uuid, target_player_name, world_scope_name,
                           historical_conflicts, affected_worlds, affected_chunks
                    FROM rollback_jobs
                    WHERE status IN ('COMPLETED', 'FAILED')
                      AND applied_blocks > 0
                    ORDER BY created_at DESC, id DESC
                    """;
            try (Connection connection = openReadConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(queryTimeoutSeconds);
                List<GuiRollbackJob> jobs = new ArrayList<>();
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        jobs.add(new GuiRollbackJob(
                                rows.getLong("id"),
                                rows.getLong("created_at"),
                                rows.getString("actor_name"),
                                rows.getString("world"),
                                rows.getInt("radius"),
                                rows.getLong("target_timestamp"),
                                rows.getString("status"),
                                rows.getInt("total_blocks"),
                                rows.getInt("applied_blocks"),
                                rows.getInt("conflict_blocks"),
                                rows.getString("last_error"),
                                rows.getString("scope_type"),
                                rows.getString("target_player_uuid"),
                                rows.getString("target_player_name"),
                                rows.getString("world_scope_name"),
                                rows.getInt("historical_conflicts"),
                                rows.getInt("affected_worlds"),
                                rows.getInt("affected_chunks")
                        ));
                    }
                }
                return List.copyOf(jobs);
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    private Connection openReadConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
        return connection;
    }
}

record GuiRollbackJob(
        long id,
        long createdAt,
        String actorName,
        String worldName,
        int radius,
        long targetTimestamp,
        String status,
        int totalBlocks,
        int appliedBlocks,
        int conflictBlocks,
        String lastError,
        String scopeType,
        String targetPlayerUuid,
        String targetPlayerName,
        String worldScopeName,
        int historicalConflicts,
        int affectedWorlds,
        int affectedChunks
) {
    GuiRollbackJob(long id, long createdAt, String actorName, String worldName,
                   int radius, long targetTimestamp, String status, int totalBlocks,
                   int appliedBlocks, int conflictBlocks, String lastError) {
        this(id, createdAt, actorName, worldName, radius, targetTimestamp,
                status, totalBlocks, appliedBlocks, conflictBlocks, lastError,
                "RADIUS", null, null, null, 0, 1, 0);
    }

    boolean isPlayerScope() {
        return "PLAYER".equals(scopeType);
    }
}
