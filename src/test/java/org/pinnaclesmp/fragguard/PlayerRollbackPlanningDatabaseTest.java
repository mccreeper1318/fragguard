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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerRollbackPlanningDatabaseTest {
    private static final String STEVE = "11111111-2222-3333-4444-555555555555";
    private static final String ALEX = "11111111-2222-3333-4444-666666666666";
    private static final String AIR = "minecraft:air";
    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";
    private static final String GLASS = "minecraft:glass";
    @TempDir Path directory;
    private Database database;

    @AfterEach
    void close() {
        if (database != null) {
            database.shutdown();
        }
    }

    @Test
    void plansDirectAndCausalExplosionsProjectilesTntChainsAndFlyingMachinesAcrossWorlds()
            throws Exception {
        database = start();
        long now = System.currentTimeMillis();
        long base = now - 600_000L;
        database.insertRequiredAsync(List.of(
                change(base + 1000, STEVE, "OldBuilder", "world", 1, AIR, STONE),
                causal(base + 2000, "tnt-1", "Explosion: TNT", "world_nether",
                        100, STONE, AIR, "TNT_ENTITY"),
                causal(base + 3000, "tnt-2", "Explosion: TNT", "world",
                        -120, GLASS, AIR, "TNT_CHAIN"),
                causal(base + 4000, "projectile-tnt", "Explosion: TNT", "world",
                        200, STONE, AIR, "TNT_ENTITY"),
                causal(base + 5000, "SYSTEM", "Piston Extend", "world_nether",
                        -300, STONE, DIRT, "PISTON"),
                causal(base + 6000, "SYSTEM", "Piston Retract", "world",
                        400, STONE, GLASS, "PISTON"),
                change(base + 7000, ALEX, "OtherBuilder", "world", 10, AIR, STONE)
        )).join();

        var plan = plan("OldBuilder", base, now, false, 30, 50, 1024);
        assertEquals(UUID.fromString(STEVE), plan.player().uuid());
        assertEquals(5 + 1, plan.reversibleCoordinates());
        assertEquals(6, plan.candidateCoordinates());
        assertEquals(0, plan.historicalConflicts());
        assertEquals(0, plan.noOps());
        assertEquals(0, plan.unavailable());
        Map<Integer, RollbackTarget> targets = plan.targets().stream()
                .collect(Collectors.toMap(RollbackTarget::x, Function.identity()));
        assertEquals(AIR, targets.get(1).blockData());
        assertEquals(STONE, targets.get(1).expectedData());
        assertEquals(STONE, targets.get(100).blockData());
        assertEquals(AIR, targets.get(100).expectedData());
        assertEquals("world_nether", targets.get(100).worldName());
        assertEquals(GLASS, targets.get(-120).blockData());
        assertEquals(STONE, targets.get(-300).blockData());
        assertEquals("world_nether", targets.get(-300).worldName());
        assertEquals(STONE, targets.get(400).blockData());
    }

    @Test
    void forceNeverBypassesOtherPlayerOrSystemHistoryAndCountsProtectedOlderRows()
            throws Exception {
        database = start();
        long now = System.currentTimeMillis();
        long base = now - 200_000L;
        database.insertRequiredAsync(List.of(
                // Steve's latest work must remain protected by Alex.
                change(base + 1000, STEVE, "Steve", "world", 1, AIR, STONE),
                change(base + 2000, ALEX, "Alex", "world", 1, STONE, DIRT),
                // Steve may undo latest glass to Alex's dirt, but no farther.
                change(base + 1000, STEVE, "Steve", "world", 2, AIR, STONE),
                change(base + 2000, ALEX, "Alex", "world", 2, STONE, DIRT),
                change(base + 3000, STEVE, "Steve", "world", 2, DIRT, GLASS),
                // Same causal rule for TNT, including ownership takeover.
                causal(base + 1000, "steve-tnt", "Explosion: TNT", "world",
                        3, AIR, STONE, "TNT_ENTITY"),
                causal(base + 2000, "alex-tnt", "Explosion: TNT", "world",
                        3, STONE, DIRT, "TNT_CHAIN", ALEX, "Alex"),
                // Unattributed later environmental change is a boundary too.
                change(base + 1000, STEVE, "Steve", "world", 4, AIR, STONE),
                change(base + 2000, "SYSTEM", "Fire Burn", "world", 4, STONE, AIR)
        )).join();

        var normal = plan("Steve", base, now, false, 30, 100, 1024);
        var force = plan(STEVE, base, now, true, 30, 100, 1024);
        assertEquals(1, normal.targets().size());
        assertEquals(1, force.targets().size());
        assertEquals(4, normal.historicalConflicts(),
                "the reversible latest Steve edit also has older Steve work protected behind Alex");
        assertEquals(normal.historicalConflicts(), force.historicalConflicts());
        assertEquals(2, normal.targets().getFirst().x());
        assertEquals(DIRT, normal.targets().getFirst().blockData());
        assertEquals(GLASS, normal.targets().getFirst().expectedData());
        assertEquals(DIRT, force.targets().getFirst().blockData());
        assertEquals(List.of(2), force.targets().stream().map(RollbackTarget::x).toList());
    }

    @Test
    void samePlayerChainsNoOpsLegacyContainersAndDiscontinuousSnapshotsAreSafe()
            throws Exception {
        database = start();
        long now = System.currentTimeMillis();
        long base = now - 200_000L;
        byte[] before = {1, 2, 3};
        byte[] middle = {4, 5};
        byte[] after = {6, 7, 8};
        database.insertRequiredAsync(List.of(
                change(base + 1000, STEVE, "Steve", "world", 1, AIR, STONE),
                change(base + 2000, STEVE, "Steve", "world", 1, STONE, GLASS),
                change(base + 1000, STEVE, "Steve", "world", 2, AIR, STONE),
                change(base + 2000, STEVE, "Steve", "world", 2, STONE, AIR),
                entity(base + 1000, STEVE, "world", 3,
                        "minecraft:chest", "minecraft:chest", before, middle),
                entity(base + 2000, STEVE, "world", 3,
                        "minecraft:chest", "minecraft:chest", middle.clone(), after),
                entity(base + 1000, STEVE, "world", 4,
                        "minecraft:chest", "minecraft:chest", null, middle),
                // A physically inconsistent ledger cannot be used to plan.
                change(base + 1000, STEVE, "Steve", "world", 5, AIR, STONE),
                change(base + 2000, STEVE, "Steve", "world", 5, DIRT, GLASS),
                // Both endpoints are ordinary blocks; the intervening
                // legacy container state is unknown and must be skipped.
                change(base + 1000, STEVE, "Steve", "world", 6, STONE, "minecraft:shulker_box"),
                change(base + 2000, STEVE, "Steve", "world", 6, "minecraft:shulker_box", DIRT),
                change(base + 1000, STEVE, "Steve", "world", 7, STONE, "minecraft:oak_shelf"),
                change(base + 2000, STEVE, "Steve", "world", 7, "minecraft:oak_shelf", DIRT)
        )).join();

        var result = plan(STEVE, base, now, false, 30, 50, 1024);
        assertEquals(2, result.reversibleCoordinates());
        assertEquals(1, result.noOps());
        assertEquals(4, result.unavailable(),
                "missing intermediate shulker and shelf snapshots must fail closed");
        assertEquals(0, result.historicalConflicts());
        Map<Integer, RollbackTarget> targets = result.targets().stream()
                .collect(Collectors.toMap(RollbackTarget::x, Function.identity()));
        assertEquals(AIR, targets.get(1).blockData());
        assertEquals(GLASS, targets.get(1).expectedData());
        assertArrayEquals(before, targets.get(3).targetEntityData());
        assertArrayEquals(after, targets.get(3).expectedEntityData());
    }

    @Test
    void knownPostSnapshotWritesCannotBeOverwrittenEvenInForceMode() throws Exception {
        database = start();
        long now = System.currentTimeMillis();
        database.insertRequiredAsync(List.of(
                change(now - 10_000, STEVE, "Steve", "world", 11, AIR, STONE),
                change(now - 8_000, ALEX, "Alex", "world", 11, STONE, GLASS),
                change(now - 10_000, STEVE, "Steve", "world", 12, AIR, STONE),
                change(now - 8_000, STEVE, "Steve", "world", 12, STONE, DIRT)
        )).join();
        long snapshotBeforeLaterWrites = now - 9_000;
        var normal = plan(STEVE, now - 20_000, snapshotBeforeLaterWrites,
                false, 30, 100, 1024);
        var force = plan(STEVE, now - 20_000, snapshotBeforeLaterWrites,
                true, 30, 100, 1024);
        assertTrue(normal.targets().isEmpty());
        assertTrue(force.targets().isEmpty(),
                "force must not apply a stale plan over newer recorded ownership");
        assertEquals(1, normal.historicalConflicts(),
                "another player's known post-snapshot edit is a historical conflict");
        assertEquals(1, normal.unavailable(),
                "even the target player's post-snapshot edit requires a fresh preview");
        assertEquals(normal.historicalConflicts(), force.historicalConflicts());
        assertEquals(normal.unavailable(), force.unavailable());
    }

    @Test
    void enforcesTimeRetentionAndConfiguredBlockAndSnapshotBounds()
            throws Exception {
        database = start();
        long now = System.currentTimeMillis();
        long old = now - 2 * 86_400_000L;
        database.insertRequiredAsync(List.of(
                change(old, STEVE, "Steve", "world", 1, AIR, STONE),
                change(now - 5_000, STEVE, "Steve", "world", 2, AIR, STONE),
                change(now - 4_000, STEVE, "Steve", "world", 3, AIR, DIRT),
                entity(now - 3_000, STEVE, "world", 4,
                        AIR, "minecraft:chest", null, new byte[]{1, 2, 3, 4})
        )).join();
        var retained = plan(STEVE, now - 3 * 86_400_000L,
                now, false, 1, 100, 1024);
        assertEquals(3, retained.reversibleCoordinates());
        assertFalse(retained.targets().stream().anyMatch(t -> t.x() == 1));
        CompletionException blockLimit = assertThrows(CompletionException.class,
                () -> plan(STEVE, now - 60_000L, now, false, 30, 2, 1024));
        assertInstanceOf(Database.PlayerRollbackLimitExceededException.class,
                blockLimit.getCause());
        CompletionException byteLimit = assertThrows(CompletionException.class,
                () -> plan(STEVE, now - 60_000L, now, false, 30, 100, 3));
        assertInstanceOf(Database.RollbackSnapshotLimitExceededException.class,
                byteLimit.getCause());
    }

    @Test
    void renamedWorldUsesStableUuidEvenWhenOldDisplayNameIsReused() throws Exception {
        database = start("original_world", true, false);
        long now = System.currentTimeMillis();
        database.insertRequiredAsync(List.of(change(now - 10_000, STEVE, "Steve",
                "original_world", 22, AIR, STONE))).join();
        database.shutdown();
        database = start("renamed_world", true, true);

        var planned = plan(STEVE, now - 20_000, now, false, 30, 100, 1024);
        assertEquals(1, planned.reversibleCoordinates());
        assertEquals(0, planned.unavailable());
        assertEquals("renamed_world", planned.targets().getFirst().worldName(),
                "history's old display name must not become an execution target");
        assertNotEquals("original_world", planned.targets().getFirst().worldName(),
                "a different loaded world can reuse the original name");
    }

    @Test
    void unloadedWorldIsUnavailableWithoutGuessingByHistoricalDisplayName() throws Exception {
        database = start();
        long now = System.currentTimeMillis();
        database.insertRequiredAsync(List.of(change(now - 10_000, STEVE, "Steve",
                "world_nether", 23, AIR, STONE))).join();
        database.shutdown();
        database = start("world", false, false);

        var planned = plan(STEVE, now - 20_000, now, true, 30, 100, 1024);
        assertEquals(1, planned.candidateCoordinates());
        assertEquals(1, planned.unavailable());
        assertEquals(0, planned.reversibleCoordinates());
        assertTrue(planned.targets().isEmpty(),
                "force must not route an unloaded world's history to a named substitute");
    }

    @Test
    void versionSevenUpgradeUsesExistingChunkIndexWithoutAllocatingGlobalCoordinateIndex() throws Exception {
        database = start();
        long now = System.currentTimeMillis();
        database.insertRequiredAsync(List.of(change(now - 10_000, STEVE, "Steve",
                "world", 24, AIR, STONE))).join();
        database.shutdown();
        database = null;

        try (Connection connection = open(); Statement sql = connection.createStatement()) {
            sql.execute("DROP INDEX IF EXISTS idx_fg_coordinate_time");
            sql.execute("PRAGMA user_version=7");
        }
        database = start();

        try (Connection connection = open(); Statement sql = connection.createStatement()) {
            try (ResultSet version = sql.executeQuery("PRAGMA user_version")) {
                assertTrue(version.next());
                assertEquals(Database.SCHEMA_VERSION, version.getInt(1));
            }
            try (ResultSet oldIndex = sql.executeQuery("""
                    SELECT COUNT(*) FROM sqlite_master
                    WHERE type = 'index' AND name = 'idx_fg_coordinate_time'
                    """)) {
                assertTrue(oldIndex.next());
                assertEquals(0, oldIndex.getInt(1), "v7 must not build a 57M-row coordinate index");
            }
            try (ResultSet plan = sql.executeQuery("""
                    EXPLAIN QUERY PLAN
                    SELECT id, happened_at, before_data FROM block_changes
                    WHERE rollback_pending = 0 AND world_uuid =
                          '99999999-2222-3333-4444-555555555555'
                      AND chunk_x=1 AND chunk_z=0
                      AND x=24 AND y=64 AND z=0
                      AND happened_at > 0 AND happened_at <= 9999999999999
                    ORDER BY happened_at DESC, id DESC
                    LIMIT 8193
                    """)) {
                List<String> details = new ArrayList<>();
                while (plan.next()) {
                    details.add(plan.getString("detail"));
                }
                assertTrue(details.stream().anyMatch(line -> line.contains("idx_fg_spatial_time")),
                        "per-coordinate history must reuse existing chunk/time index: " + details);
                assertFalse(details.stream().anyMatch(line -> line.contains("USE TEMP B-TREE")),
                        "newest-first history should not need a temporary sort: " + details);
            }
        }

        var planned = plan(STEVE, now - 20_000, now, false, 30, 100, 1024);
        assertEquals(1, planned.targets().size(), "index reuse must preserve historical rollback results");
    }

    private PlayerRollbackPlan plan(String name, long start, long end, boolean force,
                                    int days, int maxBlocks, long maxBytes) {
        return database.planPlayerRollbackAsync(name, start, end, maxBlocks,
                maxBytes, days, force).join();
    }

    private BlockChange change(long at, String actor, String name, String world,
                               int x, String before, String after) {
        return new BlockChange(at, actor, name, world, x, 64, 0,
                ChangeAction.BREAK, before, after);
    }

    private BlockChange causal(long at, String physical, String label, String world,
                               int x, String before, String after, String cause) {
        return causal(at, physical, label, world, x, before, after, cause, STEVE, "Steve");
    }

    private BlockChange causal(long at, String physical, String label, String world,
                               int x, String before, String after, String cause,
                               String player, String name) {
        return change(at, physical, label, world, x, before, after)
                .withCausalAttribution(new CausalAttribution(player, name, "chain", cause));
    }

    private BlockChange entity(long at, String actor, String world, int x,
                               String before, String after, byte[] beforeEntity, byte[] afterEntity) {
        return new BlockChange(at, actor, "Steve", world, x, 64, 0,
                ChangeAction.PLACE, before, after, beforeEntity, afterEntity);
    }

    private Database start() throws Exception {
        return start("world", true, false);
    }

    private Database start(String currentWorldName, boolean loadNether,
                           boolean reuseOriginalWorldName) throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        World nether = mock(World.class);
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("database-query-timeout-seconds", 10);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getConfig()).thenReturn(cfg);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("PlayerRollbackPlanningDatabaseTest"));

        List<World> loaded = new ArrayList<>();
        when(world.getName()).thenReturn(currentWorldName);
        when(world.getUID()).thenReturn(UUID.fromString("99999999-2222-3333-4444-555555555555"));
        loaded.add(world);
        when(server.getWorld(currentWorldName)).thenReturn(world);
        if (loadNether) {
            when(nether.getName()).thenReturn("world_nether");
            when(nether.getUID()).thenReturn(UUID.fromString("99999999-2222-3333-4444-666666666666"));
            when(server.getWorld("world_nether")).thenReturn(nether);
            loaded.add(nether);
        }
        if (reuseOriginalWorldName) {
            World reused = mock(World.class);
            when(reused.getName()).thenReturn("original_world");
            when(reused.getUID()).thenReturn(UUID.fromString("99999999-2222-3333-4444-777777777777"));
            when(server.getWorld("original_world")).thenReturn(reused);
            loaded.add(reused);
        }
        when(server.getWorlds()).thenReturn(loaded);
        Database instance = new Database(plugin);
        instance.init();
        return instance;
    }

    private Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("fragguard.db"));
    }
}
