package com.presaleagent.agent;

import com.presaleagent.intent.IntentCategory;
import com.presaleagent.intent.IntentRecognizer;
import com.presaleagent.intent.IntentResult;
import com.presaleagent.intent.UrgencyLevel;
import com.presaleagent.trace.RequestTraceStore;
import com.presaleagent.trace.RequestToolTrace;
import com.presaleagent.trace.ToolCallTrace;
import com.presaleagent.hook.HookContext;
import com.presaleagent.hook.HookDispatcher;
import com.presaleagent.hook.HookEventType;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Service
public class AgentOrchestrator {

    private final IntentRecognizer intentRecognizer;
    private final Map<AgentType, List<BaseAgent>> pool;
    private final RequestTraceStore traceStore;
    private final Executor agentExecutor;
    private final HookDispatcher hookDispatcher;
    private final Map<IntentCategory, AgentType> routing = new EnumMap<>(IntentCategory.class);

    @org.springframework.beans.factory.annotation.Autowired
    public AgentOrchestrator(IntentRecognizer intentRecognizer, Map<AgentType, List<BaseAgent>> pool, RequestTraceStore traceStore,
                             @org.springframework.beans.factory.annotation.Qualifier("agentTaskExecutor") Executor agentExecutor,
                             HookDispatcher hookDispatcher) {
        this.intentRecognizer = intentRecognizer;
        this.pool = pool;
        this.traceStore = traceStore;
        this.agentExecutor = agentExecutor;
        this.hookDispatcher = hookDispatcher;
        routing.put(IntentCategory.TECHNICAL, AgentType.TECHNICAL);
        routing.put(IntentCategory.TECHNICAL_LOGIN, AgentType.TECHNICAL);
        routing.put(IntentCategory.TECHNICAL_CRASH, AgentType.TECHNICAL);
        routing.put(IntentCategory.BILLING, AgentType.BILLING);
        routing.put(IntentCategory.REFUND, AgentType.BILLING);
        routing.put(IntentCategory.INVOICE, AgentType.BILLING);
        routing.put(IntentCategory.PAYMENT_ISSUE, AgentType.BILLING);
        routing.put(IntentCategory.ACCOUNT, AgentType.BILLING);
        routing.put(IntentCategory.ACCOUNT_SECURITY, AgentType.BILLING);
        routing.put(IntentCategory.ESCALATION, AgentType.ESCALATION);
        routing.put(IntentCategory.HUMAN_HANDOFF, AgentType.ESCALATION);
        routing.put(IntentCategory.PRODUCT_COMPARE, AgentType.PRODUCT_ADVISOR);
        routing.put(IntentCategory.PRODUCT_RECOMMEND, AgentType.PRODUCT_ADVISOR);
        routing.put(IntentCategory.SPEC_INQUIRY, AgentType.PRODUCT_ADVISOR);
        routing.put(IntentCategory.AVAILABILITY, AgentType.PRODUCT_ADVISOR);
        routing.put(IntentCategory.PRICE_PROMOTION, AgentType.PRODUCT_ADVISOR);
    }

    public AgentOrchestrator(IntentRecognizer intentRecognizer, Map<AgentType, List<BaseAgent>> pool, RequestTraceStore traceStore,
                             Executor agentExecutor) {
        this(intentRecognizer, pool, traceStore, agentExecutor, null);
    }

    public AgentOrchestrator(IntentRecognizer intentRecognizer, Map<AgentType, List<BaseAgent>> pool, RequestTraceStore traceStore) {
        this(intentRecognizer, pool, traceStore, Executors.newFixedThreadPool(4), null);
    }

    public OrchestratorResult run(AgentRequest request) {
        return run(request, List.of());
    }

