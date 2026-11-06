package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GuiPlayerHistoryStoreTest {
    @TempDir Path directory;
    private static final UUID BUILDER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void browserPagesAlphabeticallyByCurrentNameAndUsesCanonicalIdentity() throws Exception {
        initialize();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 46; i++) {
            UUID id = UUID.nameUUIDFromBytes(("member-" + i).getBytes(StandardCharsets.UTF_8));
            insert(now - 20_000 + i, id.toString(), "Member" + String.format("%02d", i),
                    null, null, null, "world", ChangeAction.PLACE, "minecraft:air", "minecraft:stone");
        }
        insert(now - 15_000, BUILDER.toString(), "OldBuilder",
                null, null, null, "world", ChangeAction.PLACE, "minecraft:air", "minecraft:dirt");
        insert(now - 1_000, null, "Explosion: TNT", BUILDER.toString(), "Builder",
                "TNT_CHAIN", "world_nether", ChangeAction.EXPLOSION,
                "minecraft:stone", "minecraft:air");
        insert(now - 1000, "SYSTEM", "System", null, null, null, "world",
                ChangeAction.FIRE_BURN, "minecraft:oak_log", "minecraft:air");
        UUID physicalEntity = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        insert(now - 500, physicalEntity.toString(), "Explosion: TNT",
                null, null, null, "world", ChangeAction.EXPLOSION,
                "minecraft:stone", "minecraft:air");
        var store = store();
        var page0 = store.identitiesAsync(0, 30).join();
        var page1 = store.identitiesAsync(1, 30).join();
        assertEquals(45, page0.identities().size());
        assertTrue(page0.hasNext());
        assertEquals(2, page1.identities().size());
        assertFalse(page1.hasNext());
        List<GuiPlayerHistoryStore.PlayerIdentity> all = new ArrayList<>(page0.identities());
        all.addAll(page1.identities());
        assertEquals(47, all.size());
        assertEquals(1, all.stream().filter(p -> BUILDER.equals(p.uuid())).count());
        assertEquals("Builder", all.stream().filter(p -> BUILDER.equals(p.uuid()))
                .findFirst().orElseThrow().latestName());
        assertEquals("Builder", all.getFirst().latestName());
        assertTrue(all.stream().noneMatch(p -> p.latestName().equals("System")));
        assertTrue(all.stream().noneMatch(p -> p.uuid().equals(physicalEntity)),
                "a UUID-bearing physical TNT entity must not appear as a player");
    }

    @Test
    void playerEventsIncludeDirectAndCausalChangesWithWorldTimeAndSqlFilters() throws Exception {
        initialize();
        long now = System.currentTimeMillis();
        insert(now - 12_000, BUILDER.toString(), "Builder",
                null, null, null, "world", ChangeAction.PLACE, "minecraft:air", "minecraft:stone");
        insert(now - 11_000, "tnt-entity", "Explosion: TNT", BUILDER.toString(), "Builder",
                "TNT_CHAIN", "world_nether", ChangeAction.EXPLOSION,
                "minecraft:stone", "minecraft:air");
        insert(now - 10_000, "piston", "Piston Extend", BUILDER.toString(), "Builder",
                "PISTON", "world", ChangeAction.PISTON_EXTEND,
                "minecraft:dirt", "minecraft:air");
        insert(now - 9_000, OTHER.toString(), "AnotherPlayer",
                null, null, null, "world", ChangeAction.PLACE, "minecraft:air", "minecraft:stone");
        var store = store();
        var scope = new GuiPlayerHistoryStore.HistoryQuery(BUILDER, "Builder", null,
                now - 30_000, now, null, null);
        var all = store.historyAsync(scope, 1).join();
        assertEquals(3, all.totalRows());
        assertEquals(3, all.rows().size());
        var indirect = all.rows().stream().filter(row -> row.actorName().equals("Explosion: TNT"))
                .findFirst().orElseThrow();
        assertTrue(indirect.indirectlyAttributedTo(BUILDER.toString()));
        assertEquals("TNT_CHAIN", indirect.causalCauseType());
        assertEquals("world_nether", indirect.worldName());
        assertFalse(all.rows().stream().filter(row -> row.actorUuid().equals(BUILDER.toString()))
                .findFirst().orElseThrow().indirectlyAttributedTo(BUILDER.toString()));

        var netherOnly = new GuiPlayerHistoryStore.HistoryQuery(BUILDER, "Builder",
                "world_nether", now - 30_000, now, null, null);
        assertEquals(1, store.historyAsync(netherOnly, 1).join().totalRows());
        assertEquals(1, store.historyAsync(scope.withAction(ChangeAction.EXPLOSION), 1)
                .join().totalRows());
        assertEquals(2, store.historyAsync(scope.withMaterial("minecraft:stone"), 1)
                .join().totalRows());
        assertEquals(0, store.historyAsync(scope.withMaterial("minecraft:glass"), 1)
                .join().totalRows());
        assertEquals(0, store.historyAsync(new GuiPlayerHistoryStore.HistoryQuery(
                BUILDER, "Builder", null, now - 5_000, now, null, null), 1).join().totalRows());

        var options = store.filterChoicesAsync(scope).join();
        assertTrue(options.actions().contains(ChangeAction.PLACE));
        assertTrue(options.actions().contains(ChangeAction.EXPLOSION));
        assertTrue(options.actions().contains(ChangeAction.PISTON_EXTEND));
        assertTrue(options.materials().contains("minecraft:stone"));
        assertTrue(options.materials().contains("minecraft:dirt"));
    }

    @Test
    void guiHistoryAndFilterQueriesUseBothPartialActorAndCausalIndexes() throws Exception {
        initialize();
        try (Connection connection = open(); Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE INDEX idx_fg_time ON block_changes(happened_at)");
            stmt.execute(HistoricalActorIndexPlan.ACTOR_TIME_SQL);
            stmt.execute("""
                    CREATE INDEX idx_fg_causal_actor_time
                    ON block_changes(causal_player_uuid, happened_at DESC, id DESC)
                    WHERE causal_player_uuid IS NOT NULL
                    """);
        }
        long now = System.currentTimeMillis();
        insert(now - 1000, BUILDER.toString(), "Builder", null, null, null, "world",
                ChangeAction.PLACE, "minecraft:air", "minecraft:stone");
        insert(now - 900, "SYSTEM", "Explosion: TNT", BUILDER.toString(), "Builder", "TNT_CHAIN",
                "world_nether", ChangeAction.EXPLOSION, "minecraft:stone", "minecraft:air");

        try (Connection connection = open()) {
            for (String query : List.of(
                    "SELECT COUNT(*) FROM block_changes WHERE " + GuiPlayerHistoryStore.OWNED,
                    "SELECT DISTINCT action FROM block_changes WHERE "
                            + GuiPlayerHistoryStore.OWNED + " ORDER BY action",
                    "SELECT id FROM block_changes WHERE " + GuiPlayerHistoryStore.OWNED
                            + " AND world = ? ORDER BY happened_at DESC, id DESC LIMIT 36")) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "EXPLAIN QUERY PLAN " + query)) {
                    statement.setLong(1, now - 30000);
                    statement.setLong(2, now);
                    statement.setString(3, BUILDER.toString());
                    statement.setString(4, BUILDER.toString());
                    if (query.contains("world = ?")) statement.setString(5, "world");
                    StringBuilder plan = new StringBuilder();
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) plan.append(rows.getString("detail")).append('\n');
                    }
                    assertTrue(plan.toString().contains("idx_fg_actor_time"),
                            "GUI history must seek direct player records: " + plan);
                    assertTrue(plan.toString().contains("idx_fg_causal_actor_time"),
                            "GUI history must seek causal player records: " + plan);
                }
            }
        }

        var scope = new GuiPlayerHistoryStore.HistoryQuery(
                BUILDER, "Builder", null, now - 30_000, now, null, null);
        assertEquals(2, store().historyAsync(scope, 1).join().totalRows());
        assertTrue(store().filterChoicesAsync(scope).join().actions().contains(ChangeAction.EXPLOSION));
    }

    @Test
    void displayedMaterialFilterMatchesSqlForSolidTransformationsAndAirTransitions()
            throws Exception {
        initialize();
        long now = System.currentTimeMillis();
        record Change(String before, String after, String expected) {}
        List<Change> changes = List.of(
                new Change("minecraft:oak_log[axis=x]",
                        "minecraft:stripped_oak_log[axis=y]", "minecraft:stripped_oak_log"),
                new Change("minecraft:dirt", "minecraft:farmland[moisture=7]",
                        "minecraft:farmland"),
                new Change("minecraft:stone", "minecraft:air", "minecraft:stone"),
                new Change("minecraft:air", "minecraft:granite", "minecraft:granite"),
                new Change("minecraft:cave_air", "minecraft:air", "minecraft:air"),
                new Change("minecraft:grass_block", "minecraft:coarse_dirt", "minecraft:coarse_dirt")
        );
        for (int i = 0; i < changes.size(); i++) {
            Change change = changes.get(i);
            insert(now - 30_000 + i * 1000L, BUILDER.toString(), "Builder",
                    null, null, null, "world", ChangeAction.PLACE,
                    change.before(), change.after());
        }
        var store = store();
        var scope = new GuiPlayerHistoryStore.HistoryQuery(
                BUILDER, "Builder", null, now - 35_000, now, null, null);
        var rows = store.historyAsync(scope, 1).join().rows();
        assertEquals(changes.size(), rows.size());
        var choices = store.filterChoicesAsync(scope).join();
        for (int i = 0; i < changes.size(); i++) {
            Change change = changes.get(i);
            PlayerLookupRow event = rows.stream()
                    .filter(row -> change.before().equals(row.beforeData())
                            && change.after().equals(row.afterData()))
                    .findFirst().orElseThrow();
            LookupRow display = new LookupRow(event.id(), event.happenedAt(),
                    event.actorUuid(), event.actorName(), event.worldName(),
                    event.x(), event.y(), event.z(), event.action(),
                    event.beforeData(), event.afterData(), event.blockEntityDataPresent());
            assertEquals(change.expected(), LookupActivityGrouper.materialKey(display),
                    "regression fixture must match the material shown by the GUI");
            assertTrue(choices.materials().contains(change.expected()),
                    "every displayed material must be offered in the filter catalog");
            var filtered = store.historyAsync(scope.withMaterial(change.expected()), 1)
                    .join().rows();
            assertTrue(filtered.stream().anyMatch(row -> row.id() == event.id()),
                    "filter for " + change.expected() + " must retain its rendered event");
        }
        assertEquals(0, store.historyAsync(scope.withMaterial("minecraft:oak_log"), 1)
                .join().totalRows(), "old source log must not match the stripped-log event");
        assertEquals(0, store.historyAsync(scope.withMaterial("minecraft:dirt"), 1)
                .join().totalRows(), "old tilled dirt must not match displayed farmland");
    }

    @Test
    void exactPagesRemainBoundedAndDoNotDropLargeHistory() throws Exception {
        initialize();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 90; i++) {
            insert(now - 5_000 - i, BUILDER.toString(), "Builder",
                    null, null, null, "world", ChangeAction.BREAK,
                    "minecraft:stone", "minecraft:air");
        }
        var scope = new GuiPlayerHistoryStore.HistoryQuery(BUILDER, "Builder",
                null, now - 10_000, now, null, null);
        var store = store();
        var first = store.historyAsync(scope, 1).join();
        var third = store.historyAsync(scope, 3).join();
        assertEquals(90, first.totalRows());
        assertEquals(36, first.rows().size());
        assertTrue(first.hasNext());
        assertEquals(18, third.rows().size());
        assertFalse(third.hasNext());
        assertTrue(first.rows().getLast().happenedAt() > third.rows().getFirst().happenedAt());
    }

    private GuiPlayerHistoryStore store() {
        return new GuiPlayerHistoryStore(directory.toFile(), 5);
    }

    private void initialize() throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE block_changes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        happened_at INTEGER NOT NULL,
                        actor_uuid TEXT,
                        actor_name TEXT,
                        causal_player_uuid TEXT,
                        causal_player_name TEXT,
                        causal_cause_type TEXT,
                        world TEXT,
                        x INTEGER NOT NULL DEFAULT 0,
                        y INTEGER NOT NULL DEFAULT 64,
                        z INTEGER NOT NULL DEFAULT 0,
                        action TEXT,
                        before_data TEXT,
                        after_data TEXT,
                        before_entity_data BLOB,
                        after_entity_data BLOB,
                        rollback_pending INTEGER NOT NULL DEFAULT 0
                    )
                    """);
        }
    }

    private void insert(long happened, String actorUuid, String actorName,
                        String causalUuid, String causalName, String causeType,
                        String world, ChangeAction action, String before, String after) throws Exception {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO block_changes(happened_at, actor_uuid, actor_name,
                     causal_player_uuid, causal_player_name, causal_cause_type,
                     world, x, y, z, action, before_data, after_data)
                     VALUES (?, ?, ?, ?, ?, ?, ?, 5, 64, 7, ?, ?, ?)
                     """)) {
            statement.setLong(1, happened);
            statement.setString(2, actorUuid);
            statement.setString(3, actorName);
            statement.setString(4, causalUuid);
            statement.setString(5, causalName);
            statement.setString(6, causeType);
            statement.setString(7, world);
            statement.setString(8, action.storageId());
            statement.setString(9, before);
            statement.setString(10, after);
            statement.executeUpdate();
        }
    }

    private Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("fragguard.db"));
    }
}
