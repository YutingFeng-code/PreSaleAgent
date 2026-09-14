package com.presaleagent.trace;

import java.util.List;

public record RequestToolTrace(
        String requestId,
        String timestamp,
        String endpoint,
        String userId,
        String conversationId,
        String intent,
        String intentGroup,
        String agentType,
        String primaryAgent,
        List<String> supportingAgents,
        List<String> toolsUsed,
        List<ToolCallTrace> toolCalls,
        boolean knowledgeUsed,
        boolean escalated,
        long latencyMs,
        int contextTokens,
        String compactionTrigger,
        long compactionGeneration,
        String catalogStatus,
        int evidenceCount,
        boolean factViolation,
        String hookAction,
        long hookLatencyMs,
        boolean toolTimeout,
        boolean toolBlocked,
        boolean cacheHit
) {
    public RequestToolTrace(String requestId, String timestamp, String endpoint, String userId,
                            String conversationId, String intent, String intentGroup, String agentType,
                            String primaryAgent, List<String> supportingAgents, List<String> toolsUsed,
                            List<ToolCallTrace> toolCalls, boolean knowledgeUsed, boolean escalated, long latencyMs) {
        this(requestId, timestamp, endpoint, userId, conversationId, intent, intentGroup, agentType,
                primaryAgent, supportingAgents, toolsUsed, toolCalls, knowledgeUsed, escalated, latencyMs,
                0, "", 0, "UNKNOWN", 0, false, "ALLOW", 0, false, false,
                toolCalls != null && toolCalls.stream().anyMatch(ToolCallTrace::cached));
    }
}
