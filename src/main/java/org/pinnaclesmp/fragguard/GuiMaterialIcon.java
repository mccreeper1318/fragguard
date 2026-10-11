package org.pinnaclesmp.fragguard;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Converts stored block material keys into inventory-safe display icons.
 *
 * <p>Block history can contain materials such as WATER, WATER_CAULDRON, and
 * MOVING_PISTON that are valid world blocks but cannot be used as ItemStacks.
 * The display icon may fall back to PAPER; the original history material and
 * before/after block data must remain unchanged in the lookup.</p>
 */
final class GuiMaterialIcon {
    private GuiMaterialIcon() {
    }

    static Material fromHistory(String key) {
        if (key == null || key.isBlank()) {
            return Material.PAPER;
        }

        String normalized = key.trim();
        if (normalized.regionMatches(true, 0, "minecraft:", 0, "minecraft:".length())) {
            normalized = normalized.substring("minecraft:".length());
        }
        return asItem(Material.matchMaterial(normalized.toUpperCase(Locale.ROOT)));
    }

    static Material asItem(Material material) {
        return material != null && material.isItem() ? material : Material.PAPER;
    }
}
