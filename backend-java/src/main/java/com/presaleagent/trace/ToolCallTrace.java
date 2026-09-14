package com.presaleagent.trace;

public record ToolCallTrace(
        String toolName,
        boolean success,
        boolean fallbackUsed,
        boolean cached,
        boolean reranked,
        long latencyMs,
        String error,
        boolean timedOut,
        boolean blocked,
        String status
) {
    public ToolCallTrace(String toolName, boolean success, boolean fallbackUsed, boolean cached,
                         boolean reranked, long latencyMs, String error) {
        this(toolName, success, fallbackUsed, cached, reranked, latencyMs, error, false, false,
                success ? "SUCCESS" : "ERROR");
    }
}
