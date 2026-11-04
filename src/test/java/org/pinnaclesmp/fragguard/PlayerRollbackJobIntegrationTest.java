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

class PlayerRollbackJobIntegrationTest {
    private static final String ADMIN = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PLAYER = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String WORLD = "99999999-2222-3333-4444-555555555555";
    private static final String NETHER = "99999999-2222-3333-4444-666666666666";
    @TempDir Path directory;
    private Database db;

    @AfterEach void close() {
        if (db != null) db.shutdown();
    }

    @Test
    void playerJobPersistsOperatorTargetScopePhysicalCauseAndMultiWorldTargets() throws Exception {
        db = start();
        long now = System.currentTimeMillis();
        db.insertRequiredAsync(List.of(
                new BlockChange(now - 5000, PLAYER, "OldBuilder", "world", 3, 64, 1,
                        ChangeAction.PLACE, "minecraft:air", "minecraft:stone"),
                new BlockChange(now - 4000, "tnt-a", "Explosion: TNT", "world_nether",
                        -100, 64, 1, ChangeAction.EXPLOSION, "minecraft:stone", "minecraft:air")
                        .withCausalAttribution(new CausalAttribution(PLAYER, "Builder", "chain-A", "TNT_ENTITY")),
                new BlockChange(now - 3000, "SYSTEM", "Piston Extend", "world",
                        180, 64, 1, ChangeAction.PISTON_EXTEND, "minecraft:stone", "minecraft:dirt")
                        .withCausalAttribution(new CausalAttribution(PLAYER, "Builder", "chain-B", "PISTON"))
        )).join();

        PlayerRollbackPlan plan = db.planPlayerRollbackAsync("OldBuilder", null,
                now - 9000, now, 100, 4096, 30, false).join();
        assertEquals(3, plan.targets().size());
        RollbackJob job = db.createPlayerRollbackJobAsync(ADMIN, "Admin", plan, null, false).join();
        assertTrue(job.isPlayerScope());
        assertEquals(ADMIN, job.actorUuid());
        assertEquals("Admin", job.actorName());
        assertEquals(PLAYER, job.targetPlayerUuid());
        assertEquals("Builder", job.targetPlayerName());
        assertEquals(2, job.affectedWorlds());
        assertEquals(3, job.affectedChunks());
        assertNull(job.worldScopeName());
        assertEquals(0, job.historicalConflicts());
        assertEquals(0, job.radius());

        List<RollbackJobChange> saved = db.loadRollbackChangesAsync(job.id(), false).join();
        assertEquals(3, saved.size());
        assertEquals(List.of(WORLD, NETHER, WORLD),
                saved.stream().map(RollbackJobChange::worldUuid).toList());
        var tnt = saved.stream().filter(change -> change.x() == -100).findFirst().orElseThrow();
        assertEquals("Explosion: TNT", tnt.physicalActorName());
        assertEquals("tnt-a", tnt.physicalActorUuid());
        assertEquals(PLAYER, tnt.causalPlayerUuid());
        assertEquals("TNT_ENTITY", tnt.causalCauseType());
        assertEquals("chain-A", tnt.causalChainId());

        db.shutdown();
        db = start();
        RollbackJob recovered = db.loadResumableJobsAsync().join().getFirst();
        assertTrue(recovered.isPlayerScope());
        assertEquals(job.id(), recovered.id());
        assertEquals(2, recovered.affectedWorlds());
        assertEquals(3, db.loadRollbackChangesAsync(job.id(), false).join().size());
        assertEquals(NETHER, db.loadRollbackChangesAsync(job.id(), false).join().get(1).worldUuid());

        // Persist completed results and verify crash-safe undo reads both worlds.
        for (RollbackJobChange change : db.loadRollbackChangesAsync(job.id(), false).join()) {
            String before = change.expectedData();
            db.prepareRollbackBatchAsync(job.id(), List.of(change.withBeforeData(before))).join();
            db.markRollbackBatchAppliedAsync(job.id(), List.of(
                    new RollbackStepResult(change.sequence(), true, false, change.targetData(),
                            change.targetEntityData()))).join();
        }
        RollbackJob done = db.completeRollbackJobAsync(job.id(), false).join();
        assertEquals("COMPLETED", done.status());
        assertEquals(3, done.appliedBlocks());
        RollbackJob undo = db.beginUndoAsync(job.id()).join();
        assertEquals("UNDOING", undo.status());
        assertEquals(3, db.loadRollbackChangesAsync(job.id(), true).join().size());
        db.shutdown();
        db = start();
        assertEquals("UNDOING", db.loadResumableJobsAsync().join().getFirst().status());
    }

