package org.pinnaclesmp.fragguard;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Client-only particle visualization for a pending rollback, addressed to its
 * operator with Player.spawnParticle rather than World.spawnParticle.
 * Does not spawn entities, place blocks, or write rollback history.
 */
final class RollbackAreaPreview {
    private static final long REFRESH_TICKS = 20L;
    private static final Particle.DustOptions BORDER = new Particle.DustOptions(Color.AQUA, 1.2f);
    private static final Particle.DustOptions HEIGHT = new Particle.DustOptions(Color.YELLOW, 1.0f);
    private static final Particle.DustOptions CENTER = new Particle.DustOptions(Color.LIME, 1.8f);

    private final FragGuardPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    RollbackAreaPreview(FragGuardPlugin plugin) {
        this.plugin = plugin;
    }

    void show(Player operator, String token, String worldName,
              int centerX, int centerZ, int radius, long expiresAt) {
        UUID actor = operator.getUniqueId();
        stop(actor);
        World sourceWorld = plugin.getServer().getWorld(worldName);
        UUID worldId = sourceWorld == null ? null : sourceWorld.getUID();
        Session session = new Session(token, worldName, worldId, centerX, centerZ,
                radius, expiresAt,
                RollbackBoundaryGeometry.perimeter(centerX, centerZ, radius,
                        RollbackBoundaryGeometry.PERIMETER_SAMPLES));
        sessions.put(actor, session);

        // The timer renders only while this exact session is live. In particular,
        // a stale callback cannot render an old token or remove its replacement.
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (sessions.get(actor) != session) {
                return;
            }
            if (!operator.isOnline() || System.currentTimeMillis() >= session.expiresAt) {
                stop(actor, session.token);
                return;
            }
            renderFrame(operator, session);
        }, 0L, REFRESH_TICKS);
        // Defensive: if a scheduler/test invokes an initial frame before it
        // returns, the frame may already have invalidated this session.
        if (sessions.get(actor) == session) {
            session.task = task;
        } else if (task != null) {
            task.cancel();
        }
    }

    void stop(UUID actor) {
        Session old = sessions.remove(actor);
        if (old != null && old.task != null) {
            old.task.cancel();
        }
    }

    void stop(UUID actor, String token) {
        Session session = sessions.get(actor);
        if (session != null && session.token.equals(token)) {
            stop(actor);
        }
    }

    void clear() {
        for (UUID actor : List.copyOf(sessions.keySet())) {
            stop(actor);
        }
    }

    boolean isShowing(UUID actor, String token) {
        Session session = sessions.get(actor);
        return session != null && session.token.equals(token);
    }

    private void renderFrame(Player operator, Session session) {
        World world = operator.getWorld();
        if (world == null || !Objects.equals(world.getName(), session.worldName)
                || (session.worldId != null && !session.worldId.equals(world.getUID()))) {
            return; // Never display the outline in another world.
        }
        Location location = operator.getLocation();
        if (location == null || world.getMaxHeight() <= world.getMinHeight()) {
            return;
        }

        double bottom = world.getMinHeight() + 0.5;
        double top = world.getMaxHeight() - 0.5;
        double eyeLevel = RollbackBoundaryGeometry.clampHeight(
                location.getY() + 0.5, world.getMinHeight(), world.getMaxHeight());

        // Sparse full-height cylinders: lower and upper rings, a ring at the
        // operator's height, and eight vertical guide columns.
        for (RollbackBoundaryGeometry.Point point : session.perimeter) {
            particle(operator, world, point.x(), bottom, point.z(), HEIGHT);
            particle(operator, world, point.x(), top, point.z(), HEIGHT);
            particle(operator, world, point.x(), eyeLevel, point.z(), BORDER);
        }
        for (int i = 0; i < RollbackBoundaryGeometry.VERTICAL_COLUMNS; i++) {
            int index = i * session.perimeter.size() / RollbackBoundaryGeometry.VERTICAL_COLUMNS;
            RollbackBoundaryGeometry.Point point = session.perimeter.get(index);
            for (int step = 1; step <= RollbackBoundaryGeometry.VERTICAL_SAMPLES; step++) {
                double y = bottom + (top - bottom)
                        * step / (RollbackBoundaryGeometry.VERTICAL_SAMPLES + 1);
                particle(operator, world, point.x(), y, point.z(), HEIGHT);
            }
        }

        // Distinct origin guide, referenced to the original command's block
        // center, not the player's location after asynchronous query completion.
        double x = session.centerX + 0.5;
        double z = session.centerZ + 0.5;
        for (double y : new double[]{bottom, eyeLevel, top}) {
            particle(operator, world, x, y, z, CENTER);
        }
    }

    private static void particle(Player operator, World world,
                                 double x, double y, double z, Particle.DustOptions options) {
        // The default particle overload has a short client range. Force the
        // extended range so operators can see larger rollback radii from the center.
        // Player.spawnParticle still targets only this operator, never the whole world.
        operator.spawnParticle(Particle.DUST, new Location(world, x, y, z),
                1, 0.0, 0.0, 0.0, 0.0, options, true);
    }

    private static final class Session {
        private final String token;
        private final String worldName;
        private final UUID worldId;
        private final int centerX;
        private final int centerZ;
        private final long expiresAt;
        private final List<RollbackBoundaryGeometry.Point> perimeter;
        private BukkitTask task;

        private Session(String token, String worldName, UUID worldId,
                        int centerX, int centerZ, int radius, long expiresAt,
                        List<RollbackBoundaryGeometry.Point> perimeter) {
            this.token = token;
            this.worldName = worldName;
            this.worldId = worldId;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.expiresAt = expiresAt;
            this.perimeter = perimeter;
        }
    }
}