    /**
     * 多 Agent 编排主入口：根据意图完成路由并生成最终回复。
     *
     * <p>处理分支：
     * <ol>
     *   <li>若请求未携带意图，先补做一次意图识别；</li>
     *   <li>低置信度的 OTHER 意图先返回澄清提问，不进入 Agent 执行；</li>
     *   <li>路由决策若含辅助 Agent（multiAgent），走 {@link #runParallel} 并发协作；</li>
     *   <li>否则由主 Agent 单路执行，并根据回复/紧急度/意图判断是否升级人工。</li>
     * </ol>
     *
     * @param request            Agent 请求；intent 为空时会在此补识别
     * @param externalToolCalls  上游已产生的工具调用轨迹（如知识库/商品目录），会合并进结果与 trace
     * @return 聚合回复、路由信息、工具使用与升级标记等的 {@link OrchestratorResult}（同时写入 trace 存储）
     */
    public OrchestratorResult run(AgentRequest request, List<ToolCallTrace> externalToolCalls) {
        Instant start = Instant.now();
        AgentRequest req = request;
        // 上游未传意图时，编排器自行识别一次，后续路由/校验都基于该结果
        if (req.intent() == null) {
            IntentResult intentResult = intentRecognizer.recognize(req.message(), req.history());
            req = req.withIntent(intentResult);
        }

        // 低置信度的 OTHER 意图：先向用户澄清，避免盲目路由，直接返回并记录 trace
        if (needsClarification(req)) {
            OrchestratorResult result = new OrchestratorResult(
                    req.requestId(),
                    "我还不能确定您的售前需求。请补充一下是商品对比、规格参数、库存/发货时效、商品推荐还是价格优惠？",
                    AgentType.GENERAL,
                    req.intent(),
                    false,
                    Duration.between(start, Instant.now()).toMillis(),
                    List.of(AgentType.GENERAL),
                    AgentType.GENERAL,
                    List.of(),
                    collectToolNames(externalToolCalls),
                    collectToolCalls(externalToolCalls),
                    "低置信度 OTHER 意图，先澄清用户需求",
                    req.intentConfidence()
            );
            recordTrace(req, result);
            return result;
        }

        // 计算路由决策；存在辅助 Agent 时走多 Agent 并发协作
        RoutingDecision decision = routeDecision(req);
        if (decision.multiAgent()) {
            return runParallel(req, decision, externalToolCalls);
        }

        // 单 Agent 路径：交给主 Agent 执行
        AgentResponse response = execute(req, decision.primaryAgent());
        // 升级判定：Agent 主动升级、紧急度 CRITICAL，或意图为转人工/交接，任一成立即升级
        boolean escalated = response.escalate()
                || req.urgency() == UrgencyLevel.CRITICAL
                || req.intent() == IntentCategory.ESCALATION
                || req.intent() == IntentCategory.HUMAN_HANDOFF;
        OrchestratorResult result = new OrchestratorResult(
                req.requestId(),
                response.content(),
                response.agentType(),
                req.intent(),
                escalated,
                Duration.between(start, Instant.now()).toMillis(),
                List.of(response.agentType()),
                decision.primaryAgent(),
                List.of(),
                collectToolNames(externalToolCalls, response),
                collectToolCalls(externalToolCalls, response),
                decision.reason(),
                decision.confidence()
        );
        recordTrace(req, result);
        return result;
    }

    public Optional<RequestToolTrace> getToolTrace(String requestId) {
        return traceStore.find(requestId);
    }

    public List<RequestToolTrace> getRecentToolTraces(int limit) {
        return traceStore.recent(limit);
    }

