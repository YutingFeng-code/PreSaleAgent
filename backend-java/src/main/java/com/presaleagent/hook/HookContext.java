package com.presaleagent.hook;

import java.util.LinkedHashMap;
import java.util.Map;

public record HookContext(HookEventType eventType, String requestId, String userId,
                          String conversationId, String agentType, String toolName,
                          Map<String, Object> attributes) {
    public HookContext {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
    public HookContext withChanges(Map<String, Object> changes) {
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        if (changes != null) merged.putAll(changes);
        return new HookContext(eventType, requestId, userId, conversationId, agentType, toolName, merged);
    }
}
