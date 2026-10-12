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
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Real SQLite regression checks for pages 1,2,3,5,10+ and every cursor boundary,
 * including equal timestamps and world/actor/material filters. No GUI row cap.
 */
class GuiDeepHistoryRegressionTest {
    private static final UUID WORLD = UUID.fromString("563fce36-6445-43e9-9e79-3bb6d0780b13");
    private static final UUID OTHER_WORLD = UUID.fromString("663fce36-6445-43e9-9e79-3bb6d0780b14");
    private static final UUID ACTOR_A = UUID.fromString("714ea63f-075e-4694-b2c4-ae06a79748aa");
    private static final UUID ACTOR_B = UUID.fromString("814ea63f-075e-4694-b2c4-ae06a79748ab");

    @TempDir Path folder;
    private Database database;

    @AfterEach
    void close() {
        if (database != null) database.shutdown();
    }

    @Test
    void groupedAndRawPageThreeAndTenRemainCompleteAcrossEqualTimestampAndMaterialBoundaries() throws Exception {
        database = start();
        long now = System.currentTimeMillis();
        int total = 481;
        List<BlockChange> events = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            // Pairs intentionally share a timestamp. Alternating actors must
            // prevent grouping of adjacent rows, regardless of tie order.
            UUID actor = (i & 1) == 0 ? ACTOR_A : ACTOR_B;
            String before = switch (i) {
                case 0 -> "minecraft:water_cauldron[level=3]";
                case 72 -> "minecraft:moving_piston[facing=north]";
                case 73 -> "minecraft:water[level=0]";
                case 74 -> "minecraft:lava[level=0]";
                case 75 -> "minecraft:redstone_wire[power=4]";
                case 76 -> "minecraft:fire[age=0]";
                case 77 -> "minecraft:not_a_real_legacy_block";
                default -> "minecraft:stone";
            };
            events.add(new BlockChange(now - i / 2, i + 1000L,
                    actor.toString(), (i & 1) == 0 ? "BuilderA" : "BuilderB",
                    "world", 0, 64, 0, ChangeAction.BREAK, before, "minecraft:air"));
        }
        // Contamination from another world and outside the selected time window
        // must not appear in the fixed GUI snapshot.
        for (int i = 0; i < 50; i++) {
            events.add(new BlockChange(now - i, i + 2000L, ACTOR_A.toString(), "BuilderA",
                    "other_world", 0, 64, 0, ChangeAction.BREAK, "minecraft:gold_block", "minecraft:air"));
        }
        events.add(new BlockChange(now - 2_000_000L, 9999L, ACTOR_A.toString(), "BuilderA",
                "world", 0, 64, 0, ChangeAction.BREAK, "minecraft:gold_block", "minecraft:air"));
        database.insertRequiredAsync(events).join();

        GuiLookupStore store = new GuiLookupStore(folder.toFile(), 15);
        GuiLookupQuery query = new GuiLookupQuery(WORLD.toString(), "world", 0, 0, 15,
                now - 100_000L, now + 1000L, LookupFilters.State.empty());
        GuiLookupOverview overview = store.prepareOverviewAsync(query).join();
        assertEquals(total, overview.totalRows());
        query = query.withMaxRowId(overview.maxRowId());

        Set<Long> allRawIds = new HashSet<>();
        Set<Long> allGroupedIds = new HashSet<>();
        GuiLookupCursor rawCursor = null;
        GuiLookupCursor groupedCursor = null;
        List<GuiLookupCursor> rawPageStarts = new ArrayList<>();
        List<GuiLookupCursor> groupedPageStarts = new ArrayList<>();
        List<String> pageThreeMaterials = new ArrayList<>();
        int page = 0;
        while (true) {
            rawPageStarts.add(rawCursor);
            groupedPageStarts.add(groupedCursor);
            GuiRawPage raw = store.selectRawPageAsync(query, rawCursor, 36).join();
            GuiActivityPage grouped = store.selectActivityPageAsync(query, groupedCursor,
                    36, 37, 2_500L, 6, 15_000L, 24).join();

            assertEquals(raw.rows().size(), grouped.activities().size(),
                    "alternating actors prevent activity grouping");
            for (LookupRow row : raw.rows()) {
                assertTrue(allRawIds.add(row.id()), "raw page " + (page + 1) + " duplicated an event");
                if (page == 2) pageThreeMaterials.add(LookupActivityGrouper.materialKey(row));
            }
            // Every actor/material pair is a singleton, so the newest event
            // cursors must be identical between condensed and exact pagination.
            for (GuiActivitySummary activity : grouped.activities()) {
                assertEquals(1, activity.eventCount());
                assertTrue(allGroupedIds.add(activity.newestCursor().id()),
                        "condensed page " + (page + 1) + " duplicated an exact event");
            }
            assertEquals(raw.hasMore(), grouped.hasMore());
            rawCursor = raw.nextCursor();
            groupedCursor = grouped.nextCursor();
            page++;
            assertTrue(page <= 20, "cursor did not advance");
            if (!raw.hasMore()) break;
        }
        assertTrue(page >= 11, "must actually exercise page 10 and beyond");
        assertEquals(total, allRawIds.size(), "no raw event omitted");
        assertEquals(allRawIds, allGroupedIds, "grouped pages represent the same exact event IDs");
        assertTrue(pageThreeMaterials.contains("minecraft:moving_piston")
                || pageThreeMaterials.contains("minecraft:water"),
                "original page-three crash materials must be present");
        assertNotNull(rawPageStarts.get(9));
        assertNotNull(groupedPageStarts.get(9));

