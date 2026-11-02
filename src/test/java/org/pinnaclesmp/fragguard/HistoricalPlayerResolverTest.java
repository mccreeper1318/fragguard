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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoricalPlayerResolverTest {
    private static final UUID OLD_PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER_PLAYER = UUID.fromString("11111111-2222-3333-4444-666666666666");
    private static final UUID WORLD_ID = UUID.fromString("99999999-2222-3333-4444-555555555555");

    @TempDir
    Path folder;

    private Database database;

    @AfterEach
    void shutdown() {
        if (database != null) {
            database.shutdown();
        }
    }

    @Test
    void resolvesHistoricalAndCurrentNamesToOneUuidAndNewestDisplay() throws Exception {
        database = start();
        database.insertRequiredAsync(List.of(
                change(OLD_PLAYER.toString(), "OldBuilder", 1000L, 0),
                change(OLD_PLAYER.toString(), "NewBuilder", 3000L, 1),
                change(OTHER_PLAYER.toString(), "AnotherUser", 2000L, 2)
        )).join();

        var byOldName = database.resolveHistoricalPlayerAsync("u:oLdBuIlDeR").join();
        var byNewName = database.resolveHistoricalPlayerAsync("NewBuilder").join();
        var byUuid = database.resolveHistoricalPlayerAsync(OLD_PLAYER.toString()).join();
        var byCompactUuid = database.resolveHistoricalPlayerAsync(
                OLD_PLAYER.toString().replace("-", "")).join();

        assertEquals(OLD_PLAYER, byOldName.uuid());
        assertEquals("NewBuilder", byOldName.latestName());
        assertEquals(byOldName, byNewName);
        assertEquals(byOldName, byUuid);
        assertEquals(byOldName, byCompactUuid);
        assertEquals(OTHER_PLAYER, database.resolveHistoricalPlayerAsync("AnotherUser").join().uuid());
    }

    @Test
    void unknownNameAndUnknownUuidReturnClearErrors() throws Exception {
        database = start();
        database.insertRequiredAsync(List.of(change(OLD_PLAYER.toString(), "Builder", 1000L, 0))).join();

        CompletionException noName = assertThrows(CompletionException.class,
                () -> database.resolveHistoricalPlayerAsync("u:MissingName").join());
        assertInstanceOf(HistoricalPlayerResolver.IdentityException.class, noName.getCause());
        assertTrue(noName.getCause().getMessage().contains("No retained FragGuard history"));

        CompletionException noUuid = assertThrows(CompletionException.class,
                () -> database.resolveHistoricalPlayerAsync(OTHER_PLAYER.toString()).join());
        assertInstanceOf(HistoricalPlayerResolver.IdentityException.class, noUuid.getCause());
        assertTrue(noUuid.getCause().getMessage().contains("No retained FragGuard history"));

        CompletionException blank = assertThrows(CompletionException.class,
                () -> database.resolveHistoricalPlayerAsync("u:").join());
        assertTrue(blank.getCause().getMessage().contains("Enter a username"));
    }

    @Test
    void reusedHistoricalNameFailsClosedRatherThanGuessingAnActor() throws Exception {
        database = start();
        database.insertRequiredAsync(List.of(
                change(OLD_PLAYER.toString(), "SharedName", 1000L, 0),
                change(OLD_PLAYER.toString(), "RenamedUser", 2000L, 1),
                change(OTHER_PLAYER.toString(), "SharedName", 3000L, 2),
                change("system:fire", "SharedName", 4000L, 3)
        )).join();

        CompletionException ambiguous = assertThrows(CompletionException.class,
                () -> database.resolveHistoricalPlayerAsync("u:sHaReDnAmE").join());
        assertInstanceOf(HistoricalPlayerResolver.IdentityException.class, ambiguous.getCause());
        assertTrue(ambiguous.getCause().getMessage().contains("more than one historical UUID"));

        assertEquals(OLD_PLAYER, database.resolveHistoricalPlayerAsync(OLD_PLAYER.toString()).join().uuid());
        assertEquals(OTHER_PLAYER, database.resolveHistoricalPlayerAsync(OTHER_PLAYER.toString()).join().uuid());
        assertEquals("RenamedUser", database.resolveHistoricalPlayerAsync("RenamedUser").join().latestName());
    }

    @Test
    void migratesV4IndexesWithoutChangingHistoryOrRollbackJobs() throws Exception {
        database = start();
        byte[] itemBytes = new byte[]{9, 8, 7, 6};
        database.insertRequiredAsync(List.of(
                new BlockChange(1000L, OLD_PLAYER.toString(), "OlderName", "world", 0, 64, 0,
                        ChangeAction.BREAK, "minecraft:chest", "minecraft:air", itemBytes, null),
                change(OLD_PLAYER.toString(), "NewerName", 2000L, 1)
        )).join();
        RollbackJob job = database.createRollbackJobAsync(
                OLD_PLAYER.toString(), "Admin", "world", 0, 0, 10,
                500L, 3000L, false, List.of(
                        new RollbackTarget("world", 0, 64, 0,
                                "minecraft:chest", "minecraft:air", itemBytes, null))).join();
        database.shutdown();
        database = null;

        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX idx_fg_actor_time");
            statement.execute("DROP INDEX idx_fg_actor_world_time");
            statement.execute("DROP INDEX idx_fg_actor_name_uuid");
            statement.execute("PRAGMA user_version=4");
        }

        database = start();
        assertEquals(OLD_PLAYER, database.resolveHistoricalPlayerAsync("OlderName").join().uuid());
        assertEquals("NewerName", database.resolveHistoricalPlayerAsync("OlderName").join().latestName());
        assertEquals(1, database.loadRollbackChangesAsync(job.id(), false).join().size());
        assertEquals(2, database.lookupAsync("world", 0, 0, 10, 1, 20, 30).join().totalRows());

        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(rows.next());
                assertEquals(Database.SCHEMA_VERSION, rows.getInt(1));
            }
            for (String index : List.of("idx_fg_actor_time", "idx_fg_actor_world_time",
                    "idx_fg_actor_name_uuid")) {
                try (ResultSet rows = statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='index' AND name='" + index + "'")) {
                    assertTrue(rows.next(), "v4 upgrade should add " + index);
                }
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT before_entity_data FROM block_changes WHERE x=0 AND y=64 AND z=0")) {
                assertTrue(rows.next());
                org.junit.jupiter.api.Assertions.assertArrayEquals(itemBytes, rows.getBytes(1));
            }
            try (ResultSet plan = statement.executeQuery("""
                    EXPLAIN QUERY PLAN SELECT id FROM block_changes
                    WHERE actor_uuid = '11111111-2222-3333-4444-555555555555'
                      AND happened_at >= 0 ORDER BY happened_at DESC, id DESC
                    """)) {
                assertTrue(plan.next());
                assertTrue(plan.getString("detail").contains("idx_fg_actor_time"),
                        "actor/time lookup should use the covering index, not scan history");
            }
            try (ResultSet plan = statement.executeQuery("""
                    EXPLAIN QUERY PLAN SELECT id FROM block_changes
                    WHERE actor_uuid = '11111111-2222-3333-4444-555555555555'
                      AND world_uuid = '99999999-2222-3333-4444-555555555555'
                      AND happened_at >= 0
                    ORDER BY happened_at DESC, id DESC
                    """)) {
                assertTrue(plan.next());
                assertTrue(plan.getString("detail").contains("idx_fg_actor_world_time"));
            }
            try (ResultSet plan = statement.executeQuery("""
                    EXPLAIN QUERY PLAN SELECT DISTINCT actor_uuid FROM block_changes
                    WHERE actor_name = 'OlderName' COLLATE NOCASE
                    """)) {
                assertTrue(plan.next());
                assertTrue(plan.getString("detail").contains("idx_fg_actor_name_uuid"));
            }
            try (ResultSet row = statement.executeQuery(
                    "SELECT id FROM rollback_jobs WHERE id=" + job.id())) {
                assertTrue(row.next(), "schema upgrade must retain existing rollback jobs");
            }
        }

        // Restarting the same schema must be idempotent, with indexes intact.
        database.shutdown();
        database = start();
        assertEquals(OLD_PLAYER, database.resolveHistoricalPlayerAsync("OlderName").join().uuid());
    }

    private BlockChange change(String uuid, String name, long at, int x) {
        return new BlockChange(at, uuid, name, "world", x, 64, 0,
                ChangeAction.BREAK, "minecraft:stone", "minecraft:air");
    }

    private Database start() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("database-query-timeout-seconds", 10);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("HistoricalPlayerResolverTest"));
        when(server.getWorlds()).thenReturn(List.of(world));
        when(server.getWorld("world")).thenReturn(world);
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("world");
        Database result = new Database(plugin);
        result.init();
        return result;
    }

    private Connection open() throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("fragguard.db"));
    }
}
