package org.pinnaclesmp.fragguard;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Read-only lookup path used by the inventory GUI.
 *
 * <p>The normal {@link Database} lookup API predates the GUI block-entity drill-down and returns the
 * full before/after snapshot blobs with every row. The GUI only needs those blobs after an operator
 * chooses one exact event, so this store deliberately keeps list queries lightweight and loads at most
 * one event payload on demand.</p>
 */
final class GuiLookupStore {
    private static final String AREA_FILTER = """
            rollback_pending = 0
            AND world_uuid IN (?, ?)
            AND (world_uuid = ? OR world = ?)
            AND happened_at >= ?
            AND happened_at <= ?
            AND chunk_x BETWEEN ? AND ?
            AND chunk_z BETWEEN ? AND ?
            AND x BETWEEN ? AND ?
            AND z BETWEEN ? AND ?
            AND ((CAST(x AS INTEGER) - ?) * (CAST(x AS INTEGER) - ?)
               + (CAST(z AS INTEGER) - ?) * (CAST(z AS INTEGER) - ?)) <= ?
            """;

    private final File databaseFile;
    private final int queryTimeoutSeconds;

    GuiLookupStore(File dataFolder, int queryTimeoutSeconds) {
        this.databaseFile = new File(dataFolder, "fragguard.db");
        this.queryTimeoutSeconds = Math.max(1, queryTimeoutSeconds);
    }

    CompletableFuture<List<LookupRow>> selectRowsAsync(
            String worldUuid,
            String worldName,
            int centerX,
            int centerZ,
            int radius,
            long cutoffTimestamp,
            long snapshotTimestamp,
            int rowLimit
    ) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                    SELECT id, happened_at, actor_uuid, actor_name, world, x, y, z, action, before_data, after_data,
                           CASE WHEN before_entity_data IS NULL AND after_entity_data IS NULL
                                THEN 0 ELSE 1 END AS has_block_entity_data
                    FROM block_changes
                    WHERE %s
                    ORDER BY happened_at DESC, id DESC
                    LIMIT ?
                    """.formatted(AREA_FILTER);
            try (Connection connection = openReadConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(queryTimeoutSeconds);
                int index = bindArea(statement, worldUuid, worldName, centerX, centerZ, radius,
                        cutoffTimestamp, snapshotTimestamp);
                statement.setInt(index, Math.max(1, rowLimit));

                List<LookupRow> rows = new ArrayList<>();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        rows.add(new LookupRow(
                                resultSet.getLong("id"),
                                resultSet.getLong("happened_at"),
                                resultSet.getString("actor_uuid"),
                                resultSet.getString("actor_name"),
                                resultSet.getString("world"),
                                resultSet.getInt("x"),
                                resultSet.getInt("y"),
                                resultSet.getInt("z"),
                                ChangeAction.fromStorageId(resultSet.getString("action")),
                                resultSet.getString("before_data"),
                                resultSet.getString("after_data"),
                                resultSet.getBoolean("has_block_entity_data")
                        ));
                    }
                }
                return List.copyOf(rows);
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    CompletableFuture<LookupEventPayload> loadEventPayloadAsync(long rowId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = openReadConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         SELECT before_entity_data, after_entity_data
                         FROM block_changes
                         WHERE id = ? AND rollback_pending = 0
                         """)) {
                statement.setQueryTimeout(queryTimeoutSeconds);
                statement.setLong(1, rowId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("That exact history event is no longer available.");
                    }
                    return new LookupEventPayload(
                            resultSet.getBytes("before_entity_data"),
                            resultSet.getBytes("after_entity_data")
                    );
                }
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

    private int bindArea(
            PreparedStatement statement,
            String worldUuid,
            String worldName,
            int centerX,
            int centerZ,
            int radius,
            long cutoffTimestamp,
            long snapshotTimestamp
    ) throws SQLException {
        long minX = (long) centerX - radius;
        long maxX = (long) centerX + radius;
        long minZ = (long) centerZ - radius;
        long maxZ = (long) centerZ + radius;
        int index = 1;
        statement.setString(index++, worldUuid);
        statement.setString(index++, worldName);
        statement.setString(index++, worldUuid);
        statement.setString(index++, worldName);
        statement.setLong(index++, cutoffTimestamp);
        statement.setLong(index++, snapshotTimestamp);
        statement.setLong(index++, Math.floorDiv(minX, 16L));
        statement.setLong(index++, Math.floorDiv(maxX, 16L));
        statement.setLong(index++, Math.floorDiv(minZ, 16L));
        statement.setLong(index++, Math.floorDiv(maxZ, 16L));
        statement.setLong(index++, minX);
        statement.setLong(index++, maxX);
        statement.setLong(index++, minZ);
        statement.setLong(index++, maxZ);
        statement.setInt(index++, centerX);
        statement.setInt(index++, centerX);
        statement.setInt(index++, centerZ);
        statement.setInt(index++, centerZ);
        statement.setLong(index++, (long) radius * radius);
        return index;
    }
}

record LookupEventPayload(byte[] beforeEntityData, byte[] afterEntityData) {
    boolean changed() {
        return !Arrays.equals(beforeEntityData, afterEntityData);
    }
}
