package org.pinnaclesmp.fragguard;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerLookupDatabaseTest {
    private static final String PLAYER = "11111111-2222-3333-4444-555555555555";
    private static final String OTHER = "11111111-2222-3333-4444-666666666666";
    @TempDir Path directory;
    private Database database;

    @AfterEach
    void close() {
        if (database != null) {
            database.shutdown();
        }
    }

    @Test
    void globalPagesMixDirectAndChainedTntWithoutDoubleCounting() throws Exception {
        database = start();
        long now = System.currentTimeMillis();
        database.insertRequiredAsync(List.of(
                change(now - 5000, PLAYER, "FormerName", "world", 0, ChangeAction.BREAK),
                caused(now - 4000, "entity-tnt-A", "Explosion: TNT", "world_nether",
                        2000, "chain-1", "TNT_ENTITY", PLAYER, "CurrentName"),
                caused(now - 3000, "entity-tnt-B", "Explosion: TNT", "world",
                        -3000, "chain-1", "TNT_CHAIN", PLAYER, "CurrentName"),
                caused(now - 2000, "SYSTEM", "Piston Extend", "world_nether",
                        3500, "chain-2", "PISTON", PLAYER, "CurrentName"),
                change(now - 1000, OTHER, "Unrelated", "world", 6, ChangeAction.BREAK),
                // Direct and causal UUID are identical: no duplicate event on the page.
                caused(now - 500, PLAYER, "CurrentName", "world", 7,
                        "chain-3", "DIRECT", PLAYER, "CurrentName")
        )).join();

        PlayerLookupPage first = database.lookupPlayerAsync("u:formername",
                null, 1, 2, 0, 30).join();
        PlayerLookupPage second = database.lookupPlayerAsync(PLAYER,
                null, 2, 2, 0, 30).join();
        PlayerLookupPage third = database.lookupPlayerAsync("CurrentName",
                null, 3, 2, 0, 30).join();

        assertEquals(UUID.fromString(PLAYER), first.player().uuid());
        assertEquals("CurrentName", first.player().latestName());
        assertEquals(5, first.totalRows());
        assertEquals(3, first.totalPages());
        assertEquals(List.of(7, 3500), first.rows().stream().map(PlayerLookupRow::x).toList());
        assertEquals(List.of(-3000, 2000), second.rows().stream().map(PlayerLookupRow::x).toList());
        assertEquals(List.of(0), third.rows().stream().map(PlayerLookupRow::x).toList());
        assertEquals(3, third.page());
        assertEquals(3, database.lookupPlayerAsync("CurrentName", null, 999,
                2, 0, 30).join().page());
        assertTrue(second.rows().stream().allMatch(row -> row.indirectlyAttributedTo(PLAYER)));
        assertEquals("entity-tnt-B", second.rows().getFirst().actorUuid());
        assertEquals("Explosion: TNT", second.rows().getFirst().actorName());
        assertEquals("TNT_CHAIN", second.rows().getFirst().causalCauseType());
        assertEquals("world_nether", second.rows().get(1).worldName());
        assertFalse(first.rows().getFirst().indirectlyAttributedTo(PLAYER));
    }

    @Test
    void worldAndTimeFilteringAreIndependentFromOperatorPosition() throws Exception {
        database = start();
        long now = System.currentTimeMillis();
        database.insertRequiredAsync(List.of(
                change(now - 4_000, PLAYER, "Builder", "world", 0, ChangeAction.PLACE),
                caused(now - 3_000, "tnt", "Explosion: TNT", "world_nether",
                        1, "fuse", "TNT_ENTITY", PLAYER, "Builder"),
                change(now - 90_000, PLAYER, "Builder", "world_nether", 2, ChangeAction.BREAK),
                change(now - 5_000, OTHER, "Other", "world_nether", 3, ChangeAction.BREAK)
        )).join();
        var allNethers = database.lookupPlayerAsync("Builder", "world_nether",
                1, 15, 0, 30).join();
        assertEquals(2, allNethers.totalRows());
        assertEquals(List.of(1, 2), allNethers.rows().stream().map(PlayerLookupRow::x).toList());
        var recentNethers = database.lookupPlayerAsync("Builder", "world_nether",
                1, 15, 60_000, 30).join();
        assertEquals(1, recentNethers.totalRows());
        assertEquals(1, recentNethers.rows().getFirst().x());
        var empty = database.lookupPlayerAsync("Builder", "the_end",
                1, 15, 0, 30).join();
        assertEquals(0, empty.totalRows());
        assertTrue(empty.rows().isEmpty());
        assertEquals(3, database.lookupPlayerAsync("Builder", null, 1, 15,
                0, 30).join().totalRows());
    }

    @Test
    void retentionAppliesEvenWithNoTimeAndCannotBeBypassedByDuration() throws Exception {
        database = start();
        long now = System.currentTimeMillis();
        database.insertRequiredAsync(List.of(
                change(now - 86_400_000L - 5_000, PLAYER, "Builder", "world", 1, ChangeAction.BREAK),
                change(now - 86_400_000L + 5_000, PLAYER, "Builder", "world", 2, ChangeAction.BREAK),
                change(now - 500, PLAYER, "Builder", "world", 3, ChangeAction.BREAK)
        )).join();
        var retained = database.lookupPlayerAsync("Builder", null, 1, 15, 0, 1).join();
        assertEquals(2, retained.totalRows());
        assertEquals(List.of(3, 2), retained.rows().stream().map(PlayerLookupRow::x).toList());
        assertEquals(2, database.lookupPlayerAsync("Builder", null, 1, 15,
                86_400_000L * 60, 1).join().totalRows());
        assertEquals(1, database.lookupPlayerAsync("Builder", null, 1, 15,
                1000, 1).join().totalRows());
    }

    @Test
    void causalOnlyPlayerCanBeResolvedWithoutAnyDirectBlockChange() throws Exception {
        database = start();
        long now = System.currentTimeMillis();
        database.insertRequiredAsync(List.of(
                caused(now - 1000, "tnt-entity", "Explosion: TNT", "world",
                        8, "chain", "TNT_ENTITY", PLAYER, "HiddenBuilder"),
                caused(now - 500, "SYSTEM", "Piston Extend", "world",
                        9, "chain", "PISTON", PLAYER, "RenamedBuilder")
        )).join();
        assertEquals(2, database.lookupPlayerAsync("u:HiddenBuilder", null,
                1, 20, 0, 30).join().totalRows());
        var byUuid = database.lookupPlayerAsync(PLAYER, null, 1, 20, 0, 30).join();
        assertEquals("RenamedBuilder", byUuid.player().latestName());
        assertEquals(2, byUuid.rows().size());
        assertTrue(byUuid.rows().stream().allMatch(r -> r.indirectlyAttributedTo(PLAYER)));

        var noHistory = assertThrows(CompletionException.class,
                () -> database.lookupPlayerAsync("UnknownPlayer", null, 1, 20, 0, 30).join());
        assertInstanceOf(HistoricalPlayerResolver.IdentityException.class, noHistory.getCause());
    }

    @Test
    void nameReuseWithCausalHistoryFailsClosed() throws Exception {
        database = start();
        long now = System.currentTimeMillis();
        database.insertRequiredAsync(List.of(
                change(now - 1000, PLAYER, "SharedName", "world", 0, ChangeAction.BREAK),
                caused(now - 500, "tnt", "Explosion: TNT", "world",
                        1, "chain", "TNT_ENTITY", OTHER, "SharedName")
        )).join();
        var error = assertThrows(CompletionException.class,
                () -> database.lookupPlayerAsync("SharedName", null, 1, 15, 0, 30).join());
        assertInstanceOf(HistoricalPlayerResolver.IdentityException.class, error.getCause());
        assertEquals(1, database.lookupPlayerAsync(OTHER, null, 1, 15, 0, 30)
                .join().totalRows());
    }

    @Test
    void schemaV6MigrationCreatesIndexedNameLookupAndPreservesDirectAndCausalRows() throws Exception {
        database = start();
        long now = System.currentTimeMillis();
        database.insertRequiredAsync(List.of(
                caused(now - 1000, "tnt", "Explosion: TNT", "world",
                        5, "chain-1", "TNT_ENTITY", PLAYER, "OldName")
        )).join();
        database.shutdown();
        database = null;
        try (Connection connection = open(); Statement sql = connection.createStatement()) {
            sql.execute("DROP INDEX idx_fg_causal_name_uuid");
            sql.execute("PRAGMA user_version=6");
        }

        database = start();
        assertEquals(1, database.lookupPlayerAsync("oldname", null, 1, 20,
                0, 30).join().totalRows());
        try (Connection connection = open(); Statement sql = connection.createStatement()) {
            try (ResultSet version = sql.executeQuery("PRAGMA user_version")) {
                assertTrue(version.next());
                assertEquals(Database.SCHEMA_VERSION, version.getInt(1));
            }
            try (ResultSet row = sql.executeQuery("""
                    SELECT actor_name, causal_player_uuid, causal_chain_id, causal_cause_type
                    FROM block_changes WHERE x=5
                    """)) {
                assertTrue(row.next());
                assertEquals("Explosion: TNT", row.getString(1));
                assertEquals(PLAYER, row.getString(2));
                assertEquals("chain-1", row.getString(3));
                assertEquals("TNT_ENTITY", row.getString(4));
            }
            try (ResultSet explain = sql.executeQuery("""
                    EXPLAIN QUERY PLAN SELECT causal_player_uuid FROM block_changes
                    WHERE causal_player_name = 'OldName' COLLATE NOCASE
                      AND causal_player_uuid IS NOT NULL
                    """)) {
                assertTrue(explain.next());
                assertTrue(explain.getString("detail").contains("idx_fg_causal_name_uuid"),
                        "historical causal-only names must use the index");
            }
            // The latest-name resolver must read at most one index-ordered
            // candidate from each branch, never sort a prolific player's rows.
            for (String[] plan : new String[][]{
                    {"EXPLAIN QUERY PLAN SELECT actor_name, happened_at, id FROM block_changes "
                            + "WHERE actor_uuid = '" + PLAYER + "' "
                            + "ORDER BY happened_at DESC, id DESC LIMIT 1", "idx_fg_actor_time"},
                    {"EXPLAIN QUERY PLAN SELECT causal_player_name, happened_at, id FROM block_changes "
                            + "WHERE causal_player_uuid = '" + PLAYER + "' AND causal_player_name IS NOT NULL "
                            + "ORDER BY happened_at DESC, id DESC LIMIT 1", "idx_fg_causal_actor_time"}
            }) {
                try (ResultSet explanation = sql.executeQuery(plan[0])) {
                    assertTrue(explanation.next());
                    assertTrue(explanation.getString("detail").contains(plan[1]),
                            "latest-name resolution needs index seek: " + plan[1]);
                }
            }
        }
    }

    private BlockChange change(long at, String actor, String name, String world, int x,
                               ChangeAction action) {
        return new BlockChange(at, actor, name, world, x, 64, 0,
                action, "minecraft:stone", "minecraft:air");
    }

    private BlockChange caused(long at, String physicalUuid, String physicalName,
                               String world, int x, String chain, String type,
                               String player, String name) {
        return change(at, physicalUuid, physicalName, world, x, ChangeAction.EXPLOSION)
                .withCausalAttribution(new CausalAttribution(player, name, chain, type));
    }

    private Database start() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World overworld = mock(World.class);
        World nether = mock(World.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("database-query-timeout-seconds", 10);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("PlayerLookupDatabaseTest"));
        when(server.getWorlds()).thenReturn(List.of(overworld, nether));
        when(server.getWorld("world")).thenReturn(overworld);
        when(server.getWorld("world_nether")).thenReturn(nether);
        when(overworld.getName()).thenReturn("world");
        when(overworld.getUID()).thenReturn(UUID.fromString("99999999-2222-3333-4444-555555555555"));
        when(nether.getName()).thenReturn("world_nether");
        when(nether.getUID()).thenReturn(UUID.fromString("99999999-2222-3333-4444-666666666666"));
        Database started = new Database(plugin);
        started.init();
        return started;
    }

    private Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("fragguard.db"));
    }
}
