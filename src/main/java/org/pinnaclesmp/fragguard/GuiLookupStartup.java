package org.pinnaclesmp.fragguard;

import java.util.concurrent.CompletableFuture;

/** Orders the GUI's snapshot/count after accepted log writes without a legacy history scan. */
final class GuiLookupStartup {
    private GuiLookupStartup() {
    }

    static CompletableFuture<GuiLookupOverview> prepare(
            Database database, GuiLookupStore guiLookupStore, GuiLookupQuery query) {
        // The database worker flushes accepted writes at its queued-operation barrier.
        // Only the GUI store performs the initial count and catalog queries.
        return database.awaitWritesAsync()
                .thenCompose(ignored -> guiLookupStore.prepareLookupAsync(query));
    }
}
