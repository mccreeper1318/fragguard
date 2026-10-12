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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Read-only, bounded lookup path used by the inventory GUI.
 *
 * <p>List and activity queries never load block-entity BLOBs. Exact event payloads are loaded
 * separately only after an operator opens one exact event.</p>
 */
final class GuiLookupStore {
    private static final String ACTOR_IDENTITY = """
            COALESCE(NULLIF(TRIM(actor_uuid), ''),
                     'system:' || COALESCE(NULLIF(TRIM(actor_name), ''), '<unknown>'))
            """.trim();
    private static final String BEFORE_BASE = """
            CASE WHEN instr(before_data, '[') > 0
                 THEN substr(before_data, 1, instr(before_data, '[') - 1)
                 ELSE before_data END
            """.trim();
    private static final String AFTER_BASE = """
            CASE WHEN instr(after_data, '[') > 0
                 THEN substr(after_data, 1, instr(after_data, '[') - 1)
                 ELSE after_data END
            """.trim();
    private static final String MATERIAL_KEY = """
            CASE
              WHEN (%1$s) IN ('air','minecraft:air','cave_air','minecraft:cave_air','void_air','minecraft:void_air')
                   AND (%2$s) NOT IN ('air','minecraft:air','cave_air','minecraft:cave_air','void_air','minecraft:void_air')
                THEN (%2$s)
              WHEN (%2$s) IN ('air','minecraft:air','cave_air','minecraft:cave_air','void_air','minecraft:void_air')
                   AND (%1$s) NOT IN ('air','minecraft:air','cave_air','minecraft:cave_air','void_air','minecraft:void_air')
                THEN (%1$s)
              ELSE (%1$s)
            END
            """.formatted(AFTER_BASE, BEFORE_BASE).trim();
    private static final String AREA_FILTER = """
            rollback_pending = 0
            AND id <= ?
            AND world_uuid IN (?, ?)
            AND (world_uuid = ? OR world = ?)
            AND happened_at >= ?
            AND happened_at <= ?
            AND %s
            AND %s
            AND x BETWEEN ? AND ?
            AND z BETWEEN ? AND ?
            AND ((CAST(x AS INTEGER) - ?) * (CAST(x AS INTEGER) - ?)
               + (CAST(z AS INTEGER) - ?) * (CAST(z AS INTEGER) - ?)) <= ?
            """;
    private static final String LIGHTWEIGHT_COLUMNS = """
            id, happened_at, actor_uuid, actor_name, world, x, y, z, action, before_data, after_data,
            CASE WHEN before_entity_data IS NULL AND after_entity_data IS NULL
                 THEN 0 ELSE 1 END AS has_block_entity_data
            """;
    private static final List<String> KNOWN_ACTION_IDS = Arrays.stream(ChangeAction.values())
            .filter(action -> action != ChangeAction.UNKNOWN)
            .flatMap(action -> java.util.stream.Stream.of(action.storageId(), action.legacyStorageId()))
            .distinct()
            .toList();

    private final File databaseFile;
    private final int queryTimeoutSeconds;
    private final Logger logger;
    private final boolean explainQueries;
    private final ThreadLocal<QueryCancellation> activeRequest = new ThreadLocal<>();

    GuiLookupStore(File dataFolder, int queryTimeoutSeconds) {
        this(dataFolder, queryTimeoutSeconds, Logger.getLogger("FragGuard"), false);
    }

    GuiLookupStore(File dataFolder, int queryTimeoutSeconds, Logger logger) {
        this(dataFolder, queryTimeoutSeconds, logger, false);
    }

    GuiLookupStore(File dataFolder, int queryTimeoutSeconds, Logger logger, boolean explainQueries) {
        this.databaseFile = new File(dataFolder, "fragguard.db");
        this.queryTimeoutSeconds = Math.max(1, queryTimeoutSeconds);
        this.logger = logger;
        this.explainQueries = explainQueries;
    }

