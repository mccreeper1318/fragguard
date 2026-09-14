package org.pinnaclesmp.fragguard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class LookupActivityGrouper {
    static final long DEFAULT_MAX_DURATION_MILLIS = 15_000L;
    static final int DEFAULT_MAX_SPAN = 24;

    private LookupActivityGrouper() {
    }

    static List<LookupActivity> group(List<LookupRow> rows, long maxGapMillis, int maxDistance) {
        return group(rows, maxGapMillis, maxDistance, DEFAULT_MAX_DURATION_MILLIS, DEFAULT_MAX_SPAN);
    }

    static List<LookupActivity> group(List<LookupRow> rows, long maxGapMillis, int maxDistance,
                                      long maxDurationMillis, int maxSpan) {
        if (rows.isEmpty()) {
            return List.of();
        }
        long localGap = Math.max(0L, maxGapMillis);
        int localDistance = Math.max(0, maxDistance);
        long totalDuration = Math.max(0L, maxDurationMillis);
        int totalSpan = Math.max(0, maxSpan);
        List<LookupRow> ordered = rows.stream()
                .sorted(Comparator.comparingLong(LookupRow::happenedAt).reversed())
                .toList();
        List<MutableActivity> finished = new ArrayList<>();
        MutableActivity current = null;
        for (LookupRow row : ordered) {
            Key key = new Key(row.actorIdentity(), row.action(), materialKey(row));
            if (current == null
                    || !current.key.equals(key)
                    || !current.canAppend(row, localGap, localDistance, totalDuration, totalSpan)) {
                if (current != null) {
                    finished.add(current);
                }
                current = new MutableActivity(key, row);
            } else {
                current.append(row);
            }
        }
        if (current != null) {
            finished.add(current);
        }
        finished.sort(Comparator.comparingLong(MutableActivity::newestAt).reversed());
        return finished.stream().map(MutableActivity::freeze).toList();
    }

    static String materialKey(LookupRow row) {
        String before = baseBlock(row.beforeData());
        String after = baseBlock(row.afterData());
        if (isAir(after) && !isAir(before)) {
            return before;
        }
        if (isAir(before) && !isAir(after)) {
            return after;
        }
        return after;
    }

    static String displayMaterial(String key) {
        if (key == null || key.isBlank()) {
            return "Unknown";
        }
        StringBuilder result = new StringBuilder();
        for (String word : key.replace("minecraft:", "").toLowerCase(Locale.ROOT).split("_")) {
            if (word.isBlank()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                result.append(word.substring(1));
            }
        }
        return result.length() == 0 ? "Unknown" : result.toString();
    }

    private static String baseBlock(String value) {
        if (value == null || value.isBlank()) {
            return "minecraft:air";
        }
        int bracket = value.indexOf('[');
        return bracket >= 0 ? value.substring(0, bracket) : value;
    }

    private static boolean isAir(String value) {
        return value.equals("air") || value.equals("minecraft:air")
                || value.equals("cave_air") || value.equals("minecraft:cave_air")
                || value.equals("void_air") || value.equals("minecraft:void_air");
    }

    private record Key(String actorIdentity, ChangeAction action, String materialKey) {
    }

    private static final class MutableActivity {
        private final Key key;
        private final String actorName;
        private final List<LookupRow> rows = new ArrayList<>();
        private long newestAt;
        private long oldestAt;
        private int minX;
        private int minY;
        private int minZ;
        private int maxX;
        private int maxY;
        private int maxZ;
        private int lastX;
        private int lastY;
        private int lastZ;

        private MutableActivity(Key key, LookupRow first) {
            this.key = key;
            actorName = first.actorName();
            newestAt = oldestAt = first.happenedAt();
            minX = maxX = lastX = first.x();
            minY = maxY = lastY = first.y();
            minZ = maxZ = lastZ = first.z();
            rows.add(first);
        }

        private boolean canAppend(LookupRow row, long maxGapMillis, int maxDistance,
                                  long maxDurationMillis, int maxSpan) {
            long gap = Math.max(0L, oldestAt - row.happenedAt());
            long duration = Math.max(0L, newestAt - row.happenedAt());
            if (gap > maxGapMillis || duration > maxDurationMillis
                    || Math.abs((long) row.x() - lastX) > maxDistance
                    || Math.abs((long) row.y() - lastY) > maxDistance
                    || Math.abs((long) row.z() - lastZ) > maxDistance) {
                return false;
            }

            int proposedMinX = Math.min(minX, row.x());
            int proposedMinY = Math.min(minY, row.y());
            int proposedMinZ = Math.min(minZ, row.z());
            int proposedMaxX = Math.max(maxX, row.x());
            int proposedMaxY = Math.max(maxY, row.y());
            int proposedMaxZ = Math.max(maxZ, row.z());
            return (long) proposedMaxX - proposedMinX <= maxSpan
                    && (long) proposedMaxY - proposedMinY <= maxSpan
                    && (long) proposedMaxZ - proposedMinZ <= maxSpan;
        }

        private void append(LookupRow row) {
            rows.add(row);
            oldestAt = Math.min(oldestAt, row.happenedAt());
            minX = Math.min(minX, row.x());
            minY = Math.min(minY, row.y());
            minZ = Math.min(minZ, row.z());
            maxX = Math.max(maxX, row.x());
            maxY = Math.max(maxY, row.y());
            maxZ = Math.max(maxZ, row.z());
            lastX = row.x();
            lastY = row.y();
            lastZ = row.z();
        }

        private long newestAt() {
            return newestAt;
        }

        private LookupActivity freeze() {
            return new LookupActivity(actorName, key.action(), key.materialKey(), newestAt, oldestAt,
                    minX, minY, minZ, maxX, maxY, maxZ, rows);
        }
    }
}
