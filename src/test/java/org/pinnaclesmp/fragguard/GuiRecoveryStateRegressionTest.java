package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual FragGuardGui session/click handling, rather than only testing its transition helper. */
class GuiRecoveryStateRegressionTest {
    @TempDir Path directory;

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void failedInventoryRenderKeepsPageThreeAndAllowsSuccessfulRetry() throws Exception {
        Logger logger = Logger.getLogger("GuiRecoveryRender-" + UUID.randomUUID());
        List<String> messages = new ArrayList<>();
        Handler handler = recordingHandler(messages);
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        try {
            FragGuardGui gui = gui(logger);
            Object session = session();
            Class<?> sessionType = session.getClass();
            Inventory previous = mock(Inventory.class);
            Inventory replacement = mock(Inventory.class);
            Player player = mock(Player.class);
            InventoryView open = mock(InventoryView.class);
            when(player.getOpenInventory()).thenReturn(open);
            when(open.getTopInventory()).thenReturn(previous);
            field(sessionType, "inventory").set(session, previous);
            field(sessionType, "screen").set(session, screen("RESULTS"));
            field(sessionType, "page").setInt(session, 2);
            field(sessionType, "resultHasNext").setBoolean(session, true);
            List<GuiLookupCursor> cursors = (List<GuiLookupCursor>)
                    field(sessionType, "resultPageStarts").get(session);
            cursors.add(null);
            cursors.add(new GuiLookupCursor(100, 10));
            cursors.add(new GuiLookupCursor(90, 20));
            cursors.add(new GuiLookupCursor(80, 30));
            List<GuiLookupCursor> saved = new ArrayList<>(cursors);

            Method show = FragGuardGui.class.getDeclaredMethod("show",
                    Player.class, sessionType, screen("RESULTS").getClass(), Supplier.class, String.class);
            show.setAccessible(true);

            boolean result = (boolean) show.invoke(gui, player, session, screen("RESULTS"),
                    (Supplier<Inventory>) () -> { throw new IllegalArgumentException(
                            "WATER_CAULDRON isn't an item"); }, "results page 4");
            assertFalse(result);
            assertSame(previous, field(sessionType, "inventory").get(session));
            assertEquals(screen("RESULTS"), field(sessionType, "screen").get(session));
            assertEquals(2, field(sessionType, "page").getInt(session));
            assertEquals(saved, cursors);
            assertEquals(1, messages.stream()
                    .filter(s -> s.contains("GUI rendering failed")).count());
            assertTrue(messages.stream().anyMatch(s -> s.contains("currentPage=3")));
            verify(player, never()).closeInventory();

            assertTrue((boolean) show.invoke(gui, player, session, screen("RESULTS"),
                    (Supplier<Inventory>) () -> replacement, "results page 4 retry"));
            assertSame(replacement, field(sessionType, "inventory").get(session));
            assertEquals(2, field(sessionType, "page").getInt(session),
                    "the renderer must never silently advance pagination");
            verify(player).openInventory(replacement);
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void timedOutPageFourPreservesVisiblePageThreeAndAllowsRetry() throws Exception {
        Logger logger = Logger.getLogger("GuiRecoverySql-" + UUID.randomUUID());
        List<String> messages = new ArrayList<>();
        Handler handler = recordingHandler(messages);
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        try {
            FragGuardGui gui = gui(logger);
            GuiLookupStore store = mock(GuiLookupStore.class);
            field(FragGuardGui.class, "guiLookupStore").set(gui, store);
            Object session = session();
            Class<?> type = session.getClass();
            UUID id = UUID.randomUUID();
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(id);
            when(player.isOnline()).thenReturn(true);
            Inventory oldInventory = mock(Inventory.class);
            when(oldInventory.getSize()).thenReturn(54);
            InventoryView open = mock(InventoryView.class);
            when(open.getTopInventory()).thenReturn(oldInventory);
            when(player.getOpenInventory()).thenReturn(open);
            field(type, "inventory").set(session, oldInventory);
            field(type, "screen").set(session, screen("RESULTS"));
            field(type, "page").setInt(session, 2);
            field(type, "grouped").setBoolean(session, false);
            field(type, "resultHasNext").setBoolean(session, true);
            field(type, "totalRows").setLong(session, 500);
            field(type, "filteredTotalRows").setLong(session, 500);
            GuiLookupQuery base = new GuiLookupQuery(UUID.randomUUID().toString(),
                    "world", 0, 0, 15, 1L, 10000L, LookupFilters.State.empty())
                    .withMaxRowId(5000);
            field(type, "baseQuery").set(session, base);
            List<GuiLookupCursor> cursors = (List<GuiLookupCursor>)
                    field(type, "resultPageStarts").get(session);
            cursors.add(null);
            cursors.add(new GuiLookupCursor(400, 400));
            cursors.add(new GuiLookupCursor(300, 300));
            cursors.add(new GuiLookupCursor(200, 200));
            List<GuiLookupCursor> original = new ArrayList<>(cursors);
            ((Map<UUID, Object>) field(FragGuardGui.class, "sessions").get(gui)).put(id, session);
            CompletableFuture<GuiRawPage> failed = CompletableFuture.failedFuture(
                    new SQLTimeoutException("injected page-four timeout"));
            when(store.selectRawPageAsync(any(GuiLookupQuery.class),
                    eq(original.get(3)), eq(36))).thenReturn(failed);

            InventoryClickEvent click = click(player, oldInventory, 50);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            when(scheduler.runTask(any(FragGuardPlugin.class), any(Runnable.class))).thenAnswer(call -> {
                call.getArgument(1, Runnable.class).run();
                return mock(BukkitTask.class);
            });

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                gui.onClick(click);
            }
            verify(click).setCancelled(true);
            assertSame(oldInventory, field(type, "inventory").get(session));
            assertEquals(2, field(type, "page").getInt(session));
            assertEquals(original, cursors);
            assertTrue(messages.stream().anyMatch(s -> s.contains("GUI database lookup timed out")));
            GuiPendingTransition pending = (GuiPendingTransition)
                    field(type, "pendingTransition").get(session);
            assertFalse(pending.isPending(), "failed query releases navigation guard for retry");
            verify(player, never()).closeInventory();
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleReadCompletionAfterQuitNeverReopensOrRewritesAClosedSession() throws Exception {
        Logger logger = Logger.getLogger("GuiRecoveryStale-" + UUID.randomUUID());
        FragGuardGui gui = gui(logger);
        GuiLookupStore store = mock(GuiLookupStore.class);
        field(FragGuardGui.class, "guiLookupStore").set(gui, store);
        Object session = session();
        Class<?> type = session.getClass();
        UUID id = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        Inventory existing = mock(Inventory.class);
        when(existing.getSize()).thenReturn(54);
        InventoryView open = mock(InventoryView.class);
        when(open.getTopInventory()).thenReturn(existing);
        when(player.getOpenInventory()).thenReturn(open);
        field(type, "inventory").set(session, existing);
        field(type, "screen").set(session, screen("RESULTS"));
        field(type, "grouped").setBoolean(session, false);
        field(type, "resultHasNext").setBoolean(session, true);
        field(type, "filteredTotalRows").setLong(session, 400);
        field(type, "baseQuery").set(session, new GuiLookupQuery(UUID.randomUUID().toString(),
                "world", 0, 0, 15, 1L, 10000L, LookupFilters.State.empty()));
        List<GuiLookupCursor> starts = (List<GuiLookupCursor>)
                field(type, "resultPageStarts").get(session);
        starts.add(null);
        starts.add(new GuiLookupCursor(200, 200));
        ((Map<UUID, Object>) field(FragGuardGui.class, "sessions").get(gui)).put(id, session);

        CompletableFuture<GuiRawPage> pendingPage = new CompletableFuture<>();
        when(store.selectRawPageAsync(any(), eq(starts.get(1)), eq(36))).thenReturn(pendingPage);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTask(any(FragGuardPlugin.class), any(Runnable.class))).thenAnswer(call -> {
            call.getArgument(1, Runnable.class).run();
            return mock(BukkitTask.class);
        });
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            gui.onClick(click(player, existing, 50));
            GuiPendingTransition navigation = (GuiPendingTransition)
                    field(type, "pendingTransition").get(session);
            assertTrue(navigation.isPending());
            PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
            when(quit.getPlayer()).thenReturn(player);
            gui.onQuit(quit);
            assertFalse(navigation.isPending());
            assertFalse(((Map<?, ?>) field(FragGuardGui.class, "sessions").get(gui)).containsKey(id));
            pendingPage.complete(new GuiRawPage(List.of(), null));
            assertEquals(0, field(type, "page").getInt(session));
            assertSame(existing, field(type, "inventory").get(session));
            verify(player, never()).openInventory(any(Inventory.class));
        }
    }

    private FragGuardGui gui(Logger logger) {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        return new FragGuardGui(plugin, mock(Database.class));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object screen(String name) throws Exception {
        Class<? extends Enum> screenType = (Class<? extends Enum>)
                Class.forName("org.pinnaclesmp.fragguard.FragGuardGui$Screen");
        return Enum.valueOf(screenType, name);
    }

    private static Object session() throws Exception {
        Class<?> type = Class.forName("org.pinnaclesmp.fragguard.FragGuardGui$Session");
        Constructor<?> ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static InventoryClickEvent click(Player player, Inventory inventory, int slot) {
        InventoryView open = mock(InventoryView.class);
        when(open.getTopInventory()).thenReturn(inventory);
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getWhoClicked()).thenReturn(player);
        when(click.getView()).thenReturn(open);
        when(click.getRawSlot()).thenReturn(slot);
        return click;
    }

    private static Handler recordingHandler(List<String> messages) {
        return new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
    }
}
