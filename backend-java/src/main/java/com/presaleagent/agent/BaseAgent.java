package com.presaleagent.agent;

import com.presaleagent.llm.LlmGateway;
import com.presaleagent.skill.SkillManager;
import com.presaleagent.trace.ToolCallTrace;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public abstract class BaseAgent {

    private final LlmGateway llmGateway;
    private final SkillManager skillManager;
    private final AgentStats stats = new AgentStats();

    protected BaseAgent(LlmGateway llmGateway, SkillManager skillManager) {
        this.llmGateway = llmGateway;
        this.skillManager = skillManager;
    }

    public abstract AgentType type();

    protected abstract String systemPrompt();

    public AgentResponse handle(AgentRequest request) {
        Instant start = Instant.now();
        try {
            String prompt = buildPrompt(request);
            String content = llmGateway.chat(buildSystemPrompt(request), prompt, temperature(), 1024);
            long latency = Duration.between(start, Instant.now()).toMillis();
            boolean escalate = needsEscalation(content);
            stats.record(true, latency);
            return new AgentResponse(type(), content, true, 1.0, latency, escalate, "", true, false, false, "");
        } catch (Exception ex) {
            long latency = Duration.between(start, Instant.now()).toMillis();
            stats.record(false, latency);
            return new AgentResponse(type(), "抱歉，处理您的请求时出现问题，请稍后重试。", false, 0.0, latency, false, "", false, false, false, ex.getMessage());
        }
    }

    public AgentStats stats() {
        return stats;
    }

    protected double temperature() {
        return 0.2;
    }

    private String buildPrompt(AgentRequest request) {
        StringBuilder prompt = new StringBuilder();
        if (request.context() != null && !request.context().isBlank()) {
            prompt.append("[背景信息]\n").append(request.context()).append("\n\n");
        }
        if (request.entities() != null && !request.entities().isEmpty()) {
            prompt.append("[结构化实体]\n").append(request.entities()).append("\n\n");
        }
        prompt.append("[用户问题]\n").append(request.message());
        return prompt.toString();
    }

    /**
     * 构造发送给 LLM 的 system prompt：以子类提供的静态 {@link #systemPrompt()} 为基底，
     * 并根据当前意图选择并注入一个动态任务 Skill。
     *
     * <p>回退策略：SkillManager 缺失或未匹配到任何 Skill（prompt 为空）时，仅返回基础提示词。
     *
     * @param request 当前请求，用于提取用户消息以匹配相关 Skill
     * @return 基础 system prompt（可能附加一个“[动态 Skills]”段落）
     */
    private String buildSystemPrompt(AgentRequest request) {
        // 无 SkillManager 时不拼接动态内容，直接用基础提示词
        if (skillManager == null) {
            return systemPrompt();
        }
        // 按当前 Agent + 意图精确选择一个 Skill；无明确意图时走旧关键词兜底
        String skillPrompt = skillManager.promptFor(
                request.message(),
                type().wireValue(),
                request.intent() == null ? "" : request.intent().wireValue()
        );
        // 未匹配到任何 Skill：回退为基础提示词
        if (skillPrompt.isBlank()) {
            return systemPrompt();
        }
        // 命中则将动态 Skills 追加到基础提示词末尾
        return systemPrompt() + "\n\n[动态 Skills]\n" + skillPrompt;
    }

    private boolean needsEscalation(String content) {
        String text = content == null ? "" : content.toLowerCase();
        return text.contains("转人工")
                || text.contains("人工客服")
                || text.contains("escalate")
                || text.contains("specialist")
                || text.contains("无法处理");
    }
}
