package org.pinnaclesmp.fragguard;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies the actual GUI click handler refuses stale inventory clicks during async navigation. */
class GuiPendingClickIntegrationTest {
    @TempDir
    Path directory;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void repeatedViewToggleCannotMutateGroupedModeDuringPendingLookup() throws Exception {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        FragGuardGui gui = new FragGuardGui(plugin, mock(Database.class));

        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);

        Inventory inventory = mock(Inventory.class);
        when(inventory.getSize()).thenReturn(54);
        InventoryView inventoryView = mock(InventoryView.class);
        when(inventoryView.getTopInventory()).thenReturn(inventory);
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getWhoClicked()).thenReturn(player);
        when(click.getView()).thenReturn(inventoryView);
        when(click.getRawSlot()).thenReturn(4); // Toggle between grouped and exact raw.

        Class<?> sessionClass = Class.forName("org.pinnaclesmp.fragguard.FragGuardGui$Session");
        Constructor<?> ctor = sessionClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object session = ctor.newInstance();

        Class<? extends Enum> screenClass = (Class<? extends Enum>)
                Class.forName("org.pinnaclesmp.fragguard.FragGuardGui$Screen");
        field(sessionClass, "screen").set(session, Enum.valueOf(screenClass, "RESULTS"));
        field(sessionClass, "inventory").set(session, inventory);
        field(sessionClass, "grouped").setBoolean(session, true);

        GuiPendingTransition pending = (GuiPendingTransition)
                field(sessionClass, "pendingTransition").get(session);
        assertTrue(pending.begin()); // An asynchronous toggle is already waiting for SQLite.

        Field sessionsField = field(FragGuardGui.class, "sessions");
        ((Map<UUID, Object>) sessionsField.get(gui)).put(playerId, session);

        // Two additional clicks arrive on the still-visible results inventory.
        gui.onClick(click);
        gui.onClick(click);
        verify(click, org.mockito.Mockito.times(2)).setCancelled(true);
        assertTrue(field(sessionClass, "grouped").getBoolean(session),
                "Pending clicks must not toggle the stored grouping mode");
        assertEquals(0, field(sessionClass, "page").getInt(session),
                "Pending clicks must not advance the page");
        assertTrue(pending.isPending(), "The original request still owns the transition guard");
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field value = owner.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }
}
