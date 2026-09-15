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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuiLookupPaginationTest {
    private static final UUID WORLD_UUID = UUID.fromString("563fce36-6445-43e9-9e79-3bb6d0780b13");
    private static final UUID ACTOR_A = UUID.fromString("714ea63f-075e-4694-b2c4-ae06a79748aa");
    private static final UUID ACTOR_B = UUID.fromString("814ea63f-075e-4694-b2c4-ae06a79748ab");
    private static final UUID ACTOR_C = UUID.fromString("914ea63f-075e-4694-b2c4-ae06a79748ac");

    @TempDir
    Path temporaryDirectory;

    private final AtomicInteger currentTick = new AtomicInteger(100);
    private Database database;

    @AfterEach
    void shutDownDatabase() {
        if (database != null) {
            database.shutdown();
        }
    }

    @Test
    void pagesAndGroupsWellBeyondFiveThousandRowsWithoutMaterializingTheWholeLookup() throws Exception {
        database = startDatabase();
        long now = System.currentTimeMillis();
        int total = 5_260;

        List<BlockChange> pending = new ArrayList<>(500);
        for (int index = 0; index < total; index++) {
            UUID actor;
            String actorName;
            if (index < 60) {
                actor = ACTOR_A;
                actorName = "BuilderA";
            } else if ((index & 1) == 0) {
                actor = ACTOR_B;
                actorName = "BuilderB";
            } else {
                actor = ACTOR_C;
                actorName = "BuilderC";
            }
            pending.add(new BlockChange(
                    now - index,
                    actor.toString(),
                    actorName,
                    "world",
                    0,
                    64,
                    0,
                    ChangeAction.BREAK,
                    "minecraft:chest",
                    "minecraft:air"));
            if (pending.size() == 500) {
                database.insertRequiredAsync(List.copyOf(pending)).join();
                pending.clear();
            }
        }
        if (!pending.isEmpty()) {
            database.insertRequiredAsync(List.copyOf(pending)).join();
        }

        GuiLookupStore store = new GuiLookupStore(temporaryDirectory.toFile(), 10);
        GuiLookupQuery query = new GuiLookupQuery(
                WORLD_UUID.toString(), "world", 0, 0, 15,
                now - 10_000L, now + 1_000L, LookupFilters.State.empty());

        GuiLookupOverview overview = store.prepareLookupAsync(query).join();
        assertEquals(total, overview.totalRows());
        assertTrue(overview.totalRows() > 5_000L);
        assertEquals(3, overview.catalog().players().size());
        query = query.withMaxRowId(overview.maxRowId());

        GuiRawPage firstRaw = store.selectRawPageAsync(query, null, 36).join();
        assertEquals(36, firstRaw.rows().size());
        assertTrue(firstRaw.hasMore());
        assertNotNull(firstRaw.nextCursor());
        assertTrue(firstRaw.rows().stream().noneMatch(LookupRow::blockEntityPayloadLoaded));

        GuiRawPage secondRaw = store.selectRawPageAsync(query, firstRaw.nextCursor(), 36).join();
        assertEquals(36, secondRaw.rows().size());
        assertNotEquals(firstRaw.rows().getLast().id(), secondRaw.rows().getFirst().id());

        GuiLookupQuery builderB = query.withFilters(
                new LookupFilters.State(ACTOR_B.toString(), null, null));
        long builderBCount = store.countRowsAsync(builderB).join();
        assertTrue(builderBCount > 2_000L);
        assertTrue(builderBCount < total);
        GuiRawPage filteredPage = store.selectRawPageAsync(builderB, null, 36).join();
        assertEquals(36, filteredPage.rows().size());
        assertTrue(filteredPage.rows().stream()
                .allMatch(row -> row.actorIdentity().equals(ACTOR_B.toString())));

        GuiActivityPage activities = store.selectActivityPageAsync(
                query, null, 36, 127,
                2_500L, 6, 15_000L, 24).join();
        assertEquals(36, activities.activities().size());
        assertTrue(activities.hasMore());

        GuiActivitySummary firstActivity = activities.activities().getFirst();
        assertEquals(ACTOR_A.toString(), firstActivity.actorIdentity());
        assertEquals(60, firstActivity.eventCount());

        GuiActivityRawPage firstActivityPage = store.selectActivityRowsPageAsync(
                query, firstActivity, null, 45).join();
        assertEquals(45, firstActivityPage.rows().size());
        assertTrue(firstActivityPage.hasMore());

        GuiActivityRawPage secondActivityPage = store.selectActivityRowsPageAsync(
                query, firstActivity, firstActivityPage.nextCursor(), 45).join();
        assertEquals(15, secondActivityPage.rows().size());
        assertFalse(secondActivityPage.hasMore());

        Set<Long> exactIds = new HashSet<>();
        firstActivityPage.rows().forEach(row -> exactIds.add(row.id()));
        secondActivityPage.rows().forEach(row -> exactIds.add(row.id()));
        assertEquals(firstActivity.eventCount(), exactIds.size(),
                "every exact row represented by the streamed activity must remain reachable");
    }

    private Database startDatabase() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("database-write-queue-capacity", 64);
        configuration.set("database-write-batch-size", 16);
        configuration.set("database-query-timeout-seconds", 10);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getConfig()).thenReturn(configuration);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("FragGuardGuiLookupPaginationTest"));
        when(server.getCurrentTick()).thenAnswer(invocation -> currentTick.get());
        when(server.getWorld("world")).thenReturn(world);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(world.getUID()).thenReturn(WORLD_UUID);
        when(world.getName()).thenReturn("world");

        Database instance = new Database(plugin);
        instance.init();
        return instance;
    }
}
