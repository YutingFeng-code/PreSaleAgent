package com.presaleagent.agent;

import com.presaleagent.llm.LlmGateway;
import com.presaleagent.skill.SkillManager;

public class EscalationAgent extends BaseAgent {
    public EscalationAgent(LlmGateway llmGateway, SkillManager skillManager) { super(llmGateway, skillManager); }
    @Override public AgentType type() { return AgentType.ESCALATION; }
    @Override protected String systemPrompt() {
        return "你负责人工升级交接。整理已知信息并说明下一步，不要声称执行了后台操作。";
    }
}
