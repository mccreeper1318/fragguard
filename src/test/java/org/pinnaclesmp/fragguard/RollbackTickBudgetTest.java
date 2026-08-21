package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackTickBudgetTest {
    @Test
    void sharesTheTimeLimitAcrossSeparateSlicesInTheSameServerTick() {
        RollbackTickBudget budget = new RollbackTickBudget();

        assertTrue(budget.begin(40, 1_000L, 100L));
        assertFalse(budget.exhausted(1_060L));
        budget.end(1_060L);

        assertTrue(budget.begin(40, 2_000L, 100L));
        assertFalse(budget.exhausted(2_039L));
        assertTrue(budget.exhausted(2_040L));
        budget.end(2_040L);

        assertFalse(budget.begin(40, 3_000L, 100L));
    }

    @Test
    void restoresTheFullTimeBudgetAtTheNextServerTick() {
        RollbackTickBudget budget = new RollbackTickBudget();

        assertTrue(budget.begin(40, 100L, 10L));
        budget.end(110L);
        assertFalse(budget.begin(40, 120L, 10L));
        assertTrue(budget.begin(41, 130L, 10L));
        assertFalse(budget.exhausted(139L));
        assertTrue(budget.exhausted(140L));
        budget.end(140L);
    }

    @Test
    void rejectsOverlappingWorkSlices() {
        RollbackTickBudget budget = new RollbackTickBudget();

        assertTrue(budget.begin(40, 100L, 10L));
        assertThrows(IllegalStateException.class, () -> budget.begin(40, 101L, 10L));
        budget.end(101L);
    }

    @Test
    void acceptsNegativeMonotonicClockValues() {
        RollbackTickBudget budget = new RollbackTickBudget();

        assertTrue(budget.begin(40, -100L, 10L));
        assertFalse(budget.exhausted(-91L));
        assertTrue(budget.exhausted(-90L));
        budget.end(-90L);
        assertFalse(budget.begin(40, -80L, 10L));
    }

    @Test
    void committedAuditSliceFinishesEvenWhenTheTickBudgetWasAlreadyUsed() {
        RollbackTickBudget budget = new RollbackTickBudget();

        assertTrue(budget.begin(40, 100L, 10L));
        budget.end(110L);
        assertFalse(budget.begin(40, 120L, 10L));

        budget.beginCommitted(40, 130L, 10L);
        assertTrue(budget.exhausted(130L));
        budget.end(132L);

        assertFalse(budget.begin(40, 140L, 10L));
        assertTrue(budget.begin(41, 150L, 10L));
        budget.end(151L);
    }
}
