package com.presaleagent.context;

/** Deterministic token budget used before a provider-specific tokenizer is added. */
public record ContextBudget(int maxTokens, int usedTokens, int reservedAnswerTokens,
                            int reservedToolTokens) {
    public ContextBudget {
        maxTokens = Math.max(1, maxTokens);
        usedTokens = Math.max(0, usedTokens);
        reservedAnswerTokens = Math.max(0, reservedAnswerTokens);
        reservedToolTokens = Math.max(0, reservedToolTokens);
    }

    public int remainingTokens() {
        return Math.max(0, maxTokens - usedTokens - reservedAnswerTokens - reservedToolTokens);
    }

    public boolean exceeds(double threshold) {
        return usedTokens >= Math.round(maxTokens * Math.max(0.1, Math.min(1.0, threshold)));
    }

    public static int estimate(String text) {
        if (text == null || text.isBlank()) return 0;
        // Chinese text is close to one token/character; Latin text needs a small allowance.
        return Math.max(1, (int) Math.ceil(text.codePointCount(0, text.length()) / 1.8));
    }
}
