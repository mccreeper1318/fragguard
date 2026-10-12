package org.pinnaclesmp.fragguard;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Orders the GUI's snapshot/count after accepted log writes without a legacy history scan. */
final class GuiLookupStartup {
    private GuiLookupStartup() {
    }

    static CompletableFuture<GuiLookupOverview> prepare(
            Database database, GuiLookupStore guiLookupStore, GuiLookupQuery query) {
        return prepare(database, guiLookupStore, query, Logger.getLogger("FragGuard"));
    }

    static CompletableFuture<GuiLookupOverview> prepare(
            Database database, GuiLookupStore guiLookupStore,
            GuiLookupQuery query, Logger logger) {
        long started = System.nanoTime();
        // The worker flushes all writes accepted before this operation.
        // Snapshot/count are run once through the GUI read-only connection;
        // expensive filter catalogs can be loaded on demand afterward.
        return database.awaitWritesAsync().thenCompose(ignored -> {
            logger.info("FragGuard GUI write barrier completed in "
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " ms");
            return guiLookupStore.prepareOverviewAsync(query);
        });
    }
}
