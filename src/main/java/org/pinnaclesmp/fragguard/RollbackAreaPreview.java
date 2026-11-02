package org.pinnaclesmp.fragguard;

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
    private final FragGuardPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    RollbackAreaPreview(FragGuardPlugin plugin) {
        this.plugin = plugin;
    }

    void show(Player operator, String token, String worldName,
              int centerX, int centerZ, int radius, long expiresAt) {
        UUID actor = operator.getUniqueId();
        stop(actor);
        RollbackPreviewVisualSettings settings = RollbackPreviewVisualSettings.from(plugin.getConfig());
        if (!settings.enabled()) {
            return;
        }
        World sourceWorld = plugin.getServer().getWorld(worldName);
        UUID worldId = sourceWorld == null ? null : sourceWorld.getUID();
        Session session = new Session(token, worldName, worldId, centerX, centerZ,
                expiresAt, settings,
                RollbackBoundaryGeometry.perimeter(centerX, centerZ, radius, settings.samples(radius)));
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
        }, 0L, settings.updateTicks());
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

        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();
        double bottom = minHeight + 0.5;
        double top = maxHeight - 0.5;
        double eyeLevel = RollbackBoundaryGeometry.clampHeight(
                location.getY() + 0.5, minHeight, maxHeight);

        // Geometry is constructed once when the preview starts. Each scheduled
        // frame reuses the immutable perimeter, and only the operator's Y changes.
        for (RollbackBoundaryGeometry.Point point : session.perimeter) {
            particle(operator, world, point.x(), bottom, point.z(), session.height);
            particle(operator, world, point.x(), top, point.z(), session.height);
            if (session.settings.operatorRing()) {
                particle(operator, world, point.x(), eyeLevel, point.z(), session.border);
            }
        }

        // Eight sparse columns, never one particle per block or per height.
        List<Double> levels = RollbackBoundaryGeometry.verticalLevels(minHeight, maxHeight,
                session.settings.verticalSections(minHeight, maxHeight));
        for (int i = 0; i < RollbackBoundaryGeometry.VERTICAL_COLUMNS; i++) {
            int index = i * session.perimeter.size() / RollbackBoundaryGeometry.VERTICAL_COLUMNS;
            RollbackBoundaryGeometry.Point point = session.perimeter.get(index);
            for (double level : levels) {
                particle(operator, world, point.x(), level, point.z(), session.height);
            }
        }

        // The origin stays fixed to the command's center even as the operator moves.
        double x = session.centerX + 0.5;
        double z = session.centerZ + 0.5;
        for (double y : new double[]{bottom, eyeLevel, top}) {
            particle(operator, world, x, y, z, session.center);
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
        private final RollbackPreviewVisualSettings settings;
        private final Particle.DustOptions border;
        private final Particle.DustOptions height;
        private final Particle.DustOptions center;
        private BukkitTask task;

        private Session(String token, String worldName, UUID worldId,
                        int centerX, int centerZ, long expiresAt,
                        RollbackPreviewVisualSettings settings,
                        List<RollbackBoundaryGeometry.Point> perimeter) {
            this.token = token;
            this.worldName = worldName;
            this.worldId = worldId;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.expiresAt = expiresAt;
            this.settings = settings;
            this.perimeter = perimeter;
            this.border = new Particle.DustOptions(settings.borderColor(), 1.4f);
            this.height = new Particle.DustOptions(settings.heightColor(), 1.0f);
            this.center = new Particle.DustOptions(settings.centerColor(), 1.8f);
        }
    }
}
