package org.pinnaclesmp.fragguard;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Particle;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RollbackAreaPreviewTest {
    private static final UUID OPERATOR = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID WORLD_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void sampledCylinderUsesRealCircularRadiusAndOriginalBlockCenter() {
        for (int radius : List.of(1, 10, 30, 100, 1000)) {
            var boundary = RollbackBoundaryGeometry.perimeter(16, -22, radius,
                    RollbackBoundaryGeometry.PERIMETER_SAMPLES);
            assertEquals(48, boundary.size(), "density stays bounded for large radii");
            for (var p : boundary) {
                assertEquals(radius, Math.hypot(p.x() - 16.5, p.z() + 21.5), 1e-9);
            }
        }
        assertEquals(-63.5, RollbackBoundaryGeometry.clampHeight(-100, -64, 320));
        assertEquals(319.5, RollbackBoundaryGeometry.clampHeight(400, -64, 320));
    }

    @Test
    void particlesAreSentOnlyToTheCorrectOperatorAndWorld() {
        Fixture f = fixture();
        RollbackAreaPreview preview = f.preview;
        preview.show(f.operator, "token", "world", 16, -22, 50, Long.MAX_VALUE);
        assertTrue(preview.isShowing(OPERATOR, "token"));
        f.render().run();

        ArgumentCaptor<Location> locations = ArgumentCaptor.forClass(Location.class);
        verify(f.operator, atLeastOnce()).spawnParticle(eq(Particle.DUST),
                locations.capture(), eq(1), eq(0.0), eq(0.0), eq(0.0), eq(0.0),
                any(Particle.DustOptions.class), eq(true));
        verify(f.operator, never()).spawnParticle(eq(Particle.DUST),
                any(Location.class), eq(1), any(Particle.DustOptions.class));
        List<Location> points = locations.getAllValues();
        assertTrue(points.stream().anyMatch(loc ->
                loc.getX() == 16.5 && loc.getZ() == -21.5 && loc.getY() == -63.5));
        assertTrue(points.stream().anyMatch(loc ->
                loc.getX() == 16.5 && loc.getZ() == -21.5 && loc.getY() == 319.5));
        assertTrue(points.stream().anyMatch(loc ->
                Math.abs(Math.hypot(loc.getX() - 16.5, loc.getZ() + 21.5) - 50) < 1e-8));
        assertTrue(points.size() <= 200, "markers must remain bounded independent of radius");
        for (Location location : points) {
            assertEquals(f.world, location.getWorld());
            assertTrue(location.getY() >= -63.5 && location.getY() <= 319.5);
        }

        Player bystander = mock(Player.class);
        verify(bystander, never()).spawnParticle(eq(Particle.DUST),
                any(Location.class), eq(1), eq(0.0), eq(0.0), eq(0.0),
                eq(0.0), any(Particle.DustOptions.class), eq(true));
    }

    @Test
    void mismatchedWorldNeverReceivesBoundaryMarkers() {
        Fixture f = fixture();
        World elsewhere = mock(World.class);
        when(elsewhere.getName()).thenReturn("other");
        when(f.operator.getWorld()).thenReturn(elsewhere);
        f.preview.show(f.operator, "token", "world", 16, -22, 20, Long.MAX_VALUE);
        f.render().run();

        verify(f.operator, never()).spawnParticle(eq(Particle.DUST),
                any(Location.class), eq(1), eq(0.0), eq(0.0), eq(0.0),
                eq(0.0), any(Particle.DustOptions.class), eq(true));
    }

    @Test
    void largeRadiusPerimetersUseExtendedRangeNotNormalParticleRange() {
        for (int radius : List.of(50, 75, 100)) {
            Fixture f = fixture();
            f.preview.show(f.operator, "token", "world", 16, -22, radius, Long.MAX_VALUE);
            f.render().run();

            ArgumentCaptor<Location> locations = ArgumentCaptor.forClass(Location.class);
            verify(f.operator, atLeastOnce()).spawnParticle(eq(Particle.DUST),
                    locations.capture(), eq(1), eq(0.0), eq(0.0), eq(0.0),
                    eq(0.0), any(Particle.DustOptions.class), eq(true));

            assertTrue(locations.getAllValues().stream().anyMatch(loc ->
                    Math.abs(Math.hypot(loc.getX() - 16.5, loc.getZ() + 21.5) - radius) < 1e-8
                            && Math.hypot(loc.getX() - 16.5, loc.getZ() + 21.5) > 32),
                    "large radius should have forced perimeter particles beyond normal range");
            verify(f.operator, never()).spawnParticle(eq(Particle.DUST),
                    any(Location.class), eq(1), any(Particle.DustOptions.class));
            verify(f.operator, never()).spawnParticle(eq(Particle.DUST),
                    any(Location.class), eq(1), eq(0.0), eq(0.0), eq(0.0),
                    eq(0.0), any(Particle.DustOptions.class), eq(false));
        }
    }

    @Test
    void operatorHeightRingUpdatesWithoutRecreatingThePerimeter() {
        Fixture f = fixture();
        when(f.operator.getLocation()).thenReturn(
                new Location(f.world, 16, 72, -22),
                new Location(f.world, 16, 130, -22));
        f.preview.show(f.operator, "token", "world", 16, -22, 100, Long.MAX_VALUE);
        Runnable frame = f.render();
        frame.run();
        assertTrue(hasBorderAt(f.operator, 72.5));

        clearInvocations(f.operator);
        frame.run();
        assertTrue(hasBorderAt(f.operator, 130.5),
                "moving or flying updates the visible ring at the next scheduled refresh");
        assertFalse(hasBorderAt(f.operator, 72.5),
                "the old operator-height ring is not resent");
        assertTrue(f.preview.isShowing(OPERATOR, "token"),
                "movement does not replace the active preview session");
    }

    private static boolean hasBorderAt(Player player, double y) {
        ArgumentCaptor<Location> points = ArgumentCaptor.forClass(Location.class);
        ArgumentCaptor<Particle.DustOptions> options =
                ArgumentCaptor.forClass(Particle.DustOptions.class);
        verify(player, atLeastOnce()).spawnParticle(eq(Particle.DUST),
                points.capture(), eq(1), eq(0.0), eq(0.0), eq(0.0),
                eq(0.0), options.capture(), eq(true));
        for (int i = 0; i < points.getAllValues().size(); i++) {
            if (options.getAllValues().get(i).getColor().equals(Color.AQUA)
                    && Math.abs(points.getAllValues().get(i).getY() - y) < 1e-9) {
                return true;
            }
        }
        return false;
    }

    @Test
    void visualizationCanBeDisabledWithoutSchedulingParticleTasks() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("rollback-preview-visualization-enabled", false);
        Fixture f = fixture(config);
        f.preview.show(f.operator, "token", "world", 16, -22, 100, Long.MAX_VALUE);
        assertFalse(f.preview.isShowing(OPERATOR, "token"));
        verify(f.scheduler, never()).runTaskTimer(eq(f.plugin),
                any(Runnable.class), eq(0L), eq(20L));
    }

    @Test
    void customRefreshRateAndHighQualityRemainBounded() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("rollback-preview-update-ticks", 40);
        config.set("rollback-preview-perimeter-quality", 2);
        config.set("rollback-preview-vertical-spacing", 16);
        config.set("rollback-preview-operator-ring-enabled", false);
        Fixture f = fixture(config);
        f.preview.show(f.operator, "token", "world", 16, -22, 100, Long.MAX_VALUE);
        verify(f.scheduler).runTaskTimer(eq(f.plugin),
                any(Runnable.class), eq(0L), eq(40L));

        // The guide remains bounded and, with the height ring disabled,
        // no border-color particles should be emitted.
        ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
        verify(f.scheduler).runTaskTimer(eq(f.plugin), tick.capture(), eq(0L), eq(40L));
        tick.getValue().run();
        ArgumentCaptor<Particle.DustOptions> options =
                ArgumentCaptor.forClass(Particle.DustOptions.class);
        verify(f.operator, atLeastOnce()).spawnParticle(eq(Particle.DUST),
                any(Location.class), eq(1), eq(0.0), eq(0.0), eq(0.0),
                eq(0.0), options.capture(), eq(true));
        assertTrue(options.getAllValues().size() <= 400);
        assertTrue(options.getAllValues().stream()
                .noneMatch(option -> option.getColor().equals(Color.AQUA)));
    }

    @Test
    void replacementAndStaleTokensCannotCancelTheCurrentBoundary() {
        Fixture f = fixture();
        f.preview.show(f.operator, "first", "world", 16, -22, 20, Long.MAX_VALUE);
        f.preview.show(f.operator, "next", "world", 16, -22, 20, Long.MAX_VALUE);

        verify(f.task).cancel();
        f.preview.stop(OPERATOR, "first");
        assertTrue(f.preview.isShowing(OPERATOR, "next"));
        f.preview.stop(OPERATOR, "next");
        assertFalse(f.preview.isShowing(OPERATOR, "next"));
    }

    private static Fixture fixture() {
        return fixture(null);
    }

    private static Fixture fixture(YamlConfiguration config) {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        if (config != null) {
            when(plugin.getConfig()).thenReturn(config);
        }
        Server server = mock(Server.class);
        World world = mock(World.class);
        Player operator = mock(Player.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);

        when(plugin.getServer()).thenReturn(server);
        when(server.getWorld("world")).thenReturn(world);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(0L), any(Long.class)))
                .thenReturn(task);
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(operator.getUniqueId()).thenReturn(OPERATOR);
        when(operator.isOnline()).thenReturn(true);
        when(operator.getWorld()).thenReturn(world);
        when(operator.getLocation()).thenReturn(new Location(world, 16, 72, -22));

        RollbackAreaPreview preview = new RollbackAreaPreview(plugin);
        return new Fixture(preview, operator, world, task, scheduler, plugin);
    }

    private record Fixture(RollbackAreaPreview preview, Player operator, World world,
                           BukkitTask task, BukkitScheduler scheduler, FragGuardPlugin plugin) {
        private Runnable render() {
            ArgumentCaptor<Runnable> ticks = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler, atLeastOnce()).runTaskTimer(any(FragGuardPlugin.class),
                    ticks.capture(), eq(0L), eq(20L));
            return ticks.getAllValues().getLast();
        }
    }
}