    private OrchestratorResult runParallel(AgentRequest req, RoutingDecision decision, List<ToolCallTrace> externalToolCalls) {
        Instant start = Instant.now();
        List<AgentType> targets = decision.agentTypes();
        List<AgentType> boundedTargets = targets.stream().limit(3).toList();
        List<CompletableFuture<AgentResponse>> futures = boundedTargets.stream()
                .map(type -> CompletableFuture.supplyAsync(() -> execute(req, type), agentExecutor))
                .toList();
        List<AgentResponse> responses = futures.stream().map(CompletableFuture::join).toList();
        List<String> parts = new ArrayList<>();
        for (AgentResponse response : responses) {
            if (response.success()) {
                String role = response.agentType() == decision.primaryAgent() ? "主处理" : "辅助处理";
                parts.add("[" + response.agentType().wireValue() + " - " + role + "]\n" + response.content());
            }
        }
        String content = parts.isEmpty() ? "抱歉，所有 Agent 均处理失败。" : String.join("\n\n", parts);
        boolean escalate = responses.stream().anyMatch(AgentResponse::escalate);
        List<AgentType> agentTypes = responses.stream()
                .filter(AgentResponse::success)
                .map(AgentResponse::agentType)
                .toList();
        OrchestratorResult result = new OrchestratorResult(
                req.requestId(),
                content,
                decision.primaryAgent(),
                req.intent(),
                escalate,
                Duration.between(start, Instant.now()).toMillis(),
                agentTypes.isEmpty() ? targets : agentTypes,
                decision.primaryAgent(),
                decision.supportingAgents(),
                collectToolNames(externalToolCalls, responses),
                collectToolCalls(externalToolCalls, responses),
                decision.reason(),
                decision.confidence()
        );
        recordTrace(req, result);
        return result;
    }

    private AgentType route(IntentCategory intent, UrgencyLevel urgency) {
        if (urgency == UrgencyLevel.CRITICAL) {
            return AgentType.ESCALATION;
        }
        AgentType target = routing.get(intent);
        if (target != null && pool.containsKey(target)) {
            return target;
        }
        return AgentType.GENERAL;
    }

    private RoutingDecision routeDecision(AgentRequest req) {
        if (req.urgency() == UrgencyLevel.CRITICAL) {
            return new RoutingDecision(AgentType.ESCALATION, List.of(), "紧急度为 CRITICAL，触发升级路由", 1.0);
        }
        if (req.intent() == IntentCategory.ESCALATION || req.intent() == IntentCategory.HUMAN_HANDOFF) {
            String intent = req.intent() == null ? "unknown" : req.intent().wireValue();
            return new RoutingDecision(
                    AgentType.ESCALATION,
                    List.of(),
                    "意图为 " + intent + "，触发升级路由",
                    Math.max(req.intentConfidence(), 0.8)
            );
        }

        Map<AgentType, Double> scores = domainScores(req);
        Map<AgentType, Double> availableScores = new EnumMap<>(AgentType.class);
        scores.forEach((agentType, score) -> {
            if (agentType == AgentType.GENERAL || pool.containsKey(agentType)) {
                availableScores.put(agentType, score);
            }
        });
        if (availableScores.isEmpty()) {
            return new RoutingDecision(AgentType.GENERAL, List.of(), "无可用专属 Agent，降级到 GeneralAgent", 0.1);
        }

        List<Map.Entry<AgentType, Double>> ordered = availableScores.entrySet().stream()
                .sorted(Map.Entry.<AgentType, Double>comparingByValue().reversed())
                .toList();
        AgentType primary = ordered.getFirst().getKey();
        double primaryScore = ordered.getFirst().getValue();
        List<AgentType> supportingAgents = ordered.stream()
                .skip(1)
                .filter(entry -> entry.getKey() != AgentType.GENERAL)
                .filter(entry -> entry.getValue() >= 0.45 && entry.getValue() >= primaryScore * 0.55)
                .map(Map.Entry::getKey)
                .toList();
        return new RoutingDecision(
                primary,
                supportingAgents,
                routingReason(req, availableScores, primary, supportingAgents),
                round(Math.min(primaryScore, 1.0))
        );
    }

