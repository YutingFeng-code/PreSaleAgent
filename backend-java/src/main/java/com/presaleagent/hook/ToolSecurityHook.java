package com.presaleagent.hook;

import com.presaleagent.agent.AgentType;
import com.presaleagent.tool.ToolSecurityService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Order(0)
public class ToolSecurityHook implements PreSaleAgentHook {
    private final ToolSecurityService security;

    public ToolSecurityHook(ToolSecurityService security) { this.security = security; }

    @Override public HookEventType eventType() { return HookEventType.PRE_TOOL_CALL; }

    /**
     * 工具调用前置安全检查（@Order(0) 最先执行）。
     * <p>
     * 检查策略分三层：
     * 1. Agent 身份合法性——拒绝未知/伪造的 Agent 发起的调用
     * 2. Agent 枚举匹配——统一格式后比对已知类型，消除大小写/分隔符差异
     * 3. 委托 ToolSecurityService 做参数级细粒度校验（白名单、注入检测、频率等）
     */
    @Override
    public HookDecision handle(HookContext context) {
        // ── 第 1 层：Agent 身份非空校验 ──
        // 无法识别调用方身份时直接拒绝，防止空参绕过
        if (context.agentType() == null || context.agentType().isBlank()) {
            return HookDecision.block("未知 Agent，拒绝工具调用");
        }
        // ── 第 2 层：Agent 类型归一化 + 枚举白名单匹配 ──
        // 将传入的 agentType 统一为小写 + 下划线格式，容忍 'product-advisor'、'Product Advisor' 等变体
        AgentType agent = AgentType.fromWireValue(context.agentType());
        String normalizedAgent = context.agentType().trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
        // 遍历所有已知 AgentType，检查归一化后的名称是否匹配 wireValue 或枚举名
        boolean knownAgent = java.util.Arrays.stream(AgentType.values())
                .anyMatch(type -> type.wireValue().equals(normalizedAgent) || type.name().toLowerCase(java.util.Locale.ROOT).equals(normalizedAgent));
        if (!knownAgent) {
            return HookDecision.block("未知 Agent，拒绝工具调用");
        }
        // ── 第 3 层：提取工具调用参数，委托 ToolSecurityService 做细粒度校验 ──
        Map<String, Object> attrs = context.attributes();
        String query = String.valueOf(attrs.getOrDefault("query", ""));
        String category = String.valueOf(attrs.getOrDefault("category", ""));
        // limit 参数安全提取：仅接受 Number 类型，默认值为 5
        int limit = attrs.get("limit") instanceof Number n ? n.intValue() : 5;
        // 综合校验：Agent-工具白名单、查询注入检测、分类合法性、参数边界等
        ToolSecurityService.Validation result = security.validate(agent, context.toolName(), query, category, limit);
        return result.allowed() ? HookDecision.allow() : HookDecision.block(result.reason());
    }
}
