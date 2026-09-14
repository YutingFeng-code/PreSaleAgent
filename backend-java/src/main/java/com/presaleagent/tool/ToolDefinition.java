package com.presaleagent.tool;

import com.presaleagent.agent.AgentType;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

public record ToolDefinition(String name, Set<AgentType> allowedAgents,
                             Map<String, Object> inputSchema, Duration timeout,
                             boolean requiresApproval) {
    public ToolDefinition {
        allowedAgents = allowedAgents == null ? Set.of() : Set.copyOf(allowedAgents);
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
    }
}
