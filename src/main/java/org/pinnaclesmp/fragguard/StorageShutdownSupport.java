package org.pinnaclesmp.fragguard;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class StorageShutdownSupport {
    private StorageShutdownSupport() {
    }

    static StorageShutdownReport finish(File databaseFile, DatabaseHealth before, DatabaseHealth after) {
        WalCheckpointResult checkpoint = checkpointWal(databaseFile);
        int drainedWrites = Math.max(0, before.queuedWrites() - after.queuedWrites());
        long lostDuringShutdown = Math.max(0L, after.droppedWrites() - before.droppedWrites());
        return new StorageShutdownReport(
                before.queuedWrites(),
                drainedWrites,
                after.queuedWrites(),
                after.queuedOperations(),
                lostDuringShutdown,
                after.droppedWrites(),
                after.storageAvailable(),
                checkpoint
        );
    }

    static WalCheckpointResult checkpointWal(File databaseFile) {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            return new WalCheckpointResult(false, 0, 0, 0,
                    "SQLite JDBC driver is unavailable: " + exception.getMessage());
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            if (!rows.next()) {
                return new WalCheckpointResult(false, 0, 0, 0,
                        "SQLite returned no WAL checkpoint result.");
            }
            int busy = rows.getInt(1);
            int logFrames = rows.getInt(2);
            int checkpointedFrames = rows.getInt(3);
            if (busy != 0) {
                return new WalCheckpointResult(false, busy, logFrames, checkpointedFrames,
                        "WAL checkpoint remained busy with " + busy + " connection(s)." );
            }
            return new WalCheckpointResult(true, busy, logFrames, checkpointedFrames, "");
        } catch (SQLException exception) {
            return new WalCheckpointResult(false, 0, 0, 0,
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }
}

record StorageShutdownReport(
        int queuedWritesAtStart,
        int drainedWrites,
        int remainingWrites,
        int remainingOperations,
        long lostDuringShutdown,
        long totalDroppedWrites,
        boolean storageAvailable,
        WalCheckpointResult checkpoint
) {
    boolean clean() {
        return remainingWrites == 0
                && remainingOperations == 0
                && lostDuringShutdown == 0
                && storageAvailable
                && checkpoint.success();
    }
}

record WalCheckpointResult(
        boolean success,
        int busyConnections,
        int logFrames,
        int checkpointedFrames,
        String error
) {
}
