package com.presaleagent.agent;

import com.presaleagent.llm.LlmGateway;
import com.presaleagent.skill.SkillManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Configuration
public class AgentConfig {

    @Bean
    Map<AgentType, List<BaseAgent>> agentPool(LlmGateway llmGateway, SkillManager skillManager) {
        return Map.of(
                AgentType.GENERAL, List.of(new GeneralAgent(llmGateway, skillManager)),
                AgentType.PRODUCT_ADVISOR, List.of(new ProductAdvisorAgent(llmGateway, skillManager)),
                AgentType.ESCALATION, List.of(new EscalationAgent(llmGateway, skillManager))
        );
    }
}
