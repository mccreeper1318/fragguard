package org.pinnaclesmp.fragguard;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.InOrder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuiPlayerWorkflowIntegrationTest {
    private static final UUID OPERATOR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID BUILDER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID WORLD = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    @TempDir Path directory;

    @Test
    void areaAndPlayerScopesToggleWithoutRemovingNearbyPlayerFilters() throws Exception {
        Fixture f = fixture();
        put(f.session, "screen", screen("SETUP"));
        put(f.session, "filters", new LookupFilters.State("actor-key", null, null));
        Inventory next = mock(Inventory.class);
        try (MockedStatic<FragGuardGuiRenderer> renderer = mockStatic(FragGuardGuiRenderer.class)) {
            renderer.when(() -> FragGuardGuiRenderer.lookupSetup(anyInt(), anyString(),
                    anyBoolean(), anyString(), nullable(String.class))).thenReturn(next);
            f.gui.onClick(click(f.player, f.inventory, 4));
            assertEquals("PLAYER", get(f.session, "lookupScope").toString());
            assertSame(next, get(f.session, "inventory"));
            assertEquals("actor-key", ((LookupFilters.State) get(f.session, "filters")).actorIdentity());

            f.gui.onClick(click(f.player, next, 4));
            assertEquals("AREA", get(f.session, "lookupScope").toString());
            assertEquals("actor-key", ((LookupFilters.State) get(f.session, "filters")).actorIdentity());
        }
    }

    @Test
    void selectorPagesHistoryIdentitiesAndStoresUuidNotUsername() throws Exception {
        Fixture f = fixture();
        setScope(f.session, "lookupScope", "PLAYER");
        put(f.session, "screen", screen("SETUP"));
        var store = mock(GuiPlayerHistoryStore.class);
        put(f.gui, "guiPlayerHistoryStore", store);
        when(f.database.awaitWritesAsync()).thenReturn(CompletableFuture.completedFuture(null));
        List<GuiPlayerHistoryStore.PlayerIdentity> first = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            first.add(new GuiPlayerHistoryStore.PlayerIdentity(
                    UUID.nameUUIDFromBytes(("p" + i).getBytes()), "Player" + i));
        }
        var chosen = new GuiPlayerHistoryStore.PlayerIdentity(BUILDER, "LatestBuilder");
        when(store.identitiesAsync(eq(0), anyInt())).thenReturn(CompletableFuture.completedFuture(
                new GuiPlayerHistoryStore.IdentityPage(first, 0, true)));
        when(store.identitiesAsync(eq(1), anyInt())).thenReturn(CompletableFuture.completedFuture(
                new GuiPlayerHistoryStore.IdentityPage(List.of(chosen), 1, false)));
        Inventory pageOne = mock(Inventory.class);
        Inventory pageTwo = mock(Inventory.class);
        Inventory setup = mock(Inventory.class);
        try (MockedStatic<Bukkit> bukkit = scheduler(f);
             MockedStatic<FragGuardGuiRenderer> renderer = mockStatic(FragGuardGuiRenderer.class)) {
            renderer.when(() -> FragGuardGuiRenderer.playerPicker(any(), any(), anyBoolean()))
                    .thenAnswer(inv -> ((GuiPlayerHistoryStore.IdentityPage) inv.getArgument(0)).page() == 0
                            ? pageOne : pageTwo);
            renderer.when(() -> FragGuardGuiRenderer.lookupSetup(anyInt(), anyString(),
                    anyBoolean(), anyString(), nullable(String.class))).thenReturn(setup);

            f.gui.onClick(click(f.player, f.inventory, 10));
            assertEquals("PLAYER_SELECT", get(f.session, "screen").toString());
            assertSame(pageOne, get(f.session, "inventory"));
            f.gui.onClick(click(f.player, pageOne, 50));
            assertEquals(1, (int) get(f.session, "playerSelectPage"));
            assertSame(pageTwo, get(f.session, "inventory"));
            f.gui.onClick(click(f.player, pageTwo, 0));
            assertSame(setup, get(f.session, "inventory"));
            assertEquals(chosen, get(f.session, "lookupPlayer"));
            assertEquals(BUILDER, ((GuiPlayerHistoryStore.PlayerIdentity)
                    get(f.session, "lookupPlayer")).uuid());
        }
    }

    @Test
    void stalePlayerHistoryCannotOverwriteChangedScopeDuringPendingLookup() throws Exception {
        Fixture f = fixture();
        setScope(f.session, "lookupScope", "PLAYER");
        put(f.session, "screen", screen("SETUP"));
        put(f.session, "lookupPlayer", new GuiPlayerHistoryStore.PlayerIdentity(BUILDER, "Builder"));
        var store = mock(GuiPlayerHistoryStore.class);
        put(f.gui, "guiPlayerHistoryStore", store);
        when(f.database.awaitWritesAsync()).thenReturn(CompletableFuture.completedFuture(null));
        CompletableFuture<GuiPlayerHistoryStore.HistoryPage> pending = new CompletableFuture<>();
        when(store.historyAsync(any(), eq(1))).thenReturn(pending);
        Inventory newerSetup = mock(Inventory.class);
        try (MockedStatic<Bukkit> bukkit = scheduler(f);
             MockedStatic<FragGuardGuiRenderer> renderer = mockStatic(FragGuardGuiRenderer.class)) {
            renderer.when(() -> FragGuardGuiRenderer.lookupSetup(anyInt(), anyString(),
                    anyBoolean(), anyString(), nullable(String.class))).thenReturn(newerSetup);
            f.gui.onClick(click(f.player, f.inventory, 16));
            assertTrue(((GuiPendingTransition) get(f.session, "pendingTransition")).isPending());
            f.gui.onClick(click(f.player, f.inventory, 4));
            assertEquals("AREA", get(f.session, "lookupScope").toString());
            assertSame(newerSetup, get(f.session, "inventory"));
            pending.complete(new GuiPlayerHistoryStore.HistoryPage(List.of(), 1, 0));
            assertEquals("SETUP", get(f.session, "screen").toString());
            assertNull(get(f.session, "playerHistoryQuery"));
            assertFalse(((GuiPendingTransition) get(f.session, "pendingTransition")).isPending());
            renderer.verify(() -> FragGuardGuiRenderer.playerHistoryResults(any(), any()), never());
        }
    }

    @Test
    void playerRollbackPreviewUsesSharedTokenAndGuiConfirmOrCancel() throws Exception {
        Fixture f = fixture();
        setScope(f.session, "rollbackScope", "PLAYER");
        put(f.session, "screen", screen("ROLLBACK_SETUP"));
        put(f.session, "rollbackPlayer", new GuiPlayerHistoryStore.PlayerIdentity(BUILDER, "Builder"));
        long now = System.currentTimeMillis();
        var plan = new PlayerRollbackPlan(
                new HistoricalPlayerResolver.HistoricalPlayerIdentity(BUILDER, "Builder"),
                now - 900000, now,
                List.of(new RollbackTarget("world", 1, 64, 2,
                        "minecraft:air", "minecraft:stone",
                        null, null, WORLD.toString(),
                        "tnt", "Explosion: TNT", BUILDER.toString(), "chain", "TNT_CHAIN")),
                1, 2, 0, 0);
        when(f.database.planPlayerRollbackAsync(eq(BUILDER.toString()), isNull(), anyLong(),
                anyLong(), anyInt(), anyLong(), anyInt(), eq(false)))
                .thenReturn(CompletableFuture.completedFuture(plan));
        FragGuardCommand command = mock(FragGuardCommand.class);
        when(f.plugin.getFragGuardCommand()).thenReturn(command);
        when(command.registerPlayerGuiPreview(eq(f.player), any(), eq(plan))).thenReturn("abc123");
        PluginCommand pluginCommand = mock(PluginCommand.class);
        when(f.plugin.getCommand("fg")).thenReturn(pluginCommand);
        when(pluginCommand.tabComplete(eq(f.player), eq("fg"), any(String[].class)))
                .thenReturn(List.of("abc123"));
        Inventory preview = mock(Inventory.class);
        try (MockedStatic<Bukkit> bukkit = scheduler(f);
             MockedStatic<FragGuardGuiRenderer> renderer = mockStatic(FragGuardGuiRenderer.class)) {
            renderer.when(() -> FragGuardGuiRenderer.playerRollbackPreview(
                    eq("abc123"), eq(plan), isNull(), eq(false))).thenReturn(preview);
            f.gui.onClick(click(f.player, f.inventory, 16));
            assertSame(preview, get(f.session, "inventory"));
            assertEquals("PLAYER_ROLLBACK_PREVIEW", get(f.session, "screen").toString());
            assertEquals("abc123", get(f.session, "playerPreviewToken"));
            verify(command).registerPlayerGuiPreview(eq(f.player), any(), eq(plan));
            f.gui.onClick(click(f.player, preview, 16));
            verify(f.player).performCommand("fg rollback confirm abc123");
            assertNull(get(f.session, "playerPreviewToken"));

            // A second preview can be cancelled via the same command lifecycle.
            put(f.session, "inventory", preview);
            put(f.session, "screen", screen("PLAYER_ROLLBACK_PREVIEW"));
            put(f.session, "playerPreviewToken", "abc123");
            f.gui.onClick(click(f.player, preview, 22));
            verify(f.player).performCommand("fg rollback cancel");
            assertNull(get(f.session, "playerPreviewToken"));
        }
    }

    @Test
    void cancelButtonInvalidatesInFlightPlayerPreviewBeforeItCanIssueAToken() throws Exception {
        Fixture f = fixture();
        put(f.session, "screen", screen("ROLLBACK_SETUP"));
        setScope(f.session, "rollbackScope", "PLAYER");
        GuiPendingTransition pending = (GuiPendingTransition) get(f.session, "pendingTransition");
        LookupRequestGeneration requests = (LookupRequestGeneration) get(f.session, "previewRequests");
        long generation = requests.begin();
        assertTrue(pending.begin());

        f.gui.onClick(click(f.player, f.inventory, 22));
        assertFalse(pending.isPending());
        assertFalse(requests.isCurrent(generation),
                "a cancelled async plan cannot register a later confirmation token");
        verify(f.player).closeInventory();
        verify(f.player).performCommand("fg rollback cancel");
    }

    @Test
    void returningMainMenuCancelsGuiOwnedPlayerTokenBeforeReset() throws Exception {
        Fixture f = fixture();
        put(f.session, "screen", screen("ROLLBACK_SETUP"));
        put(f.session, "playerPreviewToken", "abc123");
        PluginCommand pluginCommand = mock(PluginCommand.class);
        when(f.plugin.getCommand("fg")).thenReturn(pluginCommand);
        when(pluginCommand.tabComplete(eq(f.player), eq("fg"), any(String[].class)))
                .thenReturn(List.of("abc123"));
        Inventory mainMenu = mock(Inventory.class);

        try (MockedStatic<FragGuardGuiRenderer> renderer = mockStatic(FragGuardGuiRenderer.class)) {
            renderer.when(FragGuardGuiRenderer::mainMenu).thenReturn(mainMenu);
            var method = FragGuardGui.class.getDeclaredMethod("openMain", Player.class);
            method.setAccessible(true);
            method.invoke(f.gui, f.player);
        }
        verify(f.player).performCommand("fg rollback cancel");
        assertNull(get(f.session, "playerPreviewToken"));
        assertSame(mainMenu, get(f.session, "inventory"));
    }

    @Test
    void replacementAreaSearchCancelsOnlyOldGuiPlayerTokenBeforeStartingNewQuery()
            throws Exception {
        Fixture f = fixture();
        put(f.session, "screen", screen("ROLLBACK_SETUP"));
        setScope(f.session, "rollbackScope", "AREA");
        put(f.session, "playerPreviewToken", "old-player-token");
        PluginCommand command = mock(PluginCommand.class);
        when(f.plugin.getCommand("fg")).thenReturn(command);
        when(command.tabComplete(eq(f.player), eq("fg"), any(String[].class)))
                .thenReturn(List.of("old-player-token"));

        f.gui.onClick(click(f.player, f.inventory, 16));

        InOrder chronological = inOrder(f.player);
        chronological.verify(f.player).performCommand("fg rollback cancel");
        chronological.verify(f.player).closeInventory();
        chronological.verify(f.player).performCommand(startsWith("fg rollback r:"));
        assertNull(get(f.session, "playerPreviewToken"));
        assertNull(get(f.session, "playerRollbackPlan"));

        // InventoryCloseEvent is delivered after the replacement command
        // starts. Its deferred cleanup must not cancel that new radius query.
        InventoryCloseEvent close = mock(InventoryCloseEvent.class);
        when(close.getPlayer()).thenReturn(f.player);
        when(close.getInventory()).thenReturn(f.inventory);
        when(f.player.getOpenInventory()).thenReturn(null);
        f.gui.onClose(close);
        verify(f.player, times(1)).performCommand("fg rollback cancel");
        verify(f.player, times(1)).performCommand(startsWith("fg rollback r:"));
    }

    @Test
    void replacementAreaSearchNeverCancelsAnUnownedCommandPreview() throws Exception {
        Fixture f = fixture();
        put(f.session, "screen", screen("ROLLBACK_SETUP"));
        setScope(f.session, "rollbackScope", "AREA");
        put(f.session, "playerPreviewToken", "old-player-token");
        PluginCommand command = mock(PluginCommand.class);
        when(f.plugin.getCommand("fg")).thenReturn(command);
        when(command.tabComplete(eq(f.player), eq("fg"), any(String[].class)))
                .thenReturn(List.of("different-command-token"));

        f.gui.onClick(click(f.player, f.inventory, 16));

        verify(f.player, never()).performCommand("fg rollback cancel");
        verify(f.player).performCommand(startsWith("fg rollback r:"));
        assertNull(get(f.session, "playerPreviewToken"));
    }

    private Fixture fixture() throws Exception {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        Database database = mock(Database.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        YamlConfiguration config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getRetentionDays()).thenReturn(30);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("GuiPlayerWorkflowIntegrationTest"));
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return mock(BukkitTask.class);
        });
        FragGuardGui gui = new FragGuardGui(plugin, database);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(OPERATOR);
        when(player.isOnline()).thenReturn(true);
        when(player.isOp()).thenReturn(true);
        when(player.hasPermission("fragguard.admin")).thenReturn(true);
        Object session = session();
        Inventory old = mock(Inventory.class);
        put(session, "inventory", old);
        put(session, "screen", screen("SETUP"));
        InventoryView open = mock(InventoryView.class);
        when(player.getOpenInventory()).thenReturn(open);
        when(open.getTopInventory()).thenAnswer(i -> (Inventory) get(session, "inventory"));
        @SuppressWarnings("unchecked")
        Map<UUID, Object> sessions = (Map<UUID, Object>) get(gui, "sessions");
        sessions.put(OPERATOR, session);
        return new Fixture(gui, plugin, database, server, scheduler, player, old, session);
    }

    private MockedStatic<Bukkit> scheduler(Fixture f) {
        MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(f.scheduler);
        return bukkit;
    }

    private static InventoryClickEvent click(Player player, Inventory inventory, int slot) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView view = mock(InventoryView.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getView()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(inventory);
        when(inventory.getSize()).thenReturn(54);
        when(event.getRawSlot()).thenReturn(slot);
        return event;
    }

    private static Object session() throws Exception {
        Class<?> type = Class.forName("org.pinnaclesmp.fragguard.FragGuardGui$Session");
        Constructor<?> ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object screen(String name) throws Exception {
        Class<? extends Enum> type = (Class<? extends Enum>)
                Class.forName("org.pinnaclesmp.fragguard.FragGuardGui$Screen");
        return Enum.valueOf(type, name);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setScope(Object session, String field, String name) throws Exception {
        Class<? extends Enum> type = (Class<? extends Enum>)
                Class.forName("org.pinnaclesmp.fragguard.FragGuardGui$Scope");
        put(session, field, Enum.valueOf(type, name));
    }

    private static Object get(Object holder, String name) {
        try {
            return field(holder.getClass(), name).get(holder);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void put(Object holder, String name, Object value) {
        try {
            field(holder.getClass(), name).set(holder, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private record Fixture(FragGuardGui gui, FragGuardPlugin plugin, Database database,
                           Server server, BukkitScheduler scheduler, Player player,
                           Inventory inventory, Object session) {
    }
}
