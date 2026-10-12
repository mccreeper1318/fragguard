package org.pinnaclesmp.fragguard;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiMaterialIconTest {
    // Material.isItem() consults the Paper registry, which is not bootstrapped in unit tests.
    // Exercise the resolver's non-item behavior through its explicit predicate test seam.
    @ParameterizedTest
    @ValueSource(strings = {
            "WATER_CAULDRON", "MOVING_PISTON", "WATER",
            "LAVA", "FIRE", "REDSTONE_WIRE", "AIR"
    })
    void historicalNonItemBlocksUseInventorySafeFallback(String blockKey) {
        Material recorded = Material.matchMaterial(blockKey);
        assertNotNull(recorded);

        String storedKey = "minecraft:" + blockKey.toLowerCase(Locale.ROOT);
        Predicate<Material> nonItem = ignored -> false;
        assertEquals(Material.PAPER, GuiMaterialIcon.fromHistory(storedKey, nonItem));
        assertEquals(Material.PAPER, GuiMaterialIcon.asItem(recorded, nonItem));
    }

    @Test
    void unknownBlankAndNullHistoryKeysUseSafeFallback() {
        assertEquals(Material.PAPER, GuiMaterialIcon.fromHistory(null));
        assertEquals(Material.PAPER, GuiMaterialIcon.fromHistory(""));
        assertEquals(Material.PAPER, GuiMaterialIcon.fromHistory("   "));
        assertEquals(Material.PAPER,
                GuiMaterialIcon.fromHistory("minecraft:not_a_real_block", material -> true));
        assertEquals(Material.PAPER, GuiMaterialIcon.asItem(null, material -> true));
        assertEquals(Material.PAPER, GuiMaterialIcon.asItem(Material.WATER, material -> false));
    }

    @Test
    void recognizedInventoryMaterialsRetainTheirOriginalIcons() {
        Set<Material> availableItems = Set.of(
                Material.CHEST, Material.DIAMOND_BLOCK, Material.OAK_LOG, Material.WATER_BUCKET);
        Predicate<Material> isItem = availableItems::contains;

        assertEquals(Material.CHEST, GuiMaterialIcon.fromHistory("minecraft:chest", isItem));
        assertEquals(Material.DIAMOND_BLOCK,
                GuiMaterialIcon.fromHistory("MINECRAFT:DIAMOND_BLOCK", isItem));
        assertEquals(Material.OAK_LOG, GuiMaterialIcon.fromHistory("  minecraft:oak_log  ", isItem));
        assertEquals(Material.WATER_BUCKET,
                GuiMaterialIcon.fromHistory("minecraft:water_bucket", isItem));
        assertEquals(Material.CHEST, GuiMaterialIcon.asItem(Material.CHEST, isItem));
        assertTrue(isItem.test(GuiMaterialIcon.fromHistory("minecraft:chest", isItem)));
    }

    @Test
    void fallbackIconDoesNotChangeTheHistoryMaterialOrDisplayName() {
        LookupRow stored = new LookupRow(
                System.currentTimeMillis(), "Builder", "world", 10, 64, 10,
                ChangeAction.BREAK, "minecraft:water_cauldron[level=3]",
                "minecraft:water_cauldron[level=2]");

        String key = LookupActivityGrouper.materialKey(stored);
        assertEquals("minecraft:water_cauldron", key);
        assertEquals("Water Cauldron", LookupActivityGrouper.displayMaterial(key));
        assertEquals(Material.PAPER, GuiMaterialIcon.fromHistory(key, material -> false));
        assertEquals("minecraft:water_cauldron[level=3]", stored.beforeData());
        assertEquals("minecraft:water_cauldron[level=2]", stored.afterData());
    }
}
