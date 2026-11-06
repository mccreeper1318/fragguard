package org.pinnaclesmp.fragguard;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.function.Consumer;

/** Diagnostic-only instrumentation for the schema-v4 historical index migration. */
final class HistoricalIndexMigrationDiagnostics {
    private HistoricalIndexMigrationDiagnostics() { }

    static void createIndex(Connection connection, File databaseFile, String indexName, String sql,
                            Consumer<String> info, Consumer<String> warning) throws SQLException {
        createIndex(connection, databaseFile, "Schema v4->v5", indexName, sql, info, warning);
    }

    static void createIndex(Connection connection, File databaseFile, String migrationStage,
                            String indexName, String sql,
                            Consumer<String> info, Consumer<String> warning) throws SQLException {
        long start = System.nanoTime();
        String before = snapshot(connection, databaseFile);
        info.accept(migrationStage + " index " + indexName + ": starting; " + before);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException failure) {
            String error = sqlError(failure);
            String diagnostic = migrationStage + " index " + indexName + " FAILED after "
                    + elapsedSeconds(start) + "s (" + error + "); before: " + before
                    + "; after: " + snapshot(connection, databaseFile) + ". "
                    + "Reported filesystem capacity is NOT an account quota. "
                    + "Possible causes include database/WAL growth, SQLite-native temp storage, "
                    + "and container/hosting limits; this report does not establish which caused the failure.";
            warning.accept(diagnostic);
            throw new SQLException("FragGuard " + migrationStage + " failed while creating index " + indexName
                    + " after " + elapsedSeconds(start) + "s (" + error + "). "
                    + "See the preceding storage diagnostics; the root cause is not yet confirmed.",
                    failure.getSQLState(), failure.getErrorCode(), failure);
        }
        info.accept(migrationStage + " index " + indexName + ": completed in " + elapsedSeconds(start)
                + "s; " + snapshot(connection, databaseFile));
    }

    static String snapshot(Connection connection, File databaseFile) {
        File dataDirectory = databaseFile.getParentFile();
        String javaTemp = System.getProperty("java.io.tmpdir", "");
        File javaTempDirectory = javaTemp.isBlank() ? null : new File(javaTemp);
        return "db=" + size(databaseFile)
                + ", wal=" + size(new File(databaseFile.getPath() + "-wal"))
                + ", shm=" + size(new File(databaseFile.getPath() + "-shm"))
                + ", journal=" + size(new File(databaseFile.getPath() + "-journal"))
                + ", data-fs-host-usable=" + usable(dataDirectory)
                + ", java-temp-fs-host-usable=" + usable(javaTempDirectory)
                + ", SQLITE_TMPDIR-set=" + envPresent("SQLITE_TMPDIR")
                + ", TMPDIR-set=" + envPresent("TMPDIR")
                + ", temp_store=" + pragma(connection, "temp_store")
                + ", compile-TEMP_STORE=" + compileTempStore(connection)
                + ", journal_mode=" + pragma(connection, "journal_mode")
                + ", page_count=" + pragma(connection, "page_count")
                + ", freelist_count=" + pragma(connection, "freelist_count")
                + ", max_page_count=" + pragma(connection, "max_page_count")
                + ", user_version=" + pragma(connection, "user_version")
                + ", native-temp-candidates={" + SqliteTempStorageProbe.describe(databaseFile) + "}"
                + ", sqlite-native-temp-dir=unresolved (Java tmpdir is not authoritative)";
    }

    private static String sqlError(SQLException error) {
        int code = error.getErrorCode();
        String name = code == 13 || error.getMessage() != null && error.getMessage().contains("SQLITE_FULL")
                ? "SQLITE_FULL" : "SQLite error";
        return name + ", code=" + code + ", SQLState="
                + (error.getSQLState() == null ? "unknown" : error.getSQLState());
    }

    private static String size(File file) {
        if (!file.isFile()) return "absent";
        return MigrationStoragePolicy.formatBytes(file.length());
    }

    private static String usable(File directory) {
        if (directory == null || !directory.isDirectory()) return "unknown";
        long bytes = directory.getUsableSpace();
        return bytes <= 0L ? "unknown" : MigrationStoragePolicy.formatBytes(bytes);
    }

    private static boolean envPresent(String name) {
        try {
            String value = System.getenv(name);
            return value != null && !value.isBlank();
        } catch (SecurityException ignored) {
            return false;
        }
    }

    private static String pragma(Connection connection, String fixedPragma) {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA " + fixedPragma)) {
            return result.next() ? result.getString(1) : "unavailable";
        } catch (SQLException e) {
            return "unavailable";
        }
    }

    private static String compileTempStore(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA compile_options")) {
            while (result.next()) {
                String option = result.getString(1);
                if (option != null && option.startsWith("TEMP_STORE=")) return option.substring("TEMP_STORE=".length());
            }
        } catch (SQLException ignored) {
            // Compilation metadata is best-effort and must not alter migration success/failure.
        }
        return "unknown";
    }

    private static String elapsedSeconds(long start) {
        return String.format(Locale.ROOT, "%.2f", (System.nanoTime() - start) / 1_000_000_000d);
    }
}
