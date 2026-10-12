package org.pinnaclesmp.fragguard;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class GuiLookupStartupTest {
    private static final UUID WORLD_UUID = UUID.fromString("563fce36-6445-43e9-9e79-3bb6d0780b13");
    private static final UUID ACTOR_A = UUID.fromString("714ea63f-075e-4694-b2c4-ae06a79748aa");
    private static final UUID ACTOR_B = UUID.fromString("814ea63f-075e-4694-b2c4-ae06a79748ab");

    @TempDir
    Path temporaryDirectory;

    private Database database;

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.shutdown();
        }
    }

    @Test
    void guiOverviewStartsOnlyAfterWriteBarrierAndNoLegacyLookupRuns() {
        Database worker = mock(Database.class);
        GuiLookupStore store = mock(GuiLookupStore.class);
        GuiLookupQuery query = query(System.currentTimeMillis(), LookupFilters.State.empty());
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        GuiLookupOverview expected = new GuiLookupOverview(
                12L, LookupFilters.Catalog.empty(), 77L);

        when(worker.awaitWritesAsync()).thenReturn(barrier);
        when(store.prepareLookupAsync(query)).thenReturn(CompletableFuture.completedFuture(expected));

        CompletableFuture<GuiLookupOverview> result = GuiLookupStartup.prepare(worker, store, query);
        assertFalse(result.isDone(), "the GUI must not take its snapshot ahead of accepted writes");
        verify(store, never()).prepareLookupAsync(query);

        barrier.complete(null);
        assertEquals(expected, result.join());
        verify(worker).awaitWritesAsync();
        verifyNoMoreInteractions(worker); // In particular: no lookupSinceAsync/legacy COUNT.
        verify(store).prepareLookupAsync(query);
        verifyNoMoreInteractions(store); // Exactly one GUI overview/count request.
    }

    @Test
    void failedWriteBarrierDoesNotBeginAnInconsistentGuiSnapshot() {
        Database worker = mock(Database.class);
        GuiLookupStore store = mock(GuiLookupStore.class);
        GuiLookupQuery query = query(System.currentTimeMillis(), LookupFilters.State.empty());
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        when(worker.awaitWritesAsync()).thenReturn(barrier);

        CompletableFuture<GuiLookupOverview> result = GuiLookupStartup.prepare(worker, store, query);
        barrier.completeExceptionally(new IllegalStateException("Database operation queue is full"));

        assertThrows(CompletionException.class, result::join);
        verify(store, never()).prepareLookupAsync(query);
        verify(worker).awaitWritesAsync();
        verifyNoMoreInteractions(worker);
    }

    @Test
    void hundredsOfAcceptedGameplayWritesAreVisibleAndSnapshotPagingRemainsCorrect() throws Exception {
        database = startDatabase();
        long now = System.currentTimeMillis();
        final int total = 1_024;

        // Use the normal asynchronous block logger, not required inserts. None are
        // explicitly flushed before the GUI startup; its write barrier must do that.
        for (int i = 0; i < total; i++) {
            int x = i % 32;
            int z = i / 32;
            UUID actor = (i & 1) == 0 ? ACTOR_A : ACTOR_B;
            database.insertAsync(new BlockChange(
                    now - i, 1_000L, actor.toString(), (i & 1) == 0 ? "BuilderA" : "BuilderB",
                    "world", x, 64, z, ChangeAction.BREAK,
                    "minecraft:stone", "minecraft:air"));
        }

        GuiLookupStore store = new GuiLookupStore(temporaryDirectory.toFile(), 20);
        GuiLookupQuery query = query(now, LookupFilters.State.empty());
        GuiLookupOverview overview = GuiLookupStartup.prepare(database, store, query).join();

        assertEquals(total, overview.totalRows(),
                "all gameplay writes accepted before the GUI read barrier must appear in the overview");
        assertEquals(2, overview.catalog().players().size());
        assertEquals(total, overview.catalog().actions().stream().mapToLong(LookupFilters.Option::count).sum());
        assertEquals(total, overview.catalog().materials().stream().mapToLong(LookupFilters.Option::count).sum());

        GuiLookupQuery bounded = query.withMaxRowId(overview.maxRowId());
        GuiRawPage first = store.selectRawPageAsync(bounded, null, 36).join();
        GuiRawPage second = store.selectRawPageAsync(bounded, first.nextCursor(), 36).join();
        assertEquals(36, first.rows().size());
        assertEquals(36, second.rows().size());
        assertNotEquals(first.rows().getLast().id(), second.rows().getFirst().id());

        GuiLookupQuery filtered = bounded.withFilters(
                new LookupFilters.State(ACTOR_A.toString(), null, null));
        assertEquals(total / 2, store.countRowsAsync(filtered).join());

        // A change arriving after the snapshot must not alter its count or cursor boundary.
        database.insertRequiredAsync(List.of(new BlockChange(
                now + 1, 2_000L, ACTOR_A.toString(), "BuilderA",
                "world", 14, 64, 14, ChangeAction.PLACE,
                "minecraft:air", "minecraft:diamond_block"))).join();
        assertEquals(total, store.countRowsAsync(bounded).join());
        assertEquals(total + 1L, GuiLookupStartup.prepare(database, store, query).join().totalRows(),
                "a fresh lookup should see the newer accepted change");
    }

    private GuiLookupQuery query(long now, LookupFilters.State filters) {
        return new GuiLookupQuery(WORLD_UUID.toString(), "world", 15, 15, 30,
                now - 100_000L, now + 100_000L, filters);
    }

    private Database startDatabase() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("database-write-queue-capacity", 4096);
        configuration.set("database-write-batch-size", 64);
        configuration.set("database-query-timeout-seconds", 20);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getConfig()).thenReturn(configuration);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("GuiLookupStartupTest"));
        when(server.getCurrentTick()).thenReturn(100);
        when(server.getWorld("world")).thenReturn(world);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(world.getUID()).thenReturn(WORLD_UUID);
        when(world.getName()).thenReturn("world");

        Database started = new Database(plugin);
        started.init();
        return started;
    }
}
