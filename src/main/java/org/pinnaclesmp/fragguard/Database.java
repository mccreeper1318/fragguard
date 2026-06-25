package org.pinnaclesmp.fragguard;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

final class Database {
    private final JavaPlugin plugin;
    private final String jdbcUrl;
    private final ExecutorService executor;

    Database(JavaPlugin plugin) {
        this.plugin = plugin;
        File databaseFile = new File(plugin.getDataFolder(), "fragguard.db");
        this.jdbcUrl = "jdbc:sqlite:" + databaseFile.getAbsolutePath();
        this.executor = Executors.newSingleThreadExecutor(new FragGuardThreadFactory());
    }

    void init() throws SQLException, ClassNotFoundException {
        Class.forName("org.sqlite.JDBC");
        plugin.getDataFolder().mkdirs();

        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("PRAGMA journal_mode=WAL");
            statement.executeUpdate("PRAGMA synchronous=NORMAL");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS block_changes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        happened_at INTEGER NOT NULL,
                        actor_uuid TEXT NOT NULL,
                        actor_name TEXT NOT NULL,
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        action TEXT NOT NULL,
                        before_data TEXT NOT NULL,
                        after_data TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fg_time ON block_changes(happened_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fg_area_time ON block_changes(world, x, z, happened_at)");
        }
    }

    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    void insertAsync(BlockChange change) {
        executor.execute(() -> {
            try {
                insert(change);
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.WARNING, "Failed to write block change to database", exception);
            }
        });
    }

    CompletableFuture<Integer> cleanupOldRecordsAsync(int retentionDays) {
        return CompletableFuture.supplyAsync(() -> {
            long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays);
            try {
                return deleteOlderThan(cutoff);
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.WARNING, "Failed to clean old FragGuard records", exception);
                return 0;
            }
        }, executor);
    }

    CompletableFuture<LookupPage> lookupAsync(String worldName, int centerX, int centerZ, int radius, int page, int pageSize, int retentionDays) {
        return CompletableFuture.supplyAsync(() -> {
            long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays);
            try {
                int total = countLookup(worldName, centerX, centerZ, radius, cutoff);
                int totalPages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
                int safePage = Math.max(1, Math.min(page, totalPages));
                List<LookupRow> rows = selectLookup(worldName, centerX, centerZ, radius, cutoff, safePage, pageSize);
                return new LookupPage(rows, safePage, pageSize, total);
            } catch (SQLException exception) {
                throw new IllegalStateException("Lookup failed", exception);
            }
        }, executor);
    }

    CompletableFuture<List<RollbackTarget>> rollbackTargetsAsync(String worldName, int centerX, int centerZ, int radius, long targetTimestamp) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return selectRollbackTargets(worldName, centerX, centerZ, radius, targetTimestamp);
            } catch (SQLException exception) {
                throw new IllegalStateException("Rollback query failed", exception);
            }
        }, executor);
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void insert(BlockChange change) throws SQLException {
        String sql = """
                INSERT INTO block_changes
                (happened_at, actor_uuid, actor_name, world, x, y, z, action, before_data, after_data)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, change.happenedAt());
            statement.setString(2, change.actorUuid());
            statement.setString(3, change.actorName());
            statement.setString(4, change.worldName());
            statement.setInt(5, change.x());
            statement.setInt(6, change.y());
            statement.setInt(7, change.z());
            statement.setString(8, change.action().name());
            statement.setString(9, change.beforeData());
            statement.setString(10, change.afterData());
            statement.executeUpdate();
        }
    }

    private int deleteOlderThan(long cutoffTimestamp) throws SQLException {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("DELETE FROM block_changes WHERE happened_at < ?")) {
            statement.setLong(1, cutoffTimestamp);
            return statement.executeUpdate();
        }
    }

    private int countLookup(String worldName, int centerX, int centerZ, int radius, long cutoffTimestamp) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM block_changes
                WHERE world = ?
                  AND happened_at >= ?
                  AND x BETWEEN ? AND ?
                  AND z BETWEEN ? AND ?
                  AND ((x - ?) * (x - ?) + (z - ?) * (z - ?)) <= ?
                """;
        long radiusSquared = (long) radius * radius;

        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, worldName);
            statement.setLong(2, cutoffTimestamp);
            statement.setInt(3, centerX - radius);
            statement.setInt(4, centerX + radius);
            statement.setInt(5, centerZ - radius);
            statement.setInt(6, centerZ + radius);
            statement.setInt(7, centerX);
            statement.setInt(8, centerX);
            statement.setInt(9, centerZ);
            statement.setInt(10, centerZ);
            statement.setLong(11, radiusSquared);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private List<LookupRow> selectLookup(String worldName, int centerX, int centerZ, int radius, long cutoffTimestamp, int page, int pageSize) throws SQLException {
        String sql = """
                SELECT happened_at, actor_name, world, x, y, z, action, before_data, after_data
                FROM block_changes
                WHERE world = ?
                  AND happened_at >= ?
                  AND x BETWEEN ? AND ?
                  AND z BETWEEN ? AND ?
                  AND ((x - ?) * (x - ?) + (z - ?) * (z - ?)) <= ?
                ORDER BY happened_at DESC, id DESC
                LIMIT ? OFFSET ?
                """;
        long radiusSquared = (long) radius * radius;
        int offset = (page - 1) * pageSize;

        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, worldName);
            statement.setLong(2, cutoffTimestamp);
            statement.setInt(3, centerX - radius);
            statement.setInt(4, centerX + radius);
            statement.setInt(5, centerZ - radius);
            statement.setInt(6, centerZ + radius);
            statement.setInt(7, centerX);
            statement.setInt(8, centerX);
            statement.setInt(9, centerZ);
            statement.setInt(10, centerZ);
            statement.setLong(11, radiusSquared);
            statement.setInt(12, pageSize);
            statement.setInt(13, offset);

            List<LookupRow> rows = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(new LookupRow(
                            resultSet.getLong("happened_at"),
                            resultSet.getString("actor_name"),
                            resultSet.getString("world"),
                            resultSet.getInt("x"),
                            resultSet.getInt("y"),
                            resultSet.getInt("z"),
                            ChangeAction.valueOf(resultSet.getString("action")),
                            resultSet.getString("before_data"),
                            resultSet.getString("after_data")
                    ));
                }
            }
            return rows;
        }
    }

    private List<RollbackTarget> selectRollbackTargets(String worldName, int centerX, int centerZ, int radius, long targetTimestamp) throws SQLException {
        String sql = """
                SELECT x, y, z, before_data
                FROM block_changes
                WHERE world = ?
                  AND happened_at > ?
                  AND x BETWEEN ? AND ?
                  AND z BETWEEN ? AND ?
                ORDER BY happened_at ASC, id ASC
                """;

        int radiusSquared = radius * radius;
        Map<String, RollbackTarget> earliestChangePerBlock = new HashMap<>();

        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, worldName);
            statement.setLong(2, targetTimestamp);
            statement.setInt(3, centerX - radius);
            statement.setInt(4, centerX + radius);
            statement.setInt(5, centerZ - radius);
            statement.setInt(6, centerZ + radius);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    int x = resultSet.getInt("x");
                    int y = resultSet.getInt("y");
                    int z = resultSet.getInt("z");
                    int dx = x - centerX;
                    int dz = z - centerZ;
                    if ((dx * dx) + (dz * dz) > radiusSquared) {
                        continue;
                    }

                    String key = x + ":" + y + ":" + z;
                    earliestChangePerBlock.putIfAbsent(key, new RollbackTarget(
                            worldName,
                            x,
                            y,
                            z,
                            Objects.requireNonNull(resultSet.getString("before_data"))
                    ));
                }
            }
        }

        return new ArrayList<>(earliestChangePerBlock.values());
    }

    private static final class FragGuardThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "FragGuard-Database");
            thread.setDaemon(true);
            return thread;
        }
    }
}
