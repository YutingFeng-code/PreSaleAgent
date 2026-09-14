package com.presaleagent.hook;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class HookDispatcher {
    private static final Logger log = LoggerFactory.getLogger(HookDispatcher.class);
    private final List<PreSaleAgentHook> hooks;
    private final Counter failures;

    public HookDispatcher(List<PreSaleAgentHook> hooks, MeterRegistry registry) {
        this.hooks = hooks == null ? List.of() : hooks.stream().sorted(AnnotationAwareOrderComparator.INSTANCE).toList();
        this.failures = Counter.builder("presaleagent_hook_failure_total").register(registry);
    }

    /**
     * 按注册顺序依次执行与当前事件类型匹配的 Hook，聚合出一个最终决策结果。
     *
     * <p>执行语义：
     * <ul>
     *   <li>MODIFY：把 Hook 返回的 changes 合并进上下文，传给后续 Hook（链式改写）。</li>
     *   <li>BLOCK / REQUIRE_APPROVAL：短路返回，不再执行剩余 Hook。</li>
     *   <li>ALLOW 或返回 null：视为放行，继续下一个 Hook。</li>
     * </ul>
     *
     * <p>异常策略（fail closed vs fail open）：安全相关的 PRE_TOOL_CALL、PRE_RESPONSE_VERIFY
     * 一旦 Hook 抛异常即拦截；其余可观测类 Hook 异常仅计数告警后继续放行。
     *
     * @param context 本次事件的基础上下文
     * @return 聚合后的决策、可能被 MODIFY 更新过的上下文，以及累计执行耗时（毫秒）
     */
    public DispatchResult dispatch(HookContext context) {
        // current 承载链式改写后的上下文，latency 累计所有已执行 Hook 的耗时
        HookContext current = context;
        long latency = 0;
        for (PreSaleAgentHook hook : hooks) {
            // 只处理与当前事件类型匹配的 Hook，其余跳过
            if (hook.eventType() != context.eventType()) continue;
            Instant start = Instant.now();
            try {
                HookDecision decision = hook.handle(current);
                latency += Duration.between(start, Instant.now()).toMillis();
                // 返回 null 等同于不表达意见，直接放行到下一个 Hook
                if (decision == null) continue;
                // 拦截 / 需审批：立即短路返回，保留此刻已积累的上下文与耗时
                if (decision.action() == HookAction.BLOCK || decision.action() == HookAction.REQUIRE_APPROVAL) {
                    return new DispatchResult(decision, current, latency);
                }
                // MODIFY：把变更合并进上下文，供后续 Hook 在改写后的数据上继续处理
                if (decision.action() == HookAction.MODIFY) current = current.withChanges(decision.changes());
            } catch (Exception ex) {
                failures.increment();
                log.warn("Hook {} failed for {}: {}", hook.getClass().getSimpleName(), context.eventType(), ex.getMessage());
                // Security hooks fail closed; observability hooks fail open.
                // 安全校验类事件异常时按“失败即拦截”处理，避免带病放行
                if (context.eventType() == HookEventType.PRE_TOOL_CALL || context.eventType() == HookEventType.PRE_RESPONSE_VERIFY) {
                    return new DispatchResult(HookDecision.block("安全校验失败，已阻止本次操作"), current, latency);
                }
                // 其余事件异常时“失败即放行”，继续执行下一个 Hook
            }
        }
        // 全部 Hook 均放行：返回 ALLOW 及最终上下文
        return new DispatchResult(HookDecision.allow(), current, latency);
    }

    public record DispatchResult(HookDecision decision, HookContext context, long latencyMs) {
        public boolean blocked() { return decision.action() == HookAction.BLOCK || decision.action() == HookAction.REQUIRE_APPROVAL; }
    }
}
