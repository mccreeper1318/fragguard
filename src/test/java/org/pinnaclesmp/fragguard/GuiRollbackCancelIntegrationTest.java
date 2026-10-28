package org.pinnaclesmp.fragguard;

import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuiRollbackCancelIntegrationTest {
    @TempDir
    Path directory;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void cancelButtonRunsTheSameCancellationCommandAsChat() throws Exception {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        PluginCommand command = mock(PluginCommand.class);
        when(plugin.getCommand("fg")).thenReturn(command);
        when(command.tabComplete(any(Player.class), eq("fg"), any(String[].class)))
                .thenReturn(List.of("token"));

        FragGuardGui gui = new FragGuardGui(plugin, mock(Database.class));
        Player player = mock(Player.class);
        UUID actor = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(actor);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSize()).thenReturn(27);
        InventoryView inventoryView = mock(InventoryView.class);
        when(inventoryView.getTopInventory()).thenReturn(inventory);
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getWhoClicked()).thenReturn(player);
        when(click.getView()).thenReturn(inventoryView);
        when(click.getRawSlot()).thenReturn(22);

        Class<?> sessionType = Class.forName("org.pinnaclesmp.fragguard.FragGuardGui$Session");
        Constructor<?> ctor = sessionType.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object session = ctor.newInstance();
        Field screen = sessionType.getDeclaredField("screen");
        screen.setAccessible(true);
        screen.set(session, Enum.valueOf((Class<? extends Enum>) Class.forName(
                "org.pinnaclesmp.fragguard.FragGuardGui$Screen"), "ROLLBACK_SETUP"));
        Field activeInventory = sessionType.getDeclaredField("inventory");
        activeInventory.setAccessible(true);
        activeInventory.set(session, inventory);

        Field sessions = FragGuardGui.class.getDeclaredField("sessions");
        sessions.setAccessible(true);
        ((Map<UUID, Object>) sessions.get(gui)).put(actor, session);

        gui.onClick(click);

        verify(click).setCancelled(true);
        verify(player).closeInventory();
        verify(player).performCommand("fg rollback cancel");
    }
}
