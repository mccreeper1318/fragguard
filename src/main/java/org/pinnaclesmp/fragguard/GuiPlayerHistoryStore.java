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
            CASE WHEN instr(before_data, '[') > 0
                 THEN substr(before_data, 1, instr(before_data, '[') - 1)
                 ELSE before_data END
            """;
    private static final String AFTER_MATERIAL = """
            CASE WHEN instr(after_data, '[') > 0
                 THEN substr(after_data, 1, instr(after_data, '[') - 1)
                 ELSE after_data END
            """;
    private static final String MATERIAL = """
            CASE WHEN (%1$s) IN ('air','minecraft:air','cave_air','minecraft:cave_air',
                         'void_air','minecraft:void_air') THEN (%2$s)
                 ELSE (%1$s) END
            """.formatted(BASE_MATERIAL, AFTER_MATERIAL);
    private static final String OWNED = """
            rollback_pending = 0 AND happened_at >= ? AND happened_at <= ?
            AND (actor_uuid = ? OR causal_player_uuid = ?)
            """;
    private final File databaseFile;
    private final int timeoutSeconds;

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
        return async(() -> {
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
        return async(() -> {
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
        return async(() -> {
            try (Connection db = open()) {
                List<ChangeAction> actions = new ArrayList<>();
                try (PreparedStatement stmt = prepare(db,
                        "SELECT DISTINCT action FROM block_changes WHERE " + where
                                + " ORDER BY action")) {
                    bind(stmt, unfiltered);
                    try (ResultSet rows = stmt.executeQuery()) {
                        while (rows.next()) {
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
        Connection db = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        try (Statement statement = db.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        } catch (SQLException exception) {
            db.close();
            throw exception;
        }
        return db;
    }

    private PreparedStatement prepare(Connection db, String sql) throws SQLException {
        PreparedStatement stmt = db.prepareStatement(sql);
        stmt.setQueryTimeout(timeoutSeconds);
        return stmt;
    }

    private <T> CompletableFuture<T> async(java.util.function.Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier).orTimeout(timeoutSeconds, TimeUnit.SECONDS);
    }
}
