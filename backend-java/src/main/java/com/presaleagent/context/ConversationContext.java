package com.presaleagent.context;

import com.presaleagent.memory.ConversationMessage;
import java.util.List;
import java.util.Map;

public record ConversationContext(String prompt, List<ConversationMessage> recentMessages,
                                  String summary, Map<String, Object> profile,
                                  ContextBudget budget, boolean compactionRequired) {
    public ConversationContext {
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        profile = profile == null ? Map.of() : Map.copyOf(profile);
        summary = summary == null ? "" : summary;
    }
}
