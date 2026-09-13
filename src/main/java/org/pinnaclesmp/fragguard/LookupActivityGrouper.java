package org.pinnaclesmp.fragguard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class LookupActivityGrouper {
    private LookupActivityGrouper() {
    }

    static List<LookupActivity> group(List<LookupRow> rows, long maxGapMillis, int maxDistance) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<LookupRow> ordered = rows.stream()
                .sorted(Comparator.comparingLong(LookupRow::happenedAt).reversed())
                .toList();
        Map<Key, MutableActivity> active = new HashMap<>();
        List<MutableActivity> finished = new ArrayList<>();
        for (LookupRow row : ordered) {
            Key key = new Key(row.actorName(), row.action(), materialKey(row));
            MutableActivity current = active.get(key);
            if (current == null || !current.canAppend(row, Math.max(0L, maxGapMillis), Math.max(0, maxDistance))) {
                if (current != null) {
                    finished.add(current);
                }
                current = new MutableActivity(key, row);
                active.put(key, current);
            } else {
                current.append(row);
            }
        }
        finished.addAll(active.values());
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

    private record Key(String actorName, ChangeAction action, String materialKey) {
    }

    private static final class MutableActivity {
        private final Key key;
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
            newestAt = oldestAt = first.happenedAt();
            minX = maxX = lastX = first.x();
            minY = maxY = lastY = first.y();
            minZ = maxZ = lastZ = first.z();
            rows.add(first);
        }

        private boolean canAppend(LookupRow row, long maxGapMillis, int maxDistance) {
            long gap = Math.max(0L, oldestAt - row.happenedAt());
            return gap <= maxGapMillis
                    && Math.abs(row.x() - lastX) <= maxDistance
                    && Math.abs(row.y() - lastY) <= maxDistance
                    && Math.abs(row.z() - lastZ) <= maxDistance;
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
            return new LookupActivity(key.actorName(), key.action(), key.materialKey(), newestAt, oldestAt,
                    minX, minY, minZ, maxX, maxY, maxZ, rows);
        }
    }
}
