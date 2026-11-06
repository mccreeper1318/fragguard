package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoordinateHistoryIndexPlanTest {
    @TempDir Path directory;

    private static final String WORLD = "11111111-1111-1111-1111-111111111111";
    private static final String NETHER = "22222222-2222-2222-2222-222222222222";

    @Test
    void chunkSeekPreservesFullHistoryPhysicalCausesTimeOrderingAndWorldBoundaries()
            throws Exception {
        Path file = directory.resolve("coordinate-query.db");
        try (Connection db = create(file)) {
            // Same negative coordinate, including a later environmental action
            // and same-time ID ordering. Player rollback may not skip either.
            insert(db, 1, WORLD, -17, 64, -33, 100, "PLAYER", 0);
            insert(db, 2, WORLD, -17, 64, -33, 200, "SYSTEM", 0);
            insert(db, 3, WORLD, -17, 64, -33, 200, "OTHER_PLAYER", 0);
            insert(db, 4, WORLD, -17, 64, -33, 300, "PENDING_AUDIT", 1);
            // Neighboring block, same chunk: must not leak into exact history.
            insert(db, 5, WORLD, -18, 64, -33, 250, "OTHER_PLAYER", 0);
            // Same block coordinates in another world: must not leak either.
            insert(db, 6, NETHER, -17, 64, -33, 240, "NETHER_PLAYER", 0);

            CoordinateHistoryIndexPlan.verifyExistingHistory(db);

            assertPlanUsesChunkIndex(db, CoordinateHistoryIndexPlan.TIMELINE_SQL, true);
            assertPlanUsesChunkIndex(db, CoordinateHistoryIndexPlan.STALE_CHECK_SQL, false);
            assertEquals(List.of(3L, 2L, 1L), timeline(db, WORLD, -17, 64, -33),
                    "both system and other-player edits must be visible in newest-first order");
            assertEquals(List.of(6L), timeline(db, NETHER, -17, 64, -33),
                    "identical XYZ in another world is independent");
            assertTrue(stale(db, WORLD, -17, 64, -33, 199),
                    "later changes by another player must invalidate player rollback preview");
            assertFalse(stale(db, WORLD, -17, 64, -33, 200),
                    "a pending audit does not count as a committed block edit");
            assertEquals(6, scalar(db, "SELECT COUNT(*) FROM block_changes"));
            assertEquals(0, scalar(db, """
                    SELECT COUNT(*) FROM sqlite_master
                    WHERE type='index' AND name='idx_fg_coordinate_time'
                    """));
        }
    }

    @Test
    void inconsistentOrMissingLegacyChunkIndexFailsClosedWithoutRewritingHistory() throws Exception {
        Path file = directory.resolve("bad-chunks.db");
        try (Connection db = create(file)) {
            insert(db, 1, WORLD, -17, 64, -33, 100, "PLAYER", 0);
            try (Statement stmt = db.createStatement()) {
                stmt.execute("DROP INDEX idx_fg_spatial_time");
            }
            SQLException missing = assertThrows(SQLException.class,
                    () -> CoordinateHistoryIndexPlan.verifyExistingHistory(db));
            assertTrue(missing.getMessage().contains("idx_fg_spatial_time"));
            try (Statement stmt = db.createStatement()) {
                stmt.execute("""
                        CREATE INDEX idx_fg_spatial_time
                        ON block_changes(world_uuid, chunk_x, chunk_z, happened_at)
                        """);
                // A malformed legacy chunk must never cause a player rollback
                // to silently ignore this later physical/system change.
                stmt.execute("UPDATE block_changes SET chunk_x = 0 WHERE id = 1");
            }
            SQLException inconsistent = assertThrows(SQLException.class,
                    () -> CoordinateHistoryIndexPlan.verifyExistingHistory(db));
            assertTrue(inconsistent.getMessage().contains("inconsistent historical chunk coordinates"));
            assertEquals(7, scalar(db, "PRAGMA user_version"));
            assertEquals(1, scalar(db, "SELECT COUNT(*) FROM block_changes"));

            try (Statement stmt = db.createStatement()) {
                stmt.execute("UPDATE block_changes SET chunk_x = x >> 4 WHERE id = 1");
            }
            assertDoesNotThrow(() -> CoordinateHistoryIndexPlan.verifyExistingHistory(db));
            assertEquals(7, scalar(db, "PRAGMA user_version"));
        }
    }

    @Test
    void v7ToV8OnDenseHistoryNeedsNoAdditionalIndexPagesEvenWithTightPageLimit()
            throws Exception {
        Path file = directory.resolve("dense-v7.db");
        final int records = 22_000;
        try (Connection db = create(file)) {
            db.setAutoCommit(false);
            try (PreparedStatement insert = db.prepareStatement("""
                    INSERT INTO block_changes
                    (id, happened_at, actor_uuid, actor_name, world, world_uuid,
                     x, y, z, chunk_x, chunk_z, rollback_pending,
                     before_data, after_data)
                    VALUES (?, ?, 'SYSTEM', 'System', 'world', ?, ?, 64, ?, ?, ?, 0,
                            'minecraft:stone', 'minecraft:air')
                    """)) {
                for (int i = 0; i < records; i++) {
                    int x = (i % 110) - 55;
                    int z = (i / 110) - 100;
                    insert.setInt(1, i + 1);
                    insert.setLong(2, 1_000L + i);
                    insert.setString(3, i % 2 == 0 ? WORLD : NETHER);
                    insert.setInt(4, x);
                    insert.setInt(5, z);
                    insert.setInt(6, x >> 4);
                    insert.setInt(7, z >> 4);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            db.commit();
            db.setAutoCommit(true);
            long before = scalar(db, "PRAGMA page_count");
            try (Statement stmt = db.createStatement();
                 ResultSet constrained = stmt.executeQuery("PRAGMA max_page_count=" + (before + 1))) {
                assertTrue(constrained.next());
                assertEquals(before + 1, constrained.getLong(1));
            }

            CoordinateHistoryIndexPlan.verifyExistingHistory(db);
            try (Statement stmt = db.createStatement()) {
                stmt.execute("PRAGMA user_version=8");
            }
            assertEquals(8, scalar(db, "PRAGMA user_version"));
            assertEquals(records, scalar(db, "SELECT COUNT(*) FROM block_changes"));
            assertEquals(before, scalar(db, "PRAGMA page_count"),
                    "v7→v8 must not allocate pages for a new global coordinate index");
            assertEquals(0, scalar(db, """
                    SELECT COUNT(*) FROM sqlite_master
                    WHERE type='index' AND name='idx_fg_coordinate_time'
                    """));
            assertEquals("ok", scalarText(db, "PRAGMA integrity_check(1)"));
        }
        try (Connection reopened = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            assertEquals(8, scalar(reopened, "PRAGMA user_version"));
            assertEquals(records, scalar(reopened, "SELECT COUNT(*) FROM block_changes"));
            assertEquals("ok", scalarText(reopened, "PRAGMA integrity_check(1)"));
        }
    }

    private static Connection create(Path path) throws SQLException {
        Connection db = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement stmt = db.createStatement()) {
            stmt.execute("PRAGMA journal_mode=DELETE");
            stmt.execute("""
                    CREATE TABLE block_changes (
                        id INTEGER PRIMARY KEY, happened_at INTEGER NOT NULL,
                        actor_uuid TEXT NOT NULL, actor_name TEXT NOT NULL,
                        causal_player_uuid TEXT, causal_chain_id TEXT, causal_cause_type TEXT,
                        world TEXT NOT NULL, world_uuid TEXT NOT NULL,
                        x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
                        chunk_x INTEGER NOT NULL, chunk_z INTEGER NOT NULL,
                        rollback_pending INTEGER NOT NULL DEFAULT 0,
                        before_data TEXT NOT NULL, after_data TEXT NOT NULL,
                        before_entity_data BLOB, after_entity_data BLOB
                    )
                    """);
            stmt.execute("""
                    CREATE INDEX idx_fg_spatial_time
                    ON block_changes(world_uuid, chunk_x, chunk_z, happened_at)
                    """);
            stmt.execute("PRAGMA user_version=7");
        }
        return db;
    }

    private static void insert(Connection db, int id, String world, int x, int y, int z,
                               long time, String actor, int pending) throws SQLException {
        try (PreparedStatement stmt = db.prepareStatement("""
                INSERT INTO block_changes
                (id, happened_at, actor_uuid, actor_name, world, world_uuid,
                 x, y, z, chunk_x, chunk_z, rollback_pending, before_data, after_data)
                VALUES (?, ?, ?, ?, 'world', ?, ?, ?, ?, ?, ?, ?,
                        'minecraft:stone', 'minecraft:air')
                """)) {
            stmt.setInt(1, id);
            stmt.setLong(2, time);
            stmt.setString(3, actor);
            stmt.setString(4, actor);
            stmt.setString(5, world);
            stmt.setInt(6, x);
            stmt.setInt(7, y);
            stmt.setInt(8, z);
            stmt.setInt(9, x >> 4);
            stmt.setInt(10, z >> 4);
            stmt.setInt(11, pending);
            stmt.executeUpdate();
        }
    }

    private static List<Long> timeline(Connection db, String world, int x, int y, int z)
            throws SQLException {
        try (PreparedStatement stmt = db.prepareStatement(CoordinateHistoryIndexPlan.TIMELINE_SQL)) {
            stmt.setString(1, world);
            stmt.setInt(2, x >> 4);
            stmt.setInt(3, z >> 4);
            stmt.setInt(4, x);
            stmt.setInt(5, y);
            stmt.setInt(6, z);
            stmt.setLong(7, 0);
            stmt.setLong(8, 1_000_000);
            stmt.setInt(9, 8193);
            List<Long> ids = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong("id"));
            }
            return ids;
        }
    }

    private static boolean stale(Connection db, String world, int x, int y, int z, long snapshot)
            throws SQLException {
        try (PreparedStatement stmt = db.prepareStatement(CoordinateHistoryIndexPlan.STALE_CHECK_SQL)) {
            stmt.setString(1, world);
            stmt.setInt(2, x >> 4);
            stmt.setInt(3, z >> 4);
            stmt.setInt(4, x);
            stmt.setInt(5, y);
            stmt.setInt(6, z);
            stmt.setLong(7, snapshot);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void assertPlanUsesChunkIndex(Connection db, String query, boolean timeline)
            throws SQLException {
        try (PreparedStatement stmt = db.prepareStatement("EXPLAIN QUERY PLAN " + query)) {
            stmt.setString(1, WORLD);
            stmt.setInt(2, -2);
            stmt.setInt(3, -3);
            stmt.setInt(4, -17);
            stmt.setInt(5, 64);
            stmt.setInt(6, -33);
            stmt.setLong(7, timeline ? 0 : 150);
            if (timeline) {
                stmt.setLong(8, 1_000_000);
                stmt.setInt(9, 8193);
            }
            List<String> details = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) details.add(rs.getString("detail"));
            }
            assertTrue(details.stream().anyMatch(x -> x.contains("idx_fg_spatial_time")),
                    "player rollback must seek the preexisting world/chunk/time index: " + details);
            assertFalse(details.stream().anyMatch(x -> x.contains("USE TEMP B-TREE")),
                    "newest-first timeline cannot require a global sort: " + details);
        }
    }

    private static long scalar(Connection db, String sql) throws SQLException {
        try (Statement stmt = db.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private static String scalarText(Connection db, String sql) throws SQLException {
        try (Statement stmt = db.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }
}