    private Map<AgentType, Double> domainScores(AgentRequest req) {
        String msg = req.message() == null ? "" : req.message().toLowerCase(Locale.ROOT);
        Map<AgentType, Double> scores = new EnumMap<>(AgentType.class);
        scores.put(AgentType.GENERAL, 0.1);
        scores.put(AgentType.TECHNICAL, 0.0);
        scores.put(AgentType.BILLING, 0.0);
        scores.put(AgentType.PRODUCT_ADVISOR, 0.0);

        if (List.of(IntentCategory.PRODUCT_COMPARE, IntentCategory.PRODUCT_RECOMMEND,
                IntentCategory.SPEC_INQUIRY, IntentCategory.AVAILABILITY,
                IntentCategory.PRICE_PROMOTION).contains(req.intent())) {
            scores.merge(AgentType.PRODUCT_ADVISOR, 0.75, Double::sum);
        }

        if (List.of(
                IntentCategory.QUERY,
                IntentCategory.ORDER_STATUS,
                IntentCategory.LOGISTICS,
                IntentCategory.REQUEST,
                IntentCategory.COMPLAINT,
                IntentCategory.GREETING,
                IntentCategory.FEEDBACK,
                IntentCategory.OTHER
        ).contains(req.intent())) {
            scores.merge(AgentType.GENERAL, 0.55, Double::sum);
        }

        if (List.of(
                IntentCategory.TECHNICAL,
                IntentCategory.TECHNICAL_LOGIN,
                IntentCategory.TECHNICAL_CRASH
        ).contains(req.intent())) {
            scores.merge(AgentType.TECHNICAL, 0.75, Double::sum);
        }

        if (List.of(
                IntentCategory.BILLING,
                IntentCategory.ACCOUNT,
                IntentCategory.ACCOUNT_SECURITY,
                IntentCategory.REFUND,
                IntentCategory.INVOICE,
                IntentCategory.PAYMENT_ISSUE
        ).contains(req.intent())) {
            scores.merge(AgentType.BILLING, 0.75, Double::sum);
        }

        long technicalHits = countHits(msg, "崩溃", "报错", "error", "crash", "无法登录", "登录失败", "500", "401", "验证码");
        long billingHits = countHits(msg, "退款", "退货", "扣款", "发票", "账单", "支付", "订阅", "refund", "invoice", "多扣");
        long generalHits = countHits(msg, "订单", "物流", "快递", "配送", "会员", "积分", "咨询", "帮助");
        long presalesHits = countHits(msg, "商品", "产品", "推荐", "适合", "送给", "礼物", "预算", "规格", "参数", "现货", "库存", "优惠", "多少钱", "对比");

        scores.merge(AgentType.TECHNICAL, Math.min(0.45, technicalHits * 0.18), Double::sum);
        scores.merge(AgentType.BILLING, Math.min(0.45, billingHits * 0.18), Double::sum);
        scores.merge(AgentType.GENERAL, Math.min(0.35, generalHits * 0.12), Double::sum);
        scores.merge(AgentType.PRODUCT_ADVISOR, Math.min(0.45, presalesHits * 0.18), Double::sum);

        Map<String, List<String>> entities = req.entities() == null ? Map.of() : req.entities();
        if (!entities.getOrDefault("error_code", List.of()).isEmpty()) {
            scores.merge(AgentType.TECHNICAL, 0.2, Double::sum);
        }
        if (!entities.getOrDefault("amount", List.of()).isEmpty()) {
            scores.merge(AgentType.BILLING, 0.15, Double::sum);
        }
        if (!entities.getOrDefault("order_id", List.of()).isEmpty()) {
            scores.merge(AgentType.GENERAL, 0.1, Double::sum);
        }
        if (List.of("product", "budget", "scenario", "recipient", "feature").stream()
                .anyMatch(key -> !entities.getOrDefault(key, List.of()).isEmpty())) {
            scores.merge(AgentType.PRODUCT_ADVISOR, 0.15, Double::sum);
        }

        scores.replaceAll((agentType, score) -> round(score));
        return scores;
    }

