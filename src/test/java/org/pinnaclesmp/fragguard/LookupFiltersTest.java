package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookupFiltersTest {
    @Test
    void catalogUsesStableActorIdentityAndReturnedLookupValues() {
        List<LookupRow> rows = List.of(
                row(10_000, "actor-a", "Alice", ChangeAction.BREAK, "minecraft:stone", "minecraft:air"),
                row(9_900, "actor-a", "Alice", ChangeAction.PLACE, "minecraft:air", "minecraft:dirt"),
                row(9_800, "actor-b", "Alice", ChangeAction.BREAK, "minecraft:oak_log", "minecraft:air"));

        LookupFilters.Catalog catalog = LookupFilters.catalog(rows);

        assertEquals(2, catalog.players().size(),
                "identical display names with different stored identities must remain distinct filter options");
        assertEquals(2, catalog.actions().size());
        assertEquals(3, catalog.materials().size());
        assertEquals(3, catalog.players().stream().mapToInt(LookupFilters.Option::count).sum());
    }

    @Test
    void playerActionAndMaterialFiltersCanBeCombinedWithoutChangingRawHistory() {
        List<LookupRow> rows = List.of(
                row(10_000, "actor-a", "Alice", ChangeAction.BREAK, "minecraft:stone", "minecraft:air"),
                row(9_900, "actor-a", "Alice", ChangeAction.BREAK, "minecraft:dirt", "minecraft:air"),
                row(9_800, "actor-b", "Bob", ChangeAction.BREAK, "minecraft:stone", "minecraft:air"),
                row(9_700, "actor-a", "Alice", ChangeAction.PLACE, "minecraft:air", "minecraft:stone"));
        LookupResultSnapshot source = LookupResultSnapshot.fromRows(rows, 2_500, 6);
        LookupFilters.State state = LookupFilters.State.empty()
                .with(LookupFilters.Category.PLAYER, "actor-a")
                .with(LookupFilters.Category.ACTION, ChangeAction.BREAK.storageId())
                .with(LookupFilters.Category.MATERIAL, "minecraft:stone");

        LookupFilters.View filtered = LookupFilters.apply(source, state);

        assertEquals(1, filtered.rows().size());
        assertEquals("actor-a", filtered.rows().getFirst().actorIdentity());
        assertEquals(ChangeAction.BREAK, filtered.rows().getFirst().action());
        assertEquals("minecraft:stone", LookupActivityGrouper.materialKey(filtered.rows().getFirst()));
        assertEquals(4, source.rows().size(), "filtering must not modify the immutable source lookup history");
        assertTrue(filtered.activities().stream().allMatch(activity -> activity.rows().stream()
                .allMatch(row -> row.actorIdentity().equals("actor-a")
                        && row.action() == ChangeAction.BREAK
                        && LookupActivityGrouper.materialKey(row).equals("minecraft:stone"))));
    }

    @Test
    void selectingAnyClearsOnlyThatFilterCategory() {
        LookupFilters.State state = LookupFilters.State.empty()
                .with(LookupFilters.Category.PLAYER, "actor-a")
                .with(LookupFilters.Category.ACTION, ChangeAction.BREAK.storageId())
                .with(LookupFilters.Category.MATERIAL, "minecraft:stone")
                .with(LookupFilters.Category.ACTION, null);

        assertEquals("actor-a", state.actorIdentity());
        assertEquals(null, state.action());
        assertEquals("minecraft:stone", state.materialKey());
    }

    private LookupRow row(long happenedAt, String actorIdentity, String actorName,
                          ChangeAction action, String before, String after) {
        return new LookupRow(happenedAt, actorIdentity, actorName, "world", 10, 64, 20,
                action, before, after);
    }
}