    /**
     * SQLite's JDBC statement timeout does not cover every stage of a read:
     * connection setup, worker queueing and repeated grouped-page queries can
     * leave a GUI waiting without a deadline or server-side diagnostic.
     */
    <T> CompletableFuture<T> boundedQueryAsync(String phase, Supplier<T> task) {
        QueryCancellation cancellation = new QueryCancellation();
        long started = System.nanoTime();
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            activeRequest.set(cancellation);
            try {
                if (cancellation.cancelled.get()) {
                    throw new CompletionException(new TimeoutException(
                            "GUI " + phase + " expired before execution"));
                }
                return task.get();
            } finally {
                activeRequest.remove();
            }
        });
        future.orTimeout(queryTimeoutSeconds, TimeUnit.SECONDS);
        return future.whenComplete((ignored, failure) -> {
            if (failure != null && unwrap(failure) instanceof TimeoutException) {
                cancellation.cancel();
                logger.warning("FragGuard GUI query " + phase + " exceeded "
                        + queryTimeoutSeconds + " seconds (elapsed "
                        + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                        + " ms); cancelling the read-only SQL statement");
            }
        });
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure.getCause() != null && failure.getCause() != failure) {
            failure = failure.getCause();
        }
        return failure;
    }

    private PreparedStatement readStatement(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        QueryCancellation cancellation = activeRequest.get();
        if (cancellation != null) {
            cancellation.track(statement);
        }
        return statement;
    }

    private void ensureQueryActive() {
        QueryCancellation cancellation = activeRequest.get();
        if (cancellation != null && cancellation.cancelled.get()) {
            throw new CompletionException(new TimeoutException("FragGuard GUI SQL read cancelled"));
        }
    }

    private static final class QueryCancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<PreparedStatement> statement = new AtomicReference<>();

        void track(PreparedStatement current) throws SQLException {
            statement.set(current);
            if (cancelled.get()) {
                current.cancel();
                throw new SQLException("FragGuard GUI SQL read cancelled");
            }
        }

        void cancel() {
            cancelled.set(true);
            PreparedStatement current = statement.get();
            if (current != null) {
                try {
                    current.cancel();
                } catch (SQLException ignored) {
                    // The UI already received TimeoutException; cancellation is best effort.
                }
            }
        }
    }

    private void explain(Connection connection, String phase, String sql,
                         GuiLookupQuery query, GuiLookupCursor cursor, Integer limit) {
        if (!explainQueries) {
            return;
        }
        // Bind the same area/filter/cursor values as the live SQL, but never log them.
        try (PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql)) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            int index = bindArea(statement, query);
            index = bindFilters(statement, index, query.filters());
            if (cursor != null) {
                index = bindCursorUpper(statement, index, cursor);
            }
            if (limit != null) {
                statement.setInt(index, limit);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    logger.info("FragGuard GUI EXPLAIN " + phase + ": " + rows.getString("detail"));
                }
            }
        } catch (SQLException error) {
            // Planner diagnostics cannot cause a valid history lookup to fail.
            logger.log(Level.WARNING, "FragGuard GUI could not EXPLAIN " + phase, error);
        }
    }

    private void logTiming(String phase, long started, GuiLookupQuery query) {
        logger.info("FragGuard GUI query " + phase + " completed in "
                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                + " ms [radius=" + query.radius()
                + ", windowHours=" + TimeUnit.MILLISECONDS.toHours(
                        Math.max(0L, query.snapshotTimestamp() - query.cutoffTimestamp()))
                + ", filtered=" + query.filters().active() + "]");
    }

    private void logFailure(String phase, long started, GuiLookupQuery query, SQLException error) {
        logger.log(Level.WARNING, "FragGuard GUI query " + phase + " failed after "
                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                + " ms [radius=" + query.radius() + "]", error);
    }

    CompletableFuture<GuiLookupOverview> prepareLookupAsync(GuiLookupQuery query) {
        // Retain the full eager overview for existing callers/tests that require a catalog.
        return prepareOverviewAsync(query).thenCompose(overview ->
                loadCatalogAsync(query.withMaxRowId(overview.maxRowId()))
                        .thenApply(catalog -> new GuiLookupOverview(
                                overview.totalRows(), catalog, overview.maxRowId())));
    }

    /** Count exactly once; do not block the first page on three full-area GROUP BY scans. */
    CompletableFuture<GuiLookupOverview> prepareOverviewAsync(GuiLookupQuery query) {
        return boundedQueryAsync("prepareOverviewAsync", () -> {
            long started = System.nanoTime();
            try (Connection connection = openReadConnection()) {
                long maxRowId = loadMaxRowId(connection);
                logTiming("snapshot-id", started, query);
                GuiLookupQuery bounded = query.withMaxRowId(maxRowId);
                long countStarted = System.nanoTime();
                long totalRows = countRows(connection, bounded);
                logTiming("initial-count rows=" + totalRows, countStarted, bounded);
                return new GuiLookupOverview(totalRows, LookupFilters.Catalog.empty(), maxRowId);
            } catch (SQLException exception) {
                logFailure("initial-overview", started, query, exception);
                throw new CompletionException(exception);
            }
        });
    }

    /** Filter catalogs are loaded only when an operator opens the filter UI. */
    CompletableFuture<LookupFilters.Catalog> loadCatalogAsync(GuiLookupQuery query) {
        return boundedQueryAsync("loadCatalogAsync", () -> {
            long started = System.nanoTime();
            try (Connection connection = openReadConnection()) {
                LookupFilters.Catalog catalog = loadCatalog(
                        connection, query.withFilters(LookupFilters.State.empty()));
                logTiming("filter-catalog", started, query);
                return catalog;
            } catch (SQLException exception) {
                logFailure("filter-catalog", started, query, exception);
                throw new CompletionException(exception);
            }
        });
    }

    CompletableFuture<Long> countRowsAsync(GuiLookupQuery query) {
        return boundedQueryAsync("countRowsAsync", () -> {
            long started = System.nanoTime();
            try (Connection connection = openReadConnection()) {
                long count = countRows(connection, query);
                logTiming("filtered-count rows=" + count, started, query);
                return count;
            } catch (SQLException exception) {
                logFailure("filtered-count", started, query, exception);
                throw new CompletionException(exception);
            }
        });
    }

    CompletableFuture<GuiRawPage> selectRawPageAsync(
            GuiLookupQuery query,
            GuiLookupCursor startInclusive,
            int pageSize
    ) {
        return boundedQueryAsync("selectRawPageAsync", () -> {
            try (Connection connection = openReadConnection()) {
                List<LookupRow> fetched = selectRows(connection, query, startInclusive,
                        Math.max(1, pageSize) + 1);
                int visibleCount = Math.min(Math.max(1, pageSize), fetched.size());
                List<LookupRow> visible = List.copyOf(fetched.subList(0, visibleCount));
                GuiLookupCursor next = fetched.size() > visibleCount
                        ? GuiLookupCursor.of(fetched.get(visibleCount))
                        : null;
                return new GuiRawPage(visible, next);
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    CompletableFuture<GuiActivityPage> selectActivityPageAsync(
            GuiLookupQuery query,
            GuiLookupCursor startInclusive,
            int activityPageSize,
            int fetchSize,
            long maxGapMillis,
            int maxDistance,
            long maxDurationMillis,
            int maxSpan
    ) {
        return boundedQueryAsync("selectActivityPageAsync", () -> {
            long started = System.nanoTime();
            int pageSize = Math.max(1, activityPageSize);
            int chunkSize = Math.max(1, fetchSize);
            int fetchedRows = 0;
            int batches = 0;
            try (Connection connection = openReadConnection()) {
                List<GuiActivitySummary> finished = new ArrayList<>(pageSize);
                ActivityAccumulator current = null;
                GuiLookupCursor cursor = startInclusive;

                while (true) {
                    ensureQueryActive();
                    List<LookupRow> fetched = selectRows(connection, query, cursor, chunkSize + 1);
                    batches++;
                    int processCount = Math.min(chunkSize, fetched.size());
                    fetchedRows += processCount;
                    for (int index = 0; index < processCount; index++) {
                        LookupRow row = fetched.get(index);
                        if (current == null) {
                            current = new ActivityAccumulator(row);
                            continue;
                        }
                        if (current.canAppend(row, maxGapMillis, maxDistance, maxDurationMillis, maxSpan)) {
                            current.append(row);
                            continue;
                        }

                        finished.add(current.freeze());
                        if (finished.size() >= pageSize) {
                            logTiming("condensed-page batches=" + batches + " scanned=" + fetchedRows
                                    + " activities=" + finished.size(), started, query);
                            return new GuiActivityPage(List.copyOf(finished), GuiLookupCursor.of(row));
                        }
                        current = new ActivityAccumulator(row);
                    }

                    if (fetched.size() <= processCount) {
                        if (current != null) {
                            finished.add(current.freeze());
                        }
                        logTiming("condensed-page batches=" + batches + " scanned=" + fetchedRows
                                + " activities=" + finished.size(), started, query);
                        return new GuiActivityPage(List.copyOf(finished), null);
                    }
                    cursor = GuiLookupCursor.of(fetched.get(processCount));
                }
            } catch (SQLException exception) {
                logFailure("condensed-page batches=" + batches + " scanned=" + fetchedRows,
                        started, query, exception);
                throw new CompletionException(exception);
            }
        });
    }

    CompletableFuture<GuiActivityRawPage> selectActivityRowsPageAsync(
            GuiLookupQuery query,
            GuiActivitySummary activity,
            GuiLookupCursor startInclusive,
            int pageSize
    ) {
        return boundedQueryAsync("selectActivityRowsPageAsync", () -> {
            int boundedPageSize = Math.max(1, pageSize);
            StringBuilder sql = new StringBuilder("""
                    SELECT %s
                    FROM block_changes
                    WHERE %s
                    """.formatted(LIGHTWEIGHT_COLUMNS, areaFilter(query)));
            appendFilterSql(sql, query.filters());
            sql.append("""
                    
                    AND (happened_at < ? OR (happened_at = ? AND id <= ?))
                    AND (happened_at > ? OR (happened_at = ? AND id >= ?))
                    """);
            if (startInclusive != null) {
                sql.append("""
                        
                        AND (happened_at < ? OR (happened_at = ? AND id <= ?))
                        """);
            }
            sql.append("\nORDER BY happened_at DESC, id DESC\nLIMIT ?");

            try (Connection connection = openReadConnection();
                 PreparedStatement statement = readStatement(connection,sql.toString())) {
                statement.setQueryTimeout(queryTimeoutSeconds);
                int index = bindArea(statement, query);
                index = bindFilters(statement, index, query.filters());
                index = bindCursorUpper(statement, index, activity.newestCursor());
                index = bindCursorLower(statement, index, activity.oldestCursor());
                if (startInclusive != null) {
                    index = bindCursorUpper(statement, index, startInclusive);
                }
                statement.setInt(index, boundedPageSize + 1);

                List<LookupRow> fetched = readRows(statement);
                int visibleCount = Math.min(boundedPageSize, fetched.size());
                List<LookupRow> visible = List.copyOf(fetched.subList(0, visibleCount));
                GuiLookupCursor next = fetched.size() > visibleCount
                        ? GuiLookupCursor.of(fetched.get(visibleCount))
                        : null;
                return new GuiActivityRawPage(visible, next);
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    /**
     * Legacy bounded helper retained for focused tests and callers that explicitly request a finite row set.
     * The live GUI no longer uses this method as a total-result cap.
     */
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
        GuiLookupQuery query = new GuiLookupQuery(
                worldUuid, worldName, centerX, centerZ, radius,
                cutoffTimestamp, snapshotTimestamp, LookupFilters.State.empty());
        return boundedQueryAsync("selectRowsAsync", () -> {
            try (Connection connection = openReadConnection()) {
                return List.copyOf(selectRows(connection, query, null, Math.max(1, rowLimit)));
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    CompletableFuture<LookupEventPayload> loadEventPayloadAsync(long rowId) {
        return boundedQueryAsync("loadEventPayloadAsync", () -> {
            try (Connection connection = openReadConnection();
                 PreparedStatement statement = readStatement(connection,"""
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

    private long loadMaxRowId(Connection connection) throws SQLException {
        try (PreparedStatement statement = readStatement(connection,
                "SELECT COALESCE(MAX(id), 0) FROM block_changes")) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    private long countRows(Connection connection, GuiLookupQuery query) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM block_changes WHERE ").append(areaFilter(query));
        appendFilterSql(sql, query.filters());
        explain(connection, "count", sql.toString(), query, null, null);
        try (PreparedStatement statement = readStatement(connection, sql.toString())) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            int index = bindArea(statement, query);
            bindFilters(statement, index, query.filters());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    private LookupFilters.Catalog loadCatalog(Connection connection, GuiLookupQuery unfiltered) throws SQLException {
        Map<String, PlayerAggregate> players = new LinkedHashMap<>();
        Map<ChangeAction, Long> actions = new LinkedHashMap<>();
        Map<String, Long> materials = new LinkedHashMap<>();

        String playerSql = """
                SELECT %s AS actor_identity,
                       MAX(COALESCE(NULLIF(TRIM(actor_name), ''), 'Unknown')) AS actor_label,
                       COUNT(*) AS event_count
                FROM block_changes
                WHERE %s
                GROUP BY actor_identity
                """.formatted(ACTOR_IDENTITY, areaFilter(unfiltered));
        explain(connection, "catalog-player", playerSql, unfiltered, null, null);
        long playerStarted = System.nanoTime();
        try (PreparedStatement statement = readStatement(connection, playerSql)) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            bindArea(statement, unfiltered);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    players.put(rows.getString("actor_identity"),
                            new PlayerAggregate(rows.getString("actor_label"), rows.getLong("event_count")));
                }
            }
        }

        logTiming("catalog-players groups=" + players.size(), playerStarted, unfiltered);

        String actionSql = """
                SELECT action, COUNT(*) AS event_count
                FROM block_changes
                WHERE %s
                GROUP BY action
                """.formatted(areaFilter(unfiltered));
        explain(connection, "catalog-action", actionSql, unfiltered, null, null);
        long actionStarted = System.nanoTime();
        try (PreparedStatement statement = readStatement(connection, actionSql)) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            bindArea(statement, unfiltered);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ChangeAction action = ChangeAction.fromStorageId(rows.getString("action"));
                    actions.merge(action, rows.getLong("event_count"), Long::sum);
                }
            }
        }

        logTiming("catalog-actions groups=" + actions.size(), actionStarted, unfiltered);

        String materialSql = """
                SELECT %s AS material_key, COUNT(*) AS event_count
                FROM block_changes
                WHERE %s
                GROUP BY material_key
                """.formatted(MATERIAL_KEY, areaFilter(unfiltered));
        explain(connection, "catalog-material", materialSql, unfiltered, null, null);
        long materialStarted = System.nanoTime();
        try (PreparedStatement statement = readStatement(connection, materialSql)) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            bindArea(statement, unfiltered);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    materials.merge(rows.getString("material_key"), rows.getLong("event_count"), Long::sum);
                }
            }
        }

        logTiming("catalog-materials groups=" + materials.size(), materialStarted, unfiltered);

        Comparator<LookupFilters.Option> byLabel = Comparator
                .comparing(LookupFilters.Option::label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(LookupFilters.Option::key, Comparator.nullsFirst(String::compareTo));

        List<LookupFilters.Option> playerOptions = players.entrySet().stream()
                .map(entry -> new LookupFilters.Option(
                        entry.getKey(), entry.getValue().label(), safeCount(entry.getValue().count())))
                .sorted(byLabel)
                .toList();
        List<LookupFilters.Option> actionOptions = actions.entrySet().stream()
                .map(entry -> new LookupFilters.Option(
                        entry.getKey().storageId(), displayAction(entry.getKey()), safeCount(entry.getValue())))
                .sorted(byLabel)
                .toList();
        List<LookupFilters.Option> materialOptions = materials.entrySet().stream()
                .map(entry -> new LookupFilters.Option(
                        entry.getKey(), LookupActivityGrouper.displayMaterial(entry.getKey()), safeCount(entry.getValue())))
                .sorted(byLabel)
                .toList();
        return new LookupFilters.Catalog(playerOptions, actionOptions, materialOptions);
    }

    private List<LookupRow> selectRows(
            Connection connection,
            GuiLookupQuery query,
            GuiLookupCursor startInclusive,
            int limit
    ) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT %s
                FROM block_changes
                WHERE %s
                """.formatted(LIGHTWEIGHT_COLUMNS, AREA_FILTER));
        appendFilterSql(sql, query.filters());
        if (startInclusive != null) {
            sql.append("""
                    
                    AND (happened_at < ? OR (happened_at = ? AND id <= ?))
                    """);
        }
        sql.append("\nORDER BY happened_at DESC, id DESC\nLIMIT ?");

        explain(connection, "page-" + (startInclusive == null ? "first" : "next"),
                sql.toString(), query, startInclusive, Math.max(1, limit));
        try (PreparedStatement statement = readStatement(connection, sql.toString())) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            int index = bindArea(statement, query);
            index = bindFilters(statement, index, query.filters());
            if (startInclusive != null) {
                index = bindCursorUpper(statement, index, startInclusive);
            }
            statement.setInt(index, Math.max(1, limit));
            return readRows(statement);
        }
    }

    private List<LookupRow> readRows(PreparedStatement statement) throws SQLException {
        List<LookupRow> rows = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                ensureQueryActive();
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
        return rows;
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

    // Index idx_fg_spatial_time begins with (world_uuid, chunk_x, chunk_z, happened_at).
    // BETWEEN on chunk_x is a range and prevents SQLite using equality on chunk_z
    // and the timestamp suffix of that composite index. Enumerating the few
    // intersected chunk coordinates as IN lists makes both chunk dimensions
    // usable for indexed seeks even when the world database contains millions
    // of records outside the requested radius.
    private static final long MAX_INDEXED_CHUNK_VALUES = 64L;

    private static long minChunk(int center, int radius) {
        return Math.floorDiv((long) center - radius, 16L);
    }

    private static long maxChunk(int center, int radius) {
        return Math.floorDiv((long) center + radius, 16L);
    }

    private static boolean enumerateChunks(long first, long last) {
        return last >= first && last - first < MAX_INDEXED_CHUNK_VALUES;
    }

    private static String chunkPredicate(String column, long first, long last) {
        if (!enumerateChunks(first, last)) {
            return column + " BETWEEN ? AND ?";
        }
        return column + " IN (" + "?,".repeat((int) (last - first)) + "?)";
    }

    private String areaFilter(GuiLookupQuery query) {
        return AREA_FILTER.formatted(
                chunkPredicate("chunk_x",
                        minChunk(query.centerX(), query.radius()),
                        maxChunk(query.centerX(), query.radius())),
                chunkPredicate("chunk_z",
                        minChunk(query.centerZ(), query.radius()),
                        maxChunk(query.centerZ(), query.radius())));
    }

    private static int bindChunkPredicate(PreparedStatement statement, int index,
                                          long first, long last) throws SQLException {
        if (!enumerateChunks(first, last)) {
            statement.setLong(index++, first);
            statement.setLong(index++, last);
            return index;
        }
        for (long value = first; value <= last; value++) {
            statement.setLong(index++, value);
        }
        return index;
    }

    private int bindArea(PreparedStatement statement, GuiLookupQuery query) throws SQLException {
        long minX = (long) query.centerX() - query.radius();
        long maxX = (long) query.centerX() + query.radius();
        long minZ = (long) query.centerZ() - query.radius();
        long maxZ = (long) query.centerZ() + query.radius();
        int index = 1;
        statement.setLong(index++, query.maxRowId());
        statement.setString(index++, query.worldUuid());
        statement.setString(index++, query.worldName());
        statement.setString(index++, query.worldUuid());
        statement.setString(index++, query.worldName());
        statement.setLong(index++, query.cutoffTimestamp());
        statement.setLong(index++, query.snapshotTimestamp());
        index = bindChunkPredicate(statement, index,
                Math.floorDiv(minX, 16L), Math.floorDiv(maxX, 16L));
        index = bindChunkPredicate(statement, index,
                Math.floorDiv(minZ, 16L), Math.floorDiv(maxZ, 16L));
        statement.setLong(index++, minX);
        statement.setLong(index++, maxX);
        statement.setLong(index++, minZ);
        statement.setLong(index++, maxZ);
        statement.setInt(index++, query.centerX());
        statement.setInt(index++, query.centerX());
        statement.setInt(index++, query.centerZ());
        statement.setInt(index++, query.centerZ());
        statement.setLong(index++, (long) query.radius() * query.radius());
        return index;
    }

    private void appendFilterSql(StringBuilder sql, LookupFilters.State filters) {
        if (filters.actorIdentity() != null) {
            sql.append("\nAND (").append(ACTOR_IDENTITY).append(") = ?");
        }
        if (filters.action() != null) {
            if (filters.action() == ChangeAction.UNKNOWN) {
                sql.append("\nAND (action IN ('unknown','UNKNOWN') OR action NOT IN (");
                for (int i = 0; i < KNOWN_ACTION_IDS.size(); i++) {
                    if (i > 0) {
                        sql.append(',');
                    }
                    sql.append('?');
                }
                sql.append("))");
            } else {
                sql.append("\nAND (action = ? OR action = ?)");
            }
        }
        if (filters.materialKey() != null) {
            sql.append("\nAND (").append(MATERIAL_KEY).append(") = ?");
        }
    }

    private int bindFilters(
            PreparedStatement statement,
            int index,
            LookupFilters.State filters
    ) throws SQLException {
        if (filters.actorIdentity() != null) {
            statement.setString(index++, filters.actorIdentity());
        }
        if (filters.action() != null) {
            if (filters.action() == ChangeAction.UNKNOWN) {
                for (String id : KNOWN_ACTION_IDS) {
                    statement.setString(index++, id);
                }
            } else {
                statement.setString(index++, filters.action().storageId());
                statement.setString(index++, filters.action().legacyStorageId());
            }
        }
        if (filters.materialKey() != null) {
            statement.setString(index++, filters.materialKey());
        }
        return index;
    }

    private int bindCursorUpper(PreparedStatement statement, int index, GuiLookupCursor cursor)
            throws SQLException {
        statement.setLong(index++, cursor.happenedAt());
        statement.setLong(index++, cursor.happenedAt());
        statement.setLong(index++, cursor.id());
        return index;
    }

    private int bindCursorLower(PreparedStatement statement, int index, GuiLookupCursor cursor)
            throws SQLException {
        statement.setLong(index++, cursor.happenedAt());
        statement.setLong(index++, cursor.happenedAt());
        statement.setLong(index++, cursor.id());
        return index;
    }

    private static int safeCount(long count) {
        return count >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, count);
    }

    private static String displayAction(ChangeAction action) {
        String[] words = action.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder display = new StringBuilder();
        for (String word : words) {
            if (display.length() > 0) {
                display.append(' ');
            }
            display.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return display.toString();
    }

    private static final class ActivityAccumulator {
        private final String actorIdentity;
        private final String actorName;
        private final ChangeAction action;
        private final String materialKey;
        private final GuiLookupCursor newestCursor;
        private GuiLookupCursor oldestCursor;
        private long newestAt;
        private long oldestAt;
        private int minX;
        private int minY;
        private int minZ;
        private int maxX;
        private int maxY;
        private int maxZ;
        private int lastX;
        private int lastY;
        private int lastZ;
        private int eventCount;

        private ActivityAccumulator(LookupRow first) {
            actorIdentity = first.actorIdentity();
            actorName = first.actorName();
            action = first.action();
            materialKey = LookupActivityGrouper.materialKey(first);
            newestCursor = GuiLookupCursor.of(first);
            oldestCursor = newestCursor;
            newestAt = oldestAt = first.happenedAt();
            minX = maxX = lastX = first.x();
            minY = maxY = lastY = first.y();
            minZ = maxZ = lastZ = first.z();
            eventCount = 1;
        }

        private boolean canAppend(
                LookupRow row,
                long maxGapMillis,
                int maxDistance,
                long maxDurationMillis,
                int maxSpan
        ) {
            if (!actorIdentity.equals(row.actorIdentity())
                    || action != row.action()
                    || !materialKey.equals(LookupActivityGrouper.materialKey(row))) {
                return false;
            }

            long gap = Math.max(0L, oldestAt - row.happenedAt());
            long duration = Math.max(0L, newestAt - row.happenedAt());
            if (gap > Math.max(0L, maxGapMillis)
                    || duration > Math.max(0L, maxDurationMillis)
                    || Math.abs((long) row.x() - lastX) > Math.max(0, maxDistance)
                    || Math.abs((long) row.y() - lastY) > Math.max(0, maxDistance)
                    || Math.abs((long) row.z() - lastZ) > Math.max(0, maxDistance)) {
                return false;
            }

            int proposedMinX = Math.min(minX, row.x());
            int proposedMinY = Math.min(minY, row.y());
            int proposedMinZ = Math.min(minZ, row.z());
            int proposedMaxX = Math.max(maxX, row.x());
            int proposedMaxY = Math.max(maxY, row.y());
            int proposedMaxZ = Math.max(maxZ, row.z());
            int span = Math.max(0, maxSpan);
            return (long) proposedMaxX - proposedMinX <= span
                    && (long) proposedMaxY - proposedMinY <= span
                    && (long) proposedMaxZ - proposedMinZ <= span;
        }

        private void append(LookupRow row) {
            oldestCursor = GuiLookupCursor.of(row);
            oldestAt = Math.min(oldestAt, row.happenedAt());
            minX = Math.min(minX, row.x());
            minY = Math.min(minY, row.y());
            minZ = Math.min(minZ, row.z());
            maxX = Math.max(maxX, row.x());
            maxY = Math.max(maxY, row.y());
            maxZ = Math.max(maxZ, row.z());
            lastX = row.x();
            lastY = row.y();
            lastZ = row.z();
            eventCount++;
        }

        private GuiActivitySummary freeze() {
            return new GuiActivitySummary(
                    actorIdentity, actorName, action, materialKey,
                    newestAt, oldestAt,
                    minX, minY, minZ, maxX, maxY, maxZ,
                    eventCount, newestCursor, oldestCursor
            );
        }
    }

    private record PlayerAggregate(String label, long count) {
    }
}

record GuiLookupQuery(
        String worldUuid,
        String worldName,
        int centerX,
        int centerZ,
        int radius,
        long cutoffTimestamp,
        long snapshotTimestamp,
        LookupFilters.State filters,
        long maxRowId
) {
    GuiLookupQuery(
            String worldUuid,
            String worldName,
            int centerX,
            int centerZ,
            int radius,
            long cutoffTimestamp,
            long snapshotTimestamp,
            LookupFilters.State filters
    ) {
        this(worldUuid, worldName, centerX, centerZ, radius,
                cutoffTimestamp, snapshotTimestamp, filters, Long.MAX_VALUE);
    }

    GuiLookupQuery {
        filters = filters == null ? LookupFilters.State.empty() : filters;
        maxRowId = Math.max(0L, maxRowId);
    }

    GuiLookupQuery withFilters(LookupFilters.State nextFilters) {
        return new GuiLookupQuery(
                worldUuid, worldName, centerX, centerZ, radius,
                cutoffTimestamp, snapshotTimestamp, nextFilters, maxRowId);
    }

    GuiLookupQuery withMaxRowId(long nextMaxRowId) {
        return new GuiLookupQuery(
                worldUuid, worldName, centerX, centerZ, radius,
                cutoffTimestamp, snapshotTimestamp, filters, nextMaxRowId);
    }
}

record GuiLookupCursor(long happenedAt, long id) {
    static GuiLookupCursor of(LookupRow row) {
        return new GuiLookupCursor(row.happenedAt(), row.id());
    }
}

record GuiLookupOverview(long totalRows, LookupFilters.Catalog catalog, long maxRowId) {
}

record GuiRawPage(List<LookupRow> rows, GuiLookupCursor nextCursor) {
    GuiRawPage {
        rows = List.copyOf(rows);
    }

    boolean hasMore() {
        return nextCursor != null;
    }
}

record GuiActivitySummary(
        String actorIdentity,
        String actorName,
        ChangeAction action,
        String materialKey,
        long newestAt,
        long oldestAt,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ,
        int eventCount,
        GuiLookupCursor newestCursor,
        GuiLookupCursor oldestCursor
) {
}

record GuiActivityPage(List<GuiActivitySummary> activities, GuiLookupCursor nextCursor) {
    GuiActivityPage {
        activities = List.copyOf(activities);
    }

    boolean hasMore() {
        return nextCursor != null;
    }
}

record GuiActivityRawPage(List<LookupRow> rows, GuiLookupCursor nextCursor) {
    GuiActivityRawPage {
        rows = List.copyOf(rows);
    }

    boolean hasMore() {
        return nextCursor != null;
    }
}

record LookupEventPayload(byte[] beforeEntityData, byte[] afterEntityData) {
    boolean changed() {
        return !Arrays.equals(beforeEntityData, afterEntityData);
    }
}
