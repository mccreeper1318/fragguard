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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuiLookupOptimizationTest {
    private static final UUID WORLD = UUID.fromString("563fce36-6445-43e9-9e79-3bb6d0780b13");
    private static final UUID ACTOR = UUID.fromString("714ea63f-075e-4694-b2c4-ae06a79748aa");

    @TempDir
    Path directory;
    private Database database;

    @AfterEach
    void shutdown() {
        if (database != null) {
            database.shutdown();
        }
    }

    @Test
    void overviewAndFirstPageDoNotLoadCatalogUntilFiltersRequested() throws Exception {
        database = openDatabase();
        long now = System.currentTimeMillis();
        List<BlockChange> changes = new ArrayList<>();
        for (int i = 0; i < 3_000; i++) {
            changes.add(new BlockChange(now - i, i + 10L, ACTOR.toString(), "Builder",
                    "world", i % 15, 64, i / 15 % 15,
                    ChangeAction.BREAK, "minecraft:stone", "minecraft:air"));
        }
        for (int start = 0; start < changes.size(); start += 300) {
            database.insertRequiredAsync(changes.subList(start, start + 300)).join();
        }

        Logger logger = Logger.getLogger("GuiLookupOptimizationTest-" + UUID.randomUUID());
        List<String> messages = new ArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(capture);
        logger.setUseParentHandlers(false);
        try {
            GuiLookupStore store = new GuiLookupStore(directory.toFile(), 15, logger, true);
            GuiLookupQuery original = new GuiLookupQuery(WORLD.toString(), "world",
                    7, 7, 15, now - 30_000L, now + 1_000L, LookupFilters.State.empty());
            GuiLookupOverview overview = store.prepareOverviewAsync(original).join();
            assertEquals(changes.size(), overview.totalRows());
            assertTrue(overview.catalog().players().isEmpty(),
                    "Filter catalogs must not delay unfiltered results");
            assertTrue(messages.stream().anyMatch(s -> s.contains("initial-count rows=3000")));
            assertTrue(messages.stream().anyMatch(s -> s.contains("EXPLAIN count")));
            assertTrue(messages.stream().noneMatch(s -> s.contains("catalog-players")));

            GuiLookupQuery bounded = original.withMaxRowId(overview.maxRowId());
            GuiRawPage first = store.selectRawPageAsync(bounded, null, 36).join();
            assertEquals(36, first.rows().size());
            assertTrue(first.hasMore());
            assertTrue(messages.stream().anyMatch(s -> s.contains("EXPLAIN page-first")));

            LookupFilters.Catalog catalog = store.loadCatalogAsync(bounded).join();
            assertEquals(1, catalog.players().size());
            assertEquals(changes.size(), catalog.materials().stream()
                    .mapToLong(LookupFilters.Option::count).sum());
            assertTrue(messages.stream().anyMatch(s -> s.contains("catalog-materials")));
            assertTrue(messages.stream().anyMatch(s -> s.contains("EXPLAIN catalog-material")));
            assertEquals(changes.size(), store.countRowsAsync(bounded).join());
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    void overallDeadlineCompletesExceptionallyAndReportsTheStalledPhase() throws Exception {
        Logger logger = Logger.getLogger("GuiLookupDeadlineTest-" + UUID.randomUUID());
        List<String> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(capture);
        logger.setUseParentHandlers(false);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        GuiLookupStore store = new GuiLookupStore(directory.toFile(), 1, logger);

        try {
            var future = store.boundedQueryAsync("stalled-sql", () -> {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return 123;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            CompletionException failure = assertThrows(CompletionException.class, future::join);
            assertTrue(failure.getCause() instanceof TimeoutException);
            assertTrue(messages.stream().anyMatch(s -> s.contains("stalled-sql exceeded")),
                    "The database phase must be named in the console if its overall read deadline expires");
        } finally {
            release.countDown();
            logger.removeHandler(capture);
        }
    }

    private Database openDatabase() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("database-query-timeout-seconds", 15);
        config.set("database-write-queue-capacity", 64);
        config.set("database-write-batch-size", 16);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("FG-Test-" + UUID.randomUUID()));
        when(server.getCurrentTick()).thenReturn(500);
        when(server.getWorld("world")).thenReturn(world);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(WORLD);
        Database value = new Database(plugin);
        value.init();
        return value;
    }
}
