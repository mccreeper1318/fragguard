package org.pinnaclesmp.fragguard;

import org.bukkit.Color;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackPreviewVisualSettingsTest {
    @Test
    void defaultsScaleWithRadiusWithoutChangingCircularGeometry() {
        RollbackPreviewVisualSettings settings = RollbackPreviewVisualSettings.from(new YamlConfiguration());
        assertTrue(settings.enabled());
        assertTrue(settings.operatorRing());
        assertEquals(20, settings.updateTicks());
        for (int[] example : new int[][]{{1, 24}, {10, 24}, {11, 32}, {25, 32},
                {26, 48}, {50, 48}, {51, 64}, {100, 64}, {10000, 64}}) {
            int radius = example[0];
            int samples = settings.samples(radius);
            assertEquals(example[1], samples);
            var perimeter = RollbackBoundaryGeometry.perimeter(33, -18, radius, samples);
            assertEquals(samples, perimeter.size());
            for (var point : perimeter) {
                assertEquals(radius, Math.hypot(point.x() - 33.5, point.z() + 17.5), 1e-7);
            }
        }
        assertEquals(Color.AQUA, settings.borderColor());
        assertEquals(Color.YELLOW, settings.heightColor());
        assertEquals(Color.LIME, settings.centerColor());
    }

    @Test
    void userSettingsAreValidatedAndColorsFallbackSafely() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("rollback-preview-visualization-enabled", false);
        config.set("rollback-preview-perimeter-quality", 200);
        config.set("rollback-preview-vertical-spacing", -2);
        config.set("rollback-preview-operator-ring-enabled", false);
        config.set("rollback-preview-update-ticks", 1);
        config.set("rollback-preview-border-color", "#1234AB");
        config.set("rollback-preview-height-color", "red");
        config.set("rollback-preview-center-color", "#00000000");
        RollbackPreviewVisualSettings settings = RollbackPreviewVisualSettings.from(config);
        assertFalse(settings.enabled());
        assertFalse(settings.operatorRing());
        assertEquals(2, settings.quality());
        assertEquals(16, settings.verticalSpacing());
        assertEquals(10, settings.updateTicks());
        assertEquals(Color.fromRGB(0x1234AB), settings.borderColor());
        assertEquals(Color.YELLOW, settings.heightColor());
        assertEquals(Color.LIME, settings.centerColor());
        assertEquals(80, settings.samples(100));

        config.set("rollback-preview-perimeter-quality", -100);
        config.set("rollback-preview-vertical-spacing", 9999);
        config.set("rollback-preview-update-ticks", 9999);
        settings = RollbackPreviewVisualSettings.from(config);
        assertEquals(0, settings.quality());
        assertEquals(192, settings.verticalSpacing());
        assertEquals(100, settings.updateTicks());
        assertEquals(16, settings.samples(1));
    }

    @Test
    void verticalGuideCountAndBuildHeightRemainBoundedInTallWorlds() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("rollback-preview-vertical-spacing", 16);
        var settings = RollbackPreviewVisualSettings.from(config);
        assertEquals(13, settings.verticalSections(-64, 2048));
        assertEquals(0, settings.verticalSections(320, 320));
        assertEquals(List.of(), RollbackBoundaryGeometry.verticalLevels(320, 320, 2));
        List<Double> levels = RollbackBoundaryGeometry.verticalLevels(
                -64, 2048, settings.verticalSections(-64, 2048));
        assertEquals(12, levels.size());
        assertTrue(levels.stream().allMatch(y -> y > -63.5 && y < 2047.5));
        assertTrue(levels.getFirst() < levels.getLast());

        int maximumParticlesPerFrame = 3 * settings.samples(100)
                + RollbackBoundaryGeometry.VERTICAL_COLUMNS * levels.size() + 3;
        assertTrue(maximumParticlesPerFrame <= 400);
    }

    @Test
    void nullConfigurationStillUsesUsableDefaults() {
        var settings = RollbackPreviewVisualSettings.from(null);
        assertTrue(settings.enabled());
        assertEquals(48, settings.samples(30));
        assertEquals(20, settings.updateTicks());
    }
}
