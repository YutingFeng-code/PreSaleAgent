package com.presaleagent.context;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContextBudgetTest {
    @Test void estimatesTextAndDetectsThreshold() {
        ContextBudget budget = new ContextBudget(100, ContextBudget.estimate("商品推荐"), 10, 5);
        assertTrue(budget.remainingTokens() > 0);
        assertTrue(new ContextBudget(100, 75, 0, 0).exceeds(.75));
    }
}
