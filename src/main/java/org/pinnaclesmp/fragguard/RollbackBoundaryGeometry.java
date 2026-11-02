package org.pinnaclesmp.fragguard;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded presentation geometry for the full-height cylindrical X/Z rollback scope.
 * A block at (x,z) is eligible when (x-centerX)^2 + (z-centerZ)^2 <= radius^2.
 * Particle coordinates use block centers for the same origin; no world state is changed.
 */
final class RollbackBoundaryGeometry {
    static final int PERIMETER_SAMPLES = 48;
    static final int VERTICAL_COLUMNS = 8;
    static final int VERTICAL_SAMPLES = 5;

    private RollbackBoundaryGeometry() {
    }

    static List<Point> perimeter(int centerX, int centerZ, int radius, int samples) {
        if (radius < 1 || samples < 8 || samples > 128) {
            throw new IllegalArgumentException("Invalid rollback boundary radius or sample count");
        }
        List<Point> points = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
            double angle = (2.0 * Math.PI * i) / samples;
            points.add(new Point(centerX + 0.5 + radius * Math.cos(angle),
                    centerZ + 0.5 + radius * Math.sin(angle)));
        }
        return List.copyOf(points);
    }

    static double clampHeight(double requested, int minHeight, int maxHeight) {
        return Math.max(minHeight + 0.5, Math.min(maxHeight - 0.5, requested));
    }

    /** Sparse interior guide levels; top/bottom rings are rendered separately. */
    static List<Double> verticalLevels(int minHeight, int maxHeight, int sections) {
        if (maxHeight <= minHeight || sections < 2 || sections > 13) {
            return List.of();
        }
        double bottom = minHeight + 0.5;
        double top = maxHeight - 0.5;
        List<Double> levels = new ArrayList<>(sections - 1);
        for (int i = 1; i < sections; i++) {
            levels.add(bottom + (top - bottom) * i / sections);
        }
        return List.copyOf(levels);
    }

    record Point(double x, double z) {
    }
}
