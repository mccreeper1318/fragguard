package org.pinnaclesmp.fragguard;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Bounded, opt-in SQLite free-list reclamation. All operations use the existing
 * serialized database worker, outside transactions and off the Paper tick thread.
 * This never runs an in-place full VACUUM or changes an existing DB's mode.
 */
final class IncrementalSqliteReclamation {
    static final int NONE = 0;
    static final int FULL = 1;
    static final int INCREMENTAL = 2;
    static final int MAX_PAGES_PER_PASS = 128;

    record Result(int mode, long pagesBefore, long pagesAfter,
                  long freelistBefore, long freelistAfter, int requestedPages) {
        Result {
            if (pagesBefore < 0 || pagesAfter < 0 || freelistBefore < 0
                    || freelistAfter < 0 || requestedPages < 0) {
                throw new IllegalArgumentException("Negative SQLite page metric");
            }
        }

        long reclaimedPages() {
            // Actual page-count reduction, NOT an exact disk-byte saving in WAL mode.
            return Math.max(0L, pagesBefore - pagesAfter);
        }

        boolean compatible() {
            return mode == INCREMENTAL;
        }

        String summary() {
            if (!compatible()) {
                return "Skipped incremental reclamation: SQLite auto_vacuum="
                        + modeName(mode) + " (requires INCREMENTAL; no conversion attempted).";
            }
            if (requestedPages == 0) {
                return "Skipped incremental reclamation: no free-list pages available.";
            }
            return "Incremental SQLite reclamation requested up to " + requestedPages
                    + " pages, page count reduced by " + reclaimedPages()
                    + " (free-list " + freelistBefore + " -> " + freelistAfter
                    + "). Disk-file savings may lag until WAL checkpoint; "
                    + "not a guaranteed number of bytes freed.";
        }
    }

    private IncrementalSqliteReclamation() { }

    static int readMode(Connection connection) throws SQLException {
        return Math.toIntExact(pragma(connection, "auto_vacuum"));
    }

    static String modeName(int mode) {
        return switch (mode) {
            case NONE -> "NONE";
            case FULL -> "FULL";
            case INCREMENTAL -> "INCREMENTAL";
            default -> "UNKNOWN(" + mode + ")";
        };
    }

    /**
     * Only use on a physically absent DB, immediately after connection open
     * and before the first table or WAL setup. Never call on an existing file.
     */
    static void configureNewDatabase(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new SQLException("Cannot configure auto_vacuum in a transaction");
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA auto_vacuum=INCREMENTAL");
        }
        if (readMode(connection) != INCREMENTAL) {
            throw new SQLException("SQLite did not accept incremental auto_vacuum for the new database");
        }
    }

    static Result reclaim(Connection connection, int configuredPageLimit) throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new SQLException("Cannot reclaim pages inside a transaction");
        }
        int mode = readMode(connection);
        long pagesBefore = pragma(connection, "page_count");
        long freeBefore = pragma(connection, "freelist_count");
        if (mode != INCREMENTAL || freeBefore == 0) {
            return new Result(mode, pagesBefore, pagesBefore, freeBefore, freeBefore, 0);
        }
        int limit = Math.min(MAX_PAGES_PER_PASS, Math.max(1, configuredPageLimit));
        int request = (int) Math.min(freeBefore, limit);
        // Fixed numeric SQL assembled only from our capped int; no arbitrary SQL.
        // The query timeout limits a stalled SQLite statement; no full VACUUM.
        // sqlite-jdbc's Statement.execute() advances PRAGMA incremental_vacuum
        // only once; executeUpdate() drains all requested vacuum steps before
        // closing the statement, allowing the configured page budget to work.
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(3);
            statement.executeUpdate("PRAGMA incremental_vacuum(" + request + ")");
        }
        long pagesAfter = pragma(connection, "page_count");
        long freeAfter = pragma(connection, "freelist_count");
        return new Result(mode, pagesBefore, pagesAfter, freeBefore, freeAfter, request);
    }

    private static long pragma(Connection connection, String name) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA " + name)) {
            if (!result.next()) throw new SQLException("SQLite returned no PRAGMA " + name);
            return result.getLong(1);
        }
    }
}
