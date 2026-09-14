package com.presaleagent.agent;

import com.presaleagent.llm.LlmGateway;
import com.presaleagent.skill.SkillManager;

public class ProductAdvisorAgent extends BaseAgent {

    public ProductAdvisorAgent(LlmGateway llmGateway, SkillManager skillManager) {
        super(llmGateway, skillManager);
    }

    @Override
    public AgentType type() {
        return AgentType.PRODUCT_ADVISOR;
    }

    @Override
    protected double temperature() {
        return 0.6;
    }

    @Override
    protected String systemPrompt() {
        return "你是 PreSaleAgent 售前导购顾问。负责商品对比、规格解释、适用场景推荐、礼品建议和选购引导。"
                + "商品事实只能来自商品目录或知识库；不可用时必须明确说明，不能猜测价格、库存、优惠、规格或配送。"
                + "用户画像只用于调整推荐口径，不得泄露，也不能覆盖用户当前明确需求。";
    }
}