    private String routingReason(
            AgentRequest req,
            Map<AgentType, Double> scores,
            AgentType primaryAgent,
            List<AgentType> supportingAgents
    ) {
        String scoreText = scores.entrySet().stream()
                .sorted(Map.Entry.<AgentType, Double>comparingByValue().reversed())
                .map(entry -> entry.getKey().wireValue() + "=" + String.format(Locale.ROOT, "%.2f", entry.getValue()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String supportText = supportingAgents.stream()
                .map(AgentType::wireValue)
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
        String intent = req.intent() == null ? "unknown" : req.intent().wireValue();
        return "intent=" + intent
                + ", group=" + (req.intentGroup() == null ? "unknown" : req.intentGroup())
                + ", primary=" + primaryAgent.wireValue()
                + ", supporting=" + supportText
                + ", scores=[" + scoreText + "]";
    }

    private List<AgentType> collaborationTargets(AgentRequest request) {
        String msg = request.message() == null ? "" : request.message().toLowerCase(Locale.ROOT);
        LinkedHashSet<AgentType> targets = new LinkedHashSet<>();
        if (request.intent() == IntentCategory.TECHNICAL || containsAny(msg, "崩溃", "报错", "error", "crash", "无法登录", "500", "401")) {
            targets.add(AgentType.TECHNICAL);
        }
        if (request.intent() == IntentCategory.BILLING || request.intent() == IntentCategory.ACCOUNT
                || containsAny(msg, "退款", "扣款", "发票", "账单", "支付", "订阅", "refund", "invoice")) {
            targets.add(AgentType.BILLING);
        }
        if (List.of(IntentCategory.PRODUCT_COMPARE, IntentCategory.PRODUCT_RECOMMEND,
                IntentCategory.SPEC_INQUIRY, IntentCategory.AVAILABILITY,
                IntentCategory.PRICE_PROMOTION).contains(request.intent())
                || containsAny(msg, "商品", "产品", "推荐", "适合", "送给", "礼物", "预算", "规格", "现货", "库存", "优惠", "对比")) {
            targets.add(AgentType.PRODUCT_ADVISOR);
        }
        return new ArrayList<>(targets.stream().filter(pool::containsKey).toList());
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private long countHits(String text, String... keywords) {
        long hits = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                hits++;
            }
        }
        return hits;
    }

    private boolean needsClarification(AgentRequest req) {
        if (req.intent() != IntentCategory.OTHER) {
            return false;
        }
        String text = req.message() == null ? "" : req.message().trim();
        if (text.length() <= 2) {
            return false;
        }
        return req.intentConfidence() < 0.5;
    }

    private AgentResponse execute(AgentRequest req, AgentType agentType) {
        if (hookDispatcher != null) {
            var decision = hookDispatcher.dispatch(new HookContext(HookEventType.PRE_AGENT_RUN, req.requestId(), req.userId(),
                    req.conversationId(), agentType.wireValue(), "", Map.of()));
            if (decision.blocked()) {
                return new AgentResponse(agentType, "本次请求未通过运行时安全校验，请转人工客服。", false, 0.0, 0, true,
                        "", false, false, false, decision.decision().message());
            }
        }
        BaseAgent agent = bestAgent(agentType).orElseGet(() -> bestAgent(AgentType.GENERAL).orElse(null));
        if (agent == null) {
            return new AgentResponse(AgentType.GENERAL, "服务暂时不可用，请稍后重试。", false, 0.0, 0, false, "", false, false, false, "service unavailable");
        }
        AgentResponse response = agent.handle(req);
        if (!response.success() && agentType != AgentType.GENERAL) {
            return bestAgent(AgentType.GENERAL).map(a -> a.handle(req)).orElse(response);
        }
        return response;
    }

    private void recordTrace(AgentRequest req, OrchestratorResult result) {
        traceStore.record(new RequestToolTrace(
                result.requestId(),
                Instant.now().toString(),
                "chat",
                req.userId(),
                req.conversationId(),
                result.intent() == null ? null : result.intent().wireValue(),
                req.intentGroup(),
                result.agentType().wireValue(),
                result.primaryAgent() == null ? null : result.primaryAgent().wireValue(),
                result.supportingAgents().stream().map(AgentType::wireValue).toList(),
                result.toolsUsed(),
                result.toolCalls(),
                result.toolsUsed().contains("search_knowledge_base") || result.toolsUsed().contains("knowledge_search"),
                result.escalated(),
                result.latencyMs()
        ));
    }

    private List<ToolCallTrace> collectToolCalls(List<ToolCallTrace> externalToolCalls, AgentResponse... responses) {
        List<ToolCallTrace> calls = new ArrayList<>();
        if (externalToolCalls != null) {
            calls.addAll(externalToolCalls);
        }
        if (responses != null) {
            for (AgentResponse response : responses) {
                if (response == null) {
                    continue;
                }
                if (response.toolName() != null && !response.toolName().isBlank()) {
                    calls.add(toTrace(response, response.toolName()));
                }
            }
        }
        return calls;
    }

    private List<ToolCallTrace> collectToolCalls(List<ToolCallTrace> externalToolCalls, List<AgentResponse> responses) {
        return collectToolCalls(externalToolCalls, responses == null ? null : responses.toArray(new AgentResponse[0]));
    }

    private List<String> collectToolNames(List<ToolCallTrace> externalToolCalls, AgentResponse... responses) {
        return collectToolCalls(externalToolCalls, responses).stream()
                .map(ToolCallTrace::toolName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
    }

    private List<String> collectToolNames(List<ToolCallTrace> externalToolCalls, List<AgentResponse> responses) {
        return collectToolCalls(externalToolCalls, responses).stream()
                .map(ToolCallTrace::toolName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
    }

    private ToolCallTrace toTrace(AgentResponse response, String toolName) {
        return new ToolCallTrace(
                toolName == null ? "" : toolName,
                response.success(),
                !response.success(),
                response.toolCached(),
                response.toolReranked(),
                response.latencyMs(),
                response.toolError() == null ? "" : response.toolError()
        );
    }

    private Optional<BaseAgent> bestAgent(AgentType agentType) {
        return pool.getOrDefault(agentType, List.of()).stream()
                .max(Comparator.comparingDouble(a -> a.stats().routingScore()));
    }

    public Map<String, Object> stats() {
        Map<String, Object> result = new HashMap<>();
        pool.forEach((type, agents) -> {
            for (int i = 0; i < agents.size(); i++) {
                BaseAgent agent = agents.get(i);
                Map<String, Object> data = new HashMap<>();
                data.put("total", agent.stats().total());
                data.put("success_rate", round(agent.stats().successRate()));
                data.put("avg_ms", round(agent.stats().avgLatencyMs()));
                data.put("monitor_penalty", round(agent.stats().monitorPenalty()));
                data.put("routing_score", round(agent.stats().routingScore()));
                result.put(type.wireValue() + "_" + i, data);
            }
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    public void updateRoutingPenalties(Map<String, Double> penalties) {
        pool.forEach((type, agents) -> {
            for (int i = 0; i < agents.size(); i++) {
                agents.get(i).stats().setMonitorPenalty(penalties.getOrDefault(type.wireValue() + "_" + i, 0.0));
            }
        });
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private record RoutingDecision(
            AgentType primaryAgent,
            List<AgentType> supportingAgents,
            String reason,
            double confidence
    ) {
        private List<AgentType> agentTypes() {
            List<AgentType> result = new ArrayList<>();
            result.add(primaryAgent);
            result.addAll(supportingAgents);
            return result;
        }

        private boolean multiAgent() {
            return !supportingAgents.isEmpty();
        }
    }
}