        // Reopen previous/page-three/page-ten from remembered cursors, not by
        // replaying from page 1: navigation must return exactly the same events.
        for (int pageIndex : new int[]{0, 1, 2, 4, 9, page - 1}) {
            GuiRawPage reread = store.selectRawPageAsync(query, rawPageStarts.get(pageIndex), 36).join();
            assertFalse(reread.rows().isEmpty());
            assertTrue(allRawIds.containsAll(reread.rows().stream().map(LookupRow::id).toList()));
            GuiActivityPage groupReread = store.selectActivityPageAsync(
                    query, groupedPageStarts.get(pageIndex), 36, 37, 2_500L, 6, 15_000L, 24).join();
            assertEquals(reread.rows().size(), groupReread.activities().size());
        }

        GuiLookupQuery filtered = query.withFilters(new LookupFilters.State(
                ACTOR_B.toString(), ChangeAction.BREAK, "minecraft:stone"));
        long matching = store.countRowsAsync(filtered).join();
        assertTrue(matching > 0 && matching < total / 2);
        assertEquals(total / 2 - 3L, matching,
                "actor B has three deliberately non-stone material changes");
        assertEquals(total / 2, store.loadCatalogAsync(query).join().players().stream()
                .filter(o -> o.key().equals(ACTOR_B.toString()))
                .mapToLong(LookupFilters.Option::count).sum());
        GuiRawPage filteredPage = store.selectRawPageAsync(filtered, null, 36).join();
        assertTrue(filteredPage.rows().stream().allMatch(row ->
                ACTOR_B.toString().equals(row.actorIdentity())
                && "minecraft:stone".equals(LookupActivityGrouper.materialKey(row))));

        // New writes after snapshot must appear only in new searches.
        database.insertRequiredAsync(List.of(new BlockChange(now + 10, 10000L,
                ACTOR_A.toString(), "BuilderA", "world", 0, 64, 0,
                ChangeAction.PLACE, "minecraft:air", "minecraft:diamond_block"))).join();
        assertEquals(total, store.countRowsAsync(query).join());
        assertEquals(total + 1L, store.prepareOverviewAsync(new GuiLookupQuery(WORLD.toString(),
                "world", 0, 0, 15, now - 100_000L, now + 1000L,
                LookupFilters.State.empty())).join().totalRows());
    }

    @Test
    void oneCondensedActivityDrillsIntoThreeExactPagesWithoutDroppingAnything() throws Exception {
        database = start();
        long now = System.currentTimeMillis();
        List<BlockChange> changes = new ArrayList<>();
        for (int i = 0; i < 109; i++) {
            changes.add(new BlockChange(now, 50_000L + i, ACTOR_A.toString(),
                    "BuilderA", "world", 2, 64, 2, ChangeAction.BREAK,
                    "minecraft:chest[facing=north]", "minecraft:air"));
        }
        database.insertRequiredAsync(changes).join();
        GuiLookupStore store = new GuiLookupStore(folder.toFile(), 15);
        GuiLookupQuery base = new GuiLookupQuery(WORLD.toString(), "world", 2, 2, 5,
                now - 10_000L, now + 1000L, LookupFilters.State.empty());
        GuiLookupOverview overview = store.prepareOverviewAsync(base).join();
        assertEquals(109, overview.totalRows());
        GuiLookupQuery bounded = base.withMaxRowId(overview.maxRowId());
        GuiActivityPage grouped = store.selectActivityPageAsync(bounded, null, 36,
                35, 2500L, 6, 15000L, 24).join();
        assertEquals(1, grouped.activities().size());
        GuiActivitySummary activity = grouped.activities().getFirst();
        assertEquals(109, activity.eventCount());

        Set<Long> seen = new HashSet<>();
        GuiLookupCursor cursor = null;
        int index = 0;
        for (int expectedSize : new int[]{45, 45, 19}) {
            GuiActivityRawPage raw = store.selectActivityRowsPageAsync(bounded, activity, cursor, 45).join();
            assertEquals(expectedSize, raw.rows().size());
            raw.rows().forEach(row -> assertTrue(seen.add(row.id())));
            cursor = raw.nextCursor();
            index++;
            assertEquals(index < 3, raw.hasMore());
        }
        assertEquals(109, seen.size());
    }

    private Database start() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        World otherWorld = mock(World.class);
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("database-write-queue-capacity", 4096);
        cfg.set("database-write-batch-size", 100);
        cfg.set("database-query-timeout-seconds", 15);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getConfig()).thenReturn(cfg);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("GuiDeepHistoryRegressionTest"));
        when(server.getCurrentTick()).thenReturn(100);
        when(server.getWorld("world")).thenReturn(world);
        when(server.getWorld("other_world")).thenReturn(otherWorld);
        when(server.getWorlds()).thenReturn(List.of(world, otherWorld));
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(WORLD);
        when(otherWorld.getName()).thenReturn("other_world");
        when(otherWorld.getUID()).thenReturn(OTHER_WORLD);
        Database db = new Database(plugin);
        db.init();
        return db;
    }
}
