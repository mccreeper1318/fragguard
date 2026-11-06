package org.pinnaclesmp.fragguard;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Read-only, timeout-bounded GUI queries for globally scoped player history.
 * No event BLOBs are included in pages or identity selection; exact payloads
 * are loaded by GuiLookupStore when the admin opens a single event.
 */
final class GuiPlayerHistoryStore {
    static final int PLAYER_PAGE_SIZE = 45;
    static final int EVENT_PAGE_SIZE = 36;
    private static final String BASE_MATERIAL = """
            COALESCE(NULLIF(CASE WHEN instr(before_data, '[') > 0
                 THEN substr(before_data, 1, instr(before_data, '[') - 1)
                 ELSE before_data END, ''), 'minecraft:air')
            """;
    private static final String AFTER_MATERIAL = """
            COALESCE(NULLIF(CASE WHEN instr(after_data, '[') > 0
                 THEN substr(after_data, 1, instr(after_data, '[') - 1)
                 ELSE after_data END, ''), 'minecraft:air')
            """;
    // Match LookupActivityGrouper.materialKey: prefer the resulting block
    // unless the result is air and the previous block was non-air. This
    // includes changes between two different solid materials (e.g. logs
    // becoming stripped logs), not just placement and removal.
    private static final String AIR_MATERIALS = """
            ('air','minecraft:air','cave_air','minecraft:cave_air',
             'void_air','minecraft:void_air')
            """;
    private static final String MATERIAL = """
            CASE WHEN (%1$s) IN %3$s AND (%2$s) NOT IN %3$s THEN (%2$s)
                 ELSE (%1$s) END
            """.formatted(AFTER_MATERIAL, BASE_MATERIAL, AIR_MATERIALS);
    // The shared direct-actor predicate matches schema-v5 partial indexes.
    // Keep the causal branch unfiltered so TNT/piston player ownership remains visible.
    static final String OWNED = """
            rollback_pending = 0 AND happened_at >= ? AND happened_at <= ?
            AND %s
            """.formatted(Database.playerOwnershipPredicate());
    private final File databaseFile;
    private final int timeoutSeconds;
    private final Logger logger = Logger.getLogger("FragGuard GUI Player History");
    private final ThreadLocal<QueryCancellation> activeRequest = new ThreadLocal<>();

