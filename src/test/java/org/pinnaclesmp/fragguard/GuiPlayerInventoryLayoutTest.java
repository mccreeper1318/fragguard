package org.pinnaclesmp.fragguard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuiPlayerInventoryLayoutTest {
    private static final UUID BUILDER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID WORLD = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Test
    void pickerUsesAllFortyFiveSlotsAndGlobalPagesKeepWorldAndPhysicalCause() {
        Inventory inventory = mock(Inventory.class);
        Map<Integer, ItemStack> slots = new HashMap<>();
        Map<ItemStack, ItemDetails> details = new IdentityHashMap<>();
        doAnswer(invocation -> {
            slots.put(invocation.getArgument(0, Integer.class),
                    invocation.getArgument(1, ItemStack.class));
            return null;
        }).when(inventory).setItem(anyInt(), any(ItemStack.class));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<GuiMaterialIcon> icons = mockStatic(GuiMaterialIcon.class);
             MockedConstruction<ItemStack> construction = mockConstruction(ItemStack.class,
                     (stack, context) -> {
                         ItemDetails item = new ItemDetails();
                         details.put(stack, item);
                         ItemMeta meta = mock(ItemMeta.class);
                         when(stack.getItemMeta()).thenReturn(meta);
                         doAnswer(invocation -> {
                             item.title = plain(invocation.getArgument(0, Component.class));
                             return null;
                         }).when(meta).displayName(any(Component.class));
                         doAnswer(invocation -> {
                             List<Component> lore = invocation.getArgument(0);
                             item.lore = lore.stream().map(GuiPlayerInventoryLayoutTest::plain).toList();
                             return null;
                         }).when(meta).lore(anyList());
                     })) {
            bukkit.when(() -> Bukkit.createInventory(isNull(), eq(54), any(Component.class)))
                    .thenReturn(inventory);
            bukkit.when(() -> Bukkit.createInventory(isNull(), eq(27), any(Component.class)))
                    .thenReturn(inventory);
            icons.when(() -> GuiMaterialIcon.asItem(any(Material.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            icons.when(() -> GuiMaterialIcon.fromHistory(anyString()))
                    .thenReturn(Material.PAPER);

            List<GuiPlayerHistoryStore.PlayerIdentity> people = new ArrayList<>();
            for (int i = 0; i < 44; i++) {
                people.add(new GuiPlayerHistoryStore.PlayerIdentity(UUID.randomUUID(), "Player" + i));
            }
            people.add(new GuiPlayerHistoryStore.PlayerIdentity(BUILDER, "Builder"));
            FragGuardGuiRenderer.playerPicker(new GuiPlayerHistoryStore.IdentityPage(people, 0, true),
                    BUILDER.toString(), false);
            assertEquals(45, slots.keySet().stream().filter(s -> s < 45).count());
            assertTrue(details.get(slots.get(44)).title.contains("Builder"));
            assertTrue(details.get(slots.get(44)).lore.stream().anyMatch(s -> s.contains("Selected")));
            assertTrue(details.get(slots.get(50)).title.contains("Next Players"));

            slots.clear();
            List<PlayerLookupRow> events = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (int i = 0; i < 36; i++) {
                boolean indirect = i % 2 == 0;
                events.add(new PlayerLookupRow(i + 1L, now - i,
                        indirect ? "tnt-entity" : BUILDER.toString(),
                        indirect ? "Explosion: TNT" : "Builder",
                        indirect ? BUILDER.toString() : null,
                        indirect ? "Builder" : null,
                        indirect ? "TNT_CHAIN" : null,
                        indirect ? "world_nether" : "world", i, 64, -i,
                        indirect ? ChangeAction.EXPLOSION : ChangeAction.BREAK,
                        "minecraft:stone", "minecraft:air", false));
            }
            var scope = new GuiPlayerHistoryStore.HistoryQuery(BUILDER, "Builder",
                    null, now - 10_000, now, null, null);
            FragGuardGuiRenderer.playerHistoryResults(
                    new GuiPlayerHistoryStore.HistoryPage(events, 1, 72), scope);
            assertEquals(36, slots.keySet().stream().filter(s -> s >= 9 && s <= 44).count());
            ItemDetails indirect = details.get(slots.get(9));
            assertTrue(indirect.lore.stream().anyMatch(s -> s.contains("world_nether")));
            assertTrue(indirect.lore.stream().anyMatch(s -> s.contains("Indirectly attributed")));
            assertTrue(indirect.lore.stream().anyMatch(s -> s.contains("Explosion: TNT")));
            assertTrue(indirect.lore.stream().anyMatch(s -> s.contains("TNT_CHAIN")));
            ItemDetails direct = details.get(slots.get(10));
            assertTrue(direct.lore.stream().anyMatch(s -> s.contains("Direct actor")));
            assertTrue(details.get(slots.get(50)).title.contains("Next Events"));

            slots.clear();
            var plan = new PlayerRollbackPlan(
                    new HistoricalPlayerResolver.HistoricalPlayerIdentity(BUILDER, "Builder"),
                    now - 3_600_000, now,
                    List.of(new RollbackTarget("world", 1, 64, 2,
                            "minecraft:air", "minecraft:stone", null, null,
                            WORLD.toString(), "tnt", "Explosion: TNT",
                            BUILDER.toString(), "chain", "TNT_CHAIN")),
                    1, 12, 0, 0);
            FragGuardGuiRenderer.playerRollbackPreview("testtoken", plan, null, false);
            assertTrue(details.get(slots.get(14)).lore.stream()
                    .anyMatch(s -> s.contains("Historical conflicts: 12")));
            assertTrue(details.get(slots.get(16)).lore.stream()
                    .anyMatch(s -> s.contains("testtoken")));
            assertTrue(details.get(slots.get(22)).title.contains("Cancel Preview"));
            assertFalse(slots.containsKey(24), "a scattered player preview has no fake radius control");
        }
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static final class ItemDetails {
        String title;
        List<String> lore = List.of();
    }
}
