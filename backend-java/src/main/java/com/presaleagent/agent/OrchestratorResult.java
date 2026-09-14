package com.presaleagent.agent;

import com.presaleagent.intent.IntentCategory;
import com.presaleagent.trace.ToolCallTrace;

import java.util.List;

public record OrchestratorResult(
        String requestId,
        String response,
        AgentType agentType,
        IntentCategory intent,
        boolean escalated,
        long latencyMs,
        List<AgentType> agentTypes,
        AgentType primaryAgent,
        List<AgentType> supportingAgents,
        List<String> toolsUsed,
        List<ToolCallTrace> toolCalls,
        String routingReason,
        double routingConfidence
) {
}