    GuiPlayerHistoryStore(File dataFolder, int timeoutSeconds) {
        this.databaseFile = new File(dataFolder, "fragguard.db");
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    record PlayerIdentity(UUID uuid, String latestName) {
        PlayerIdentity {
            if (latestName == null || latestName.isBlank()) latestName = uuid.toString();
        }
    }
    record IdentityPage(List<PlayerIdentity> identities, int page, boolean hasNext) {
        IdentityPage { identities = List.copyOf(identities); }
    }
    record HistoryQuery(UUID playerUuid, String latestName, String worldName,
                        long cutoff, long snapshot, ChangeAction action, String material) {
        HistoryQuery {
            if (playerUuid == null) throw new IllegalArgumentException("Missing player UUID");
        }
        HistoryQuery withAction(ChangeAction value) {
            return new HistoryQuery(playerUuid, latestName, worldName, cutoff, snapshot, value, material);
        }
        HistoryQuery withMaterial(String value) {
            return new HistoryQuery(playerUuid, latestName, worldName, cutoff, snapshot, action, value);
        }
    }
    record HistoryPage(List<PlayerLookupRow> rows, int page, long totalRows) {
        HistoryPage { rows = List.copyOf(rows); }
        boolean hasNext() { return (long) page * EVENT_PAGE_SIZE < totalRows; }
    }
    record FilterChoices(List<ChangeAction> actions, List<String> materials) {
        FilterChoices { actions = List.copyOf(actions); materials = List.copyOf(materials); }
    }

    CompletableFuture<IdentityPage> identitiesAsync(int page, int retentionDays) {
        int index = Math.max(0, page);
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(Math.max(1, retentionDays));
        return boundedQueryAsync("player-selector", () -> {
            try (Connection db = open(); PreparedStatement statement = prepare(db, """
                    WITH actors AS (
                        SELECT actor_uuid AS uuid, actor_name AS name, happened_at, id
                        FROM block_changes WHERE rollback_pending = 0 AND happened_at >= ?
                          AND actor_uuid IS NOT NULL AND length(actor_uuid) = 36
                          AND actor_name IS NOT NULL AND length(actor_name) BETWEEN 1 AND 16
                          AND actor_name NOT GLOB '*[^A-Za-z0-9_]*'
                        UNION ALL
                        SELECT causal_player_uuid AS uuid, causal_player_name AS name, happened_at, id
                        FROM block_changes WHERE rollback_pending = 0 AND happened_at >= ?
                          AND causal_player_uuid IS NOT NULL AND length(causal_player_uuid) = 36
                    ), latest AS (
                        SELECT uuid, name, ROW_NUMBER() OVER (
                            PARTITION BY uuid ORDER BY happened_at DESC, id DESC
                        ) AS rank_number FROM actors
                    )
                    SELECT uuid, COALESCE(NULLIF(TRIM(name),''), uuid) AS latest_name
                    FROM latest WHERE rank_number = 1
                    ORDER BY lower(latest_name), uuid
                    LIMIT ? OFFSET ?
                    """)) {
                statement.setLong(1, cutoff);
                statement.setLong(2, cutoff);
                statement.setInt(3, PLAYER_PAGE_SIZE + 1);
                statement.setLong(4, (long) index * PLAYER_PAGE_SIZE);
                List<PlayerIdentity> identities = new ArrayList<>();
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        ensureQueryActive();
                        try {
                            UUID uuid = UUID.fromString(rows.getString("uuid"));
                            identities.add(new PlayerIdentity(uuid, rows.getString("latest_name")));
                        } catch (IllegalArgumentException invalid) {
                            // Historical non-player system identifiers aren't selectable.
                        }
                    }
                }
                boolean hasNext = identities.size() > PLAYER_PAGE_SIZE;
                return new IdentityPage(identities.subList(0, Math.min(PLAYER_PAGE_SIZE, identities.size())),
                        index, hasNext);
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    CompletableFuture<HistoryPage> historyAsync(HistoryQuery query, int requestedPage) {
        int page = Math.max(1, requestedPage);
        return boundedQueryAsync("player-history", () -> {
            String filters = OWNED + (query.worldName() == null ? "" : " AND world = ?")
                    + (query.action() == null ? "" : " AND action IN (?, ?)")
                    + (query.material() == null ? "" : " AND (" + MATERIAL + ") = ?");
            try (Connection db = open()) {
                long total;
                try (PreparedStatement count = prepare(db,
                        "SELECT COUNT(*) FROM block_changes WHERE " + filters)) {
                    bind(count, query);
                    try (ResultSet rs = count.executeQuery()) {
                        total = rs.next() ? rs.getLong(1) : 0L;
                    }
                }
                int safePage = (int) Math.min(page,
                        Math.max(1, (total + EVENT_PAGE_SIZE - 1L) / EVENT_PAGE_SIZE));
                List<PlayerLookupRow> events = new ArrayList<>();
                String sql = """
                        SELECT id, happened_at, actor_uuid, actor_name,
                               causal_player_uuid, causal_player_name, causal_cause_type,
                               world, x, y, z, action, before_data, after_data,
                               CASE WHEN before_entity_data IS NULL AND after_entity_data IS NULL
                                    THEN 0 ELSE 1 END AS has_block_entity_data
                        FROM block_changes WHERE %s
                        ORDER BY happened_at DESC, id DESC LIMIT ? OFFSET ?
                        """.formatted(filters);
                try (PreparedStatement stmt = prepare(db, sql)) {
                    int next = bind(stmt, query);
                    stmt.setInt(next++, EVENT_PAGE_SIZE);
                    stmt.setLong(next, (long) (safePage - 1) * EVENT_PAGE_SIZE);
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            ensureQueryActive();
                            events.add(new PlayerLookupRow(rs.getLong("id"),
                                    rs.getLong("happened_at"), rs.getString("actor_uuid"),
                                    rs.getString("actor_name"), rs.getString("causal_player_uuid"),
                                    rs.getString("causal_player_name"), rs.getString("causal_cause_type"),
                                    rs.getString("world"), rs.getInt("x"), rs.getInt("y"),
                                    rs.getInt("z"), ChangeAction.fromStorageId(rs.getString("action")),
                                    rs.getString("before_data"), rs.getString("after_data"),
                                    rs.getInt("has_block_entity_data") != 0));
                        }
                    }
                }
                return new HistoryPage(events, safePage, total);
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    CompletableFuture<FilterChoices> filterChoicesAsync(HistoryQuery query) {
        // Resolve all available action/material choices for the selected player,
        // scope and snapshot, not just the current page.
        HistoryQuery unfiltered = query.withAction(null).withMaterial(null);
        String where = OWNED + (query.worldName() == null ? "" : " AND world = ?");
        return boundedQueryAsync("player-filters", () -> {
            try (Connection db = open()) {
                List<ChangeAction> actions = new ArrayList<>();
                try (PreparedStatement stmt = prepare(db,
                        "SELECT DISTINCT action FROM block_changes WHERE " + where
                                + " ORDER BY action")) {
                    bind(stmt, unfiltered);
                    try (ResultSet rows = stmt.executeQuery()) {
                        while (rows.next()) {
                            ensureQueryActive();
                            ChangeAction action = ChangeAction.fromStorageId(rows.getString(1));
                            if (!actions.contains(action)) actions.add(action);
                        }
                    }
                }
                List<String> materials = new ArrayList<>();
                try (PreparedStatement stmt = prepare(db,
                        "SELECT DISTINCT (" + MATERIAL + ") AS material FROM block_changes WHERE "
                                + where + " ORDER BY material")) {
                    bind(stmt, unfiltered);
                    try (ResultSet rows = stmt.executeQuery()) {
                        while (rows.next()) {
                            ensureQueryActive();
                            String material = rows.getString(1);
                            if (material != null && !material.isBlank()) materials.add(material);
                        }
                    }
                }
                actions.sort(Comparator.comparing(ChangeAction::displayPastTense));
                materials.sort(String.CASE_INSENSITIVE_ORDER);
                return new FilterChoices(actions, materials);
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    private int bind(PreparedStatement stmt, HistoryQuery query) throws SQLException {
        int i = 1;
        stmt.setLong(i++, query.cutoff());
        stmt.setLong(i++, query.snapshot());
        stmt.setString(i++, query.playerUuid().toString());
        stmt.setString(i++, query.playerUuid().toString());
        if (query.worldName() != null) stmt.setString(i++, query.worldName());
        if (query.action() != null) {
            stmt.setString(i++, query.action().storageId());
            stmt.setString(i++, query.action().legacyStorageId());
        }
        if (query.material() != null) stmt.setString(i++, query.material());
        return i;
    }

    private Connection open() throws SQLException {
        ensureQueryActive();
        Connection db = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        try (Statement statement = db.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            ensureQueryActive();
        } catch (SQLException | RuntimeException exception) {
            db.close();
            throw exception;
        }
        return db;
    }

    private PreparedStatement prepare(Connection db, String sql) throws SQLException {
        ensureQueryActive();
        PreparedStatement stmt = db.prepareStatement(sql);
        try {
            // sqlite-jdbc's JDBC timeout is a busy-lock timeout, not a CPU budget.
            // The async deadline below must independently interrupt live SQL.
            stmt.setQueryTimeout(timeoutSeconds);
            trackStatement(stmt);
            return stmt;
        } catch (SQLException | RuntimeException failure) {
            stmt.close();
            throw failure;
        }
    }

    /** Register the current SQLite statement so expiry actually interrupts it. */
    void trackStatement(PreparedStatement statement) throws SQLException {
        QueryCancellation request = activeRequest.get();
        if (request != null) request.track(statement);
    }

    void ensureQueryActive() {
        QueryCancellation request = activeRequest.get();
        if (request != null && request.isCancelled()) {
            throw new CompletionException(new TimeoutException(
                    "FragGuard GUI player-history SQL read cancelled"));
        }
    }

    /** One independent cancellation context per GUI request, never shared across retries. */
    <T> CompletableFuture<T> boundedQueryAsync(String phase, Supplier<T> supplier) {
        QueryCancellation request = new QueryCancellation();
        long started = System.nanoTime();
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            activeRequest.set(request);
            try {
                ensureQueryActive(); // Also cover deadlines during worker queueing.
                T result = supplier.get();
                ensureQueryActive();
                return result;
            } finally {
                activeRequest.remove();
            }
        });
        future.orTimeout(timeoutSeconds, TimeUnit.SECONDS);
        return future.whenComplete((result, failure) -> {
            if (failure != null && unwrap(failure) instanceof TimeoutException) {
                request.cancel();
                logger.warning("FragGuard GUI player query " + phase + " exceeded "
                        + timeoutSeconds + " seconds (elapsed "
                        + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                        + " ms); cancelling the underlying SQLite statement");
            }
        });
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure.getCause() != null && failure.getCause() != failure) {
            failure = failure.getCause();
        }
        return failure;
    }

    static final class QueryCancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<PreparedStatement> running = new AtomicReference<>();

        void track(PreparedStatement statement) throws SQLException {
            running.set(statement);
            if (cancelled.get()) {
                try {
                    statement.cancel();
                } finally {
                    throw new SQLException("FragGuard GUI player-history SQL read cancelled");
                }
            }
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        void cancel() {
            cancelled.set(true);
            PreparedStatement statement = running.get();
            if (statement != null) {
                try {
                    statement.cancel();
                } catch (SQLException ignored) {
                    // The request is already timed out; interruption is best effort.
                }
            }
        }
    }
}