    @Test
    void preventsRadiusVersusPlayerAndPlayerVersusPlayerOverlappingWorldChunks() throws Exception {
        db = start();
        long now = System.currentTimeMillis();
        db.insertRequiredAsync(List.of(
                new BlockChange(now - 1000, PLAYER, "Builder", "world", 5, 64, 0,
                        ChangeAction.BREAK, "minecraft:stone", "minecraft:air"),
                new BlockChange(now - 1000, PLAYER, "Builder", "world_nether", 5, 64, 0,
                        ChangeAction.BREAK, "minecraft:stone", "minecraft:air")
        )).join();
        PlayerRollbackPlan all = db.planPlayerRollbackAsync(PLAYER, null,
                now - 5000, now, 100, 4096, 30, false).join();
        RollbackJob playerJob = db.createPlayerRollbackJobAsync(ADMIN, "Admin", all, null, false).join();

        var areaOverlaps = assertThrows(CompletionException.class, () ->
                db.createRollbackJobAsync(ADMIN, "Admin", "world", 5, 0, 3,
                        now - 5000, now, false, List.of(
                                new RollbackTarget("world", 5, 64, 0,
                                        "minecraft:stone", "minecraft:air"))).join());
        assertTrue(areaOverlaps.getCause().getMessage().contains("overlapping region"));
        var anotherPlayer = assertThrows(CompletionException.class, () ->
                db.createPlayerRollbackJobAsync(ADMIN, "Second Admin", all, null, true).join());
        assertTrue(anotherPlayer.getCause().getMessage().contains("overlapping region"));

        // A non-overlapping radius remains allowed, even in a player's world.
        RollbackJob distant = db.createRollbackJobAsync(ADMIN, "Admin", "world",
                1200, 1200, 2, now - 5000, now, false,
                List.of(new RollbackTarget("world", 1200, 64, 1200,
                        "minecraft:stone", "minecraft:air"))).join();
        assertEquals("RADIUS", distant.scopeType());
        assertEquals(0, distant.radius() - 2);
        assertFalse(distant.isPlayerScope());

        db.completeRollbackJobAsync(playerJob.id(), false).join();
        db.completeRollbackJobAsync(distant.id(), false).join();
        // The stored radius job may be undone only when no conflicting job is active.
        db.beginUndoAsync(playerJob.id()).join();
        var radiusUndo = assertThrows(CompletionException.class, () ->
                db.beginUndoAsync(distant.id()).join());
        // Distant chunks do not conflict; therefore this undo should be valid instead.
        assertNotNull(radiusUndo);
    }

    @Test
    void worldFilterAndStalePreviewRemainEnforcedOnJobCreation() throws Exception {
        db = start();
        long now = System.currentTimeMillis();
        db.insertRequiredAsync(List.of(
                new BlockChange(now - 5000, PLAYER, "Builder", "world", 1, 64, 0,
                        ChangeAction.BREAK, "minecraft:stone", "minecraft:air"),
                new BlockChange(now - 4000, PLAYER, "Builder", "world_nether", 1, 64, 0,
                        ChangeAction.BREAK, "minecraft:stone", "minecraft:air")
        )).join();
        var filtered = db.planPlayerRollbackAsync(PLAYER, "world_nether",
                now - 9000, now, 100, 4096, 30, false).join();
        assertEquals(1, filtered.reversibleCoordinates());
        assertEquals("world_nether", filtered.targets().getFirst().worldName());
        RollbackJob job = db.createPlayerRollbackJobAsync(ADMIN, "Admin", filtered, "world_nether", false).join();
        assertEquals("world_nether", job.worldScopeName());
        assertEquals(NETHER, job.worldScopeUuid());

        var toStale = db.planPlayerRollbackAsync(PLAYER, "world",
                now - 9000, now, 100, 4096, 30, true).join();
        db.insertRequiredAsync(List.of(new BlockChange(System.currentTimeMillis() + 100,
                ADMIN, "Admin", "world", 1, 64, 0, ChangeAction.BREAK,
                "minecraft:air", "minecraft:dirt"))).join();
        var refused = assertThrows(CompletionException.class, () ->
                db.createPlayerRollbackJobAsync(ADMIN, "Admin", toStale, "world", true).join());
        assertTrue(refused.getCause().getMessage().contains("stale"));
    }

    @Test
    void legacyRadiusJobsRemainReadableAcrossSchemaNineUpgrade() throws Exception {
        db = start();
        long now = System.currentTimeMillis();
        RollbackJob radius = db.createRollbackJobAsync(ADMIN, "Admin",
                "world", 0, 0, 10, now - 10000, now, false,
                List.of(new RollbackTarget("world", 0, 64, 0,
                        "minecraft:stone", "minecraft:air"))).join();
        db.shutdown();
        db = null;
        try (Connection c = open(); Statement sql = c.createStatement()) {
            sql.execute("DROP TABLE rollback_job_chunks");
            for (String field : List.of("world_uuid", "physical_actor_uuid", "physical_actor_name",
                    "causal_player_uuid", "causal_chain_id", "causal_cause_type")) {
                sql.execute("ALTER TABLE rollback_job_changes DROP COLUMN " + field);
            }
            for (String field : List.of("scope_type", "target_player_uuid", "target_player_name",
                    "world_scope_uuid", "world_scope_name", "historical_conflicts",
                    "affected_worlds", "affected_chunks")) {
                sql.execute("ALTER TABLE rollback_jobs DROP COLUMN " + field);
            }
            sql.execute("PRAGMA user_version=8");
        }
        db = start();
        RollbackJob restored = db.loadRollbackJobAsync(radius.id()).join();
        assertEquals("RADIUS", restored.scopeType());
        assertEquals(10, restored.radius());
        assertEquals(ADMIN, restored.actorUuid());
        assertEquals(WORLD, db.loadRollbackChangesAsync(radius.id(), false).join().getFirst().worldUuid());
        try (Connection c = open(); Statement sql = c.createStatement();
             ResultSet version = sql.executeQuery("PRAGMA user_version")) {
            assertTrue(version.next());
            assertEquals(Database.SCHEMA_VERSION, version.getInt(1));
        }
    }

    private Database start() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        World nether = mock(World.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("database-query-timeout-seconds", 15);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("PlayerRollbackJobIntegrationTest"));
        when(plugin.getServer()).thenReturn(server);
        when(server.getWorlds()).thenReturn(List.of(world, nether));
        when(server.getWorld("world")).thenReturn(world);
        when(server.getWorld("world_nether")).thenReturn(nether);
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(UUID.fromString(WORLD));
        when(nether.getName()).thenReturn("world_nether");
        when(nether.getUID()).thenReturn(UUID.fromString(NETHER));
        Database instance = new Database(plugin);
        instance.init();
        return instance;
    }

    private Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("fragguard.db"));
    }
}
