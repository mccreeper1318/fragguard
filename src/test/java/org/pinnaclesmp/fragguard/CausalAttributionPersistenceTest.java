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
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CausalAttributionPersistenceTest {
    @TempDir Path directory;
    private Database database;

    @AfterEach void close() {
        if (database != null) {
            database.shutdown();
        }
    }

    @Test
    void storesImmediateActorAndSeparateCausalOwnershipForQueries() throws Exception {
        database = start();
        String player = "11111111-2222-3333-4444-555555555555";
        BlockChange blast = new BlockChange(1000L, "tnt-uuid", "Explosion: TNT",
                "world", 1, 64, 1, ChangeAction.EXPLOSION, "minecraft:stone", "minecraft:air")
                .withCausalAttribution(new CausalAttribution(player, "Builder", "chain-1", "TNT_ENTITY"));
        BlockChange piston = new BlockChange(1001L, "SYSTEM", "Piston Extend",
                "world", 2, 64, 1, ChangeAction.PISTON_EXTEND, "minecraft:stone", "minecraft:air")
                .withCausalAttribution(new CausalAttribution(player, "Builder", "chain-1", "PISTON"));
        BlockChange unrelated = new BlockChange(1002L, "SYSTEM", "Natural",
                "world", 3, 64, 1, ChangeAction.EXPLOSION, "minecraft:stone", "minecraft:air");
        database.insertRequiredAsync(List.of(blast, piston, unrelated)).join();

        try (Connection db = open(); Statement stmt = db.createStatement()) {
            try (ResultSet rows = stmt.executeQuery("""
                    SELECT actor_uuid, actor_name, causal_player_uuid, causal_player_name,
                           causal_chain_id, causal_cause_type
                    FROM block_changes WHERE x=1
                    """)) {
                assertTrue(rows.next());
                assertEquals("tnt-uuid", rows.getString("actor_uuid"));
                assertEquals("Explosion: TNT", rows.getString("actor_name"));
                assertEquals(player, rows.getString("causal_player_uuid"));
                assertEquals("Builder", rows.getString("causal_player_name"));
                assertEquals("chain-1", rows.getString("causal_chain_id"));
                assertEquals("TNT_ENTITY", rows.getString("causal_cause_type"));
            }
            try (ResultSet rows = stmt.executeQuery("""
                    SELECT COUNT(*) FROM block_changes WHERE (actor_uuid =
                    '11111111-2222-3333-4444-555555555555' OR causal_player_uuid =
                    '11111111-2222-3333-4444-555555555555')
                    """)) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1), "player queries include both attributed TNT and piston rows");
            }
            try (ResultSet rows = stmt.executeQuery(
                    "SELECT causal_player_uuid FROM block_changes WHERE x=3")) {
                assertTrue(rows.next());
                assertNull(rows.getString(1), "untraceable events must remain unattributed");
            }
        }
    }

    @Test
    void schemaV5UpgradeAddsOptionalMetadataWithoutChangingExistingRows() throws Exception {
        database = start();
        database.insertRequiredAsync(List.of(new BlockChange(1000L, "SYSTEM", "Old",
                "world", 5, 70, 5, ChangeAction.BREAK, "minecraft:stone", "minecraft:air"))).join();
        database.shutdown();
        database = null;

        try (Connection db = open(); Statement stmt = db.createStatement()) {
            stmt.execute("DROP INDEX IF EXISTS idx_fg_causal_actor_time");
            stmt.execute("DROP INDEX IF EXISTS idx_fg_causal_actor_world_time");
            stmt.execute("ALTER TABLE block_changes DROP COLUMN causal_player_uuid");
            stmt.execute("ALTER TABLE block_changes DROP COLUMN causal_player_name");
            stmt.execute("ALTER TABLE block_changes DROP COLUMN causal_chain_id");
            stmt.execute("ALTER TABLE block_changes DROP COLUMN causal_cause_type");
            stmt.execute("PRAGMA user_version=5");
        }

        database = start();
        try (Connection db = open(); Statement stmt = db.createStatement()) {
            try (ResultSet row = stmt.executeQuery("PRAGMA user_version")) {
                assertTrue(row.next());
                assertEquals(Database.SCHEMA_VERSION, row.getInt(1));
            }
            try (ResultSet row = stmt.executeQuery("""
                    SELECT actor_uuid, causal_player_uuid, after_data
                    FROM block_changes WHERE x=5 AND y=70 AND z=5
                    """)) {
                assertTrue(row.next());
                assertEquals("SYSTEM", row.getString(1));
                assertNull(row.getString(2));
                assertEquals("minecraft:air", row.getString(3));
            }
            for (String index : List.of("idx_fg_causal_actor_time", "idx_fg_causal_actor_world_time")) {
                try (ResultSet row = stmt.executeQuery(
                        "SELECT 1 FROM sqlite_master WHERE type='index' AND name='" + index + "'")) {
                    assertTrue(row.next(), "required causal query index missing: " + index);
                }
            }
        }
    }

    private Database start() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        YamlConfiguration config = new YamlConfiguration();
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CausalAttributionPersistenceTest"));
        when(server.getWorlds()).thenReturn(List.of(world));
        when(server.getWorld("world")).thenReturn(world);
        when(world.getUID()).thenReturn(UUID.fromString("99999999-2222-3333-4444-555555555555"));
        when(world.getName()).thenReturn("world");
        Database result = new Database(plugin);
        result.init();
        return result;
    }

    private Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("fragguard.db"));
    }
}
