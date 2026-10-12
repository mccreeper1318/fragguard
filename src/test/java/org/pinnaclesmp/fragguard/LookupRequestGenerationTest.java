package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookupRequestGenerationTest {

    @Test
    void rejectsOlderLookupWhenNewerCompletesFirst() {
        LookupRequestGeneration generation = new LookupRequestGeneration();
        long olderRequest = generation.begin();
        long newerRequest = generation.begin();
        List<String> appliedResults = new ArrayList<>();

        CompletableFuture<String> olderLookup = new CompletableFuture<>();
        CompletableFuture<String> newerLookup = new CompletableFuture<>();

        olderLookup.thenAccept(result -> {
            if (generation.isCurrent(olderRequest)) {
                appliedResults.add(result);
            }
        });
        newerLookup.thenAccept(result -> {
            if (generation.isCurrent(newerRequest)) {
                appliedResults.add(result);
            }
        });

        newerLookup.complete("newer");
        olderLookup.complete("older");

        assertTrue(newerRequest > olderRequest);
        assertEquals(List.of("newer"), appliedResults);
    }

    @Test
    void invalidationRejectsPendingLookup() {
        LookupRequestGeneration generation = new LookupRequestGeneration();
        long request = generation.begin();

        assertTrue(generation.isCurrent(request));

        generation.invalidate();

        assertFalse(generation.isCurrent(request));
    }
}
