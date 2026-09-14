package com.presaleagent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.presaleagent.memory.ConversationMessage;
import com.presaleagent.memory.MemoryContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ContextAssembler {
    private final ObjectMapper objectMapper;
    private final com.presaleagent.config.PreSaleAgentProperties properties;

    public ContextAssembler(ObjectMapper objectMapper, com.presaleagent.config.PreSaleAgentProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public ConversationContext assemble(String question, MemoryContext memory, String catalogEvidence, String knowledgeEvidence) {
        StringBuilder prompt = new StringBuilder();
        append(prompt, "[当前用户问题]", question);
        List<ConversationMessage> recent = memory == null ? List.of() : memory.recentMessages();
        int tail = properties.getContext().getTailMessages();
        append(prompt, "[当前会话最近消息]", recent.stream().skip(Math.max(0, recent.size() - tail))
                .map(m -> m.role().name().toLowerCase() + ": " + m.content()).reduce((a, b) -> a + "\n" + b).orElse(""));
        append(prompt, "[商品目录证据]", catalogEvidence);
        append(prompt, "[商品知识库证据]", knowledgeEvidence);
        if (memory != null) {
            append(prompt, "[当前会话摘要]", memory.summary());
            append(prompt, "[用户画像（仅用于推荐口径，不是商品事实）]", safeJson(memory.userProfile()));
            append(prompt, "[跨会话情景记忆]", String.join("\n", memory.relevantHistory()));
        }
        String value = prompt.toString();
        int used = ContextBudget.estimate(value);
        ContextBudget budget = new ContextBudget(properties.getContext().getMaxTokens(), used, 1024, 256);
        return new ConversationContext(value, recent, memory == null ? "" : memory.summary(),
                memory == null ? java.util.Map.of() : memory.userProfile(), budget,
                budget.exceeds(properties.getContext().getCompactThreshold()));
    }

    private void append(StringBuilder out, String heading, String value) {
        if (value != null && !value.isBlank()) out.append(heading).append('\n').append(value).append("\n\n");
    }

    private String safeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ignored) { return String.valueOf(value); }
    }
}
