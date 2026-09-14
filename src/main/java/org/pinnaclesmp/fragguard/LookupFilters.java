package org.pinnaclesmp.fragguard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LookupFilters {
    private LookupFilters() {
    }

    enum Category {
        PLAYER("Player"),
        ACTION("Action"),
        MATERIAL("Material");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }
    }

    record State(String actorIdentity, ChangeAction action, String materialKey) {
        static State empty() {
            return new State(null, null, null);
        }

        boolean active() {
            return actorIdentity != null || action != null || materialKey != null;
        }

        String selectedKey(Category category) {
            return switch (category) {
                case PLAYER -> actorIdentity;
                case ACTION -> action == null ? null : action.storageId();
                case MATERIAL -> materialKey;
            };
        }

        State with(Category category, String key) {
            String normalized = key == null || key.isBlank() ? null : key;
            return switch (category) {
                case PLAYER -> new State(normalized, action, materialKey);
                case ACTION -> new State(actorIdentity,
                        normalized == null ? null : ChangeAction.fromStorageId(normalized), materialKey);
                case MATERIAL -> new State(actorIdentity, action, normalized);
            };
        }
    }

    record Option(String key, String label, int count) {
    }

    record Catalog(List<Option> players, List<Option> actions, List<Option> materials) {
        Catalog {
            players = List.copyOf(players);
            actions = List.copyOf(actions);
            materials = List.copyOf(materials);
        }

        static Catalog empty() {
            return new Catalog(List.of(), List.of(), List.of());
        }

        List<Option> options(Category category) {
            return switch (category) {
                case PLAYER -> players;
                case ACTION -> actions;
                case MATERIAL -> materials;
            };
        }

        String selectedLabel(State state, Category category) {
            String selected = state.selectedKey(category);
            if (selected == null) {
                return "Any";
            }
            return options(category).stream()
                    .filter(option -> option.key().equals(selected))
                    .map(Option::label)
                    .findFirst()
                    .orElse("Unknown");
        }
    }

    record View(List<LookupRow> rows, List<LookupActivity> activities) {
        View {
            rows = List.copyOf(rows);
            activities = List.copyOf(activities);
        }

        static View empty() {
            return new View(List.of(), List.of());
        }
    }

    static Catalog catalog(List<LookupRow> rows) {
        Map<String, PlayerCount> players = new LinkedHashMap<>();
        Map<ChangeAction, Integer> actions = new LinkedHashMap<>();
        Map<String, Integer> materials = new LinkedHashMap<>();

        for (LookupRow row : rows) {
            players.compute(row.actorIdentity(), (identity, current) -> {
                if (current == null) {
                    return new PlayerCount(actor(row.actorName()), 1);
                }
                return new PlayerCount(current.label(), current.count() + 1);
            });
            actions.merge(row.action(), 1, Integer::sum);
            materials.merge(LookupActivityGrouper.materialKey(row), 1, Integer::sum);
        }

        Comparator<Option> byLabel = Comparator.comparing(Option::label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Option::key);
        List<Option> playerOptions = players.entrySet().stream()
                .map(entry -> new Option(entry.getKey(), entry.getValue().label(), entry.getValue().count()))
                .sorted(byLabel)
                .toList();
        List<Option> actionOptions = actions.entrySet().stream()
                .map(entry -> new Option(entry.getKey().storageId(), displayAction(entry.getKey()), entry.getValue()))
                .sorted(byLabel)
                .toList();
        List<Option> materialOptions = materials.entrySet().stream()
                .map(entry -> new Option(entry.getKey(),
                        LookupActivityGrouper.displayMaterial(entry.getKey()), entry.getValue()))
                .sorted(byLabel)
                .toList();
        return new Catalog(playerOptions, actionOptions, materialOptions);
    }

    static View apply(LookupResultSnapshot source, State state) {
        if (!state.active()) {
            return new View(source.rows(), source.activities());
        }
        List<LookupRow> rows = source.rows().stream()
                .filter(row -> matches(row, state))
                .toList();
        List<LookupActivity> activities = source.activities().stream()
                .filter(activity -> !activity.rows().isEmpty() && matches(activity.rows().getFirst(), state))
                .toList();
        return new View(rows, activities);
    }

    static String summary(State state, Catalog catalog) {
        if (!state.active()) {
            return "No filters applied";
        }
        List<String> parts = new ArrayList<>(3);
        if (state.actorIdentity() != null) {
            parts.add("Player: " + catalog.selectedLabel(state, Category.PLAYER));
        }
        if (state.action() != null) {
            parts.add("Action: " + catalog.selectedLabel(state, Category.ACTION));
        }
        if (state.materialKey() != null) {
            parts.add("Material: " + catalog.selectedLabel(state, Category.MATERIAL));
        }
        return String.join(" | ", parts);
    }

    private static boolean matches(LookupRow row, State state) {
        return (state.actorIdentity() == null || state.actorIdentity().equals(row.actorIdentity()))
                && (state.action() == null || state.action() == row.action())
                && (state.materialKey() == null
                    || state.materialKey().equals(LookupActivityGrouper.materialKey(row)));
    }

    private static String displayAction(ChangeAction action) {
        String[] words = action.name().toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder display = new StringBuilder();
        for (String word : words) {
            if (display.length() > 0) {
                display.append(' ');
            }
            display.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return display.toString();
    }

    private static String actor(String actorName) {
        return actorName == null || actorName.isBlank() ? "Unknown" : actorName;
    }

    private record PlayerCount(String label, int count) {
    }
}
