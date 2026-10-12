package org.pinnaclesmp.fragguard;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Protect indexed area counts and cursor pagination from losing exact history rows. */
class GuiIndexedAreaQueryTest {
    private static final UUID WORLD_UUID = UUID.fromString("563fce36-6445-43e9-9e79-3bb6d0780b13");
    private static final UUID ACTOR_UUID = UUID.fromString("714ea63f-075e-4694-b2c4-ae06a79748aa");

    @TempDir Path temp;
    private Database database;

    @AfterEach
    void shutdown() {
        if (database != null) {
            database.shutdown();
        }
    }

    @Test
    void searchesAllIntersectingPositiveAndNegativeChunksWithIndexedCountAndStablePages() throws Exception {
        database = openDatabase();
        long now = System.currentTimeMillis();
        int expected15 = 0;
        int expected30 = 0;
        List<BlockChange> batch = new ArrayList<>();

        // More than ten thousand changes spread over a 101x101 square. Searches
        // around (0,0) cross the chunk-zero boundary in both directions; many
        // irrelevant distant chunks must not be scanned just to count 15b.
        for (int x = -50; x <= 50; x++) {
            for (int z = -50; z <= 50; z++) {
                int square = x * x + z * z;
                if (square <= 15 * 15) expected15++;
                if (square <= 30 * 30) expected30++;
                batch.add(new BlockChange(now - (long) (x + 50) * 101 - (z + 50),
                        70_000L + (x + 50) * 101L + (z + 50), ACTOR_UUID.toString(),
                        "Builder", "world", x, 64, z, ChangeAction.BREAK,
                        "minecraft:stone", "minecraft:air"));
                if (batch.size() == 400) {
                    database.insertRequiredAsync(List.copyOf(batch)).join();
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            database.insertRequiredAsync(List.copyOf(batch)).join();
        }

        List<String> logs = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger("FGIndexedArea-" + UUID.randomUUID());
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { logs.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.setUseParentHandlers(false);
        logger.addHandler(capture);
        try {
            GuiLookupStore store = new GuiLookupStore(temp.toFile(), 15, logger, true);
            GuiLookupQuery area15 = query(now, 15);
            GuiLookupOverview overview15 = store.prepareOverviewAsync(area15).join();
            assertEquals(expected15, overview15.totalRows());
            assertEquals(expected15, store.countRowsAsync(area15.withMaxRowId(overview15.maxRowId())).join());
            assertTrue(logs.stream().anyMatch(line -> line.contains("EXPLAIN count")
                    && line.contains("idx_fg_spatial_time")
                    && line.contains("chunk_x=?") && line.contains("chunk_z=?")),
                    "the SQLite query plan should use both chunk coordinates in the composite spatial index");

            GuiLookupQuery bounded = area15.withMaxRowId(overview15.maxRowId());
            Set<Long> ids = new HashSet<>();
            GuiLookupCursor cursor = null;
            int pages = 0;
            do {
                GuiRawPage page = store.selectRawPageAsync(bounded, cursor, 36).join();
                for (LookupRow row : page.rows()) {
                    assertTrue((long) row.x() * row.x() + (long) row.z() * row.z() <= 225L);
                    assertTrue(ids.add(row.id()), "cursor paging must not duplicate exact history");
                }
                cursor = page.nextCursor();
                pages++;
                assertTrue(pages <= 100, "pagination must make progress without looping");
            } while (cursor != null);
            assertEquals(expected15, ids.size(),
                    "every event within 15 blocks must be reachable, even across negative chunk boundaries");

            GuiLookupOverview overview30 = store.prepareOverviewAsync(query(now, 30)).join();
            assertEquals(expected30, overview30.totalRows());
            GuiRawPage raw30 = store.selectRawPageAsync(
                    query(now, 30).withMaxRowId(overview30.maxRowId()), null, 36).join();
            assertEquals(36, raw30.rows().size());
            assertTrue(raw30.hasMore());
            assertTrue(logs.stream().anyMatch(s -> s.contains("EXPLAIN page-first")
                    && s.contains("idx_fg_spatial_time") && s.contains("chunk_z=?")));

            // Both searches obey the exact block-circle boundary, not a square/box
            // shortcut to obtain an index seek.
            assertTrue(overview30.totalRows() > overview15.totalRows());
            assertTrue(overview15.totalRows() > 0);
        } finally {
            logger.removeHandler(capture);
        }
    }

    private static GuiLookupQuery query(long now, int radius) {
        return new GuiLookupQuery(WORLD_UUID.toString(), "world", 0, 0, radius,
                now - 86_400_000L, now + 1000L, LookupFilters.State.empty());
    }

    private Database openDatabase() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        YamlConfiguration conf = new YamlConfiguration();
        conf.set("database-write-queue-capacity", 1024);
        conf.set("database-write-batch-size", 100);
        conf.set("database-query-timeout-seconds", 15);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getConfig()).thenReturn(conf);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("FGIndexedAreaTest"));
        when(server.getCurrentTick()).thenReturn(100);
        when(server.getWorld("world")).thenReturn(world);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(world.getUID()).thenReturn(WORLD_UUID);
        when(world.getName()).thenReturn("world");
        Database store = new Database(plugin);
        store.init();
        return store;
    }
}
