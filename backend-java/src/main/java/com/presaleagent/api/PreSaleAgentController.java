package com.presaleagent.api;

import com.presaleagent.agent.AgentOrchestrator;
import com.presaleagent.agent.AgentRequest;
import com.presaleagent.agent.AgentType;
import com.presaleagent.agent.AnswerVerifier;
import com.presaleagent.agent.OrchestratorResult;
import com.presaleagent.api.dto.BatchDocInput;
import com.presaleagent.api.dto.ChatRequest;
import com.presaleagent.api.dto.ChatResponse;
import com.presaleagent.api.dto.EvalRunRequest;
import com.presaleagent.evaluation.EndToEndEvaluator;
import com.presaleagent.intent.IntentCategory;
import com.presaleagent.intent.IntentRecognizer;
import com.presaleagent.intent.IntentResult;
import com.presaleagent.knowledge.KnowledgeBaseService;
import com.presaleagent.knowledge.SearchResult;
import com.presaleagent.catalog.ProductCatalogService;
import com.presaleagent.memory.MemoryContext;
import com.presaleagent.memory.MemoryManager;
import com.presaleagent.memory.MessageRole;
import com.presaleagent.monitor.PerformanceMonitor;
import com.presaleagent.skill.SkillManager;
import com.presaleagent.tool.KnowledgeToolManager;
import com.presaleagent.tool.ToolResult;
import com.presaleagent.trace.ToolCallTrace;
import com.presaleagent.context.ContextAssembler;
import com.presaleagent.context.ConversationContext;
import com.presaleagent.hook.HookContext;
import com.presaleagent.hook.HookDispatcher;
import com.presaleagent.hook.HookEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@Tag(name = "PreSaleAgent API", description = "售前导购对话、知识库、监控和评测接口")
public class PreSaleAgentController {

    private final AgentOrchestrator orchestrator;
    private final IntentRecognizer intentRecognizer;
    private final MemoryManager memoryManager;
    private final KnowledgeToolManager knowledgeToolManager;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AnswerVerifier answerVerifier;
    private final PerformanceMonitor performanceMonitor;
    private final EndToEndEvaluator evaluator;
    private final SkillManager skillManager;
    private final ObjectMapper objectMapper;
    private final PrometheusMeterRegistry prometheusMeterRegistry;
    private final ProductCatalogService productCatalogService;
    private final ContextAssembler contextAssembler;
    private final HookDispatcher hookDispatcher;

    public PreSaleAgentController(
            AgentOrchestrator orchestrator,
            IntentRecognizer intentRecognizer,
            MemoryManager memoryManager,
            KnowledgeToolManager knowledgeToolManager,
            KnowledgeBaseService knowledgeBaseService,
            AnswerVerifier answerVerifier,
            PerformanceMonitor performanceMonitor,
            EndToEndEvaluator evaluator,
            SkillManager skillManager,
            ObjectMapper objectMapper,
            PrometheusMeterRegistry prometheusMeterRegistry
            , ProductCatalogService productCatalogService, ContextAssembler contextAssembler,
            HookDispatcher hookDispatcher
    ) {
        this.orchestrator = orchestrator;
        this.intentRecognizer = intentRecognizer;
        this.memoryManager = memoryManager;
        this.knowledgeToolManager = knowledgeToolManager;
        this.knowledgeBaseService = knowledgeBaseService;
        this.answerVerifier = answerVerifier;
        this.performanceMonitor = performanceMonitor;
        this.evaluator = evaluator;
        this.skillManager = skillManager;
        this.objectMapper = objectMapper;
        this.prometheusMeterRegistry = prometheusMeterRegistry;
        this.productCatalogService = productCatalogService;
        this.contextAssembler = contextAssembler;
        this.hookDispatcher = hookDispatcher;
    }

    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "返回服务状态和 Agent 路由统计。")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "agents", orchestrator.stats());
    }

    /**
     * 售前智能客服对话主入口，串联一次请求的完整处理链路：
     * <ol>
     *   <li>解析用户 / 会话 / 请求 ID，并触发 SESSION_START Hook；</li>
     *   <li>读取会话记忆，截取最近若干轮历史作为上下文；</li>
     *   <li>意图识别，据此决定是否走知识库检索、是否为售前商品咨询；</li>
     *   <li>知识库检索与（售前场景下的）商品目录检索，分别记录工具调用轨迹；</li>
     *   <li>组装完整上下文，交由多 Agent 编排器生成回答；</li>
     *   <li>回答校验、判断是否需升级人工，写回记忆并构造响应。</li>
     * </ol>
     *
     * @param request 对话请求体（含用户消息、可选 userId / conversationId）
     * @return 包含回答、命中的意图 / Agent、工具使用与校验结果等信息的响应
     */
    @PostMapping("/chat")
    @Operation(summary = "智能客服对话", description = "执行完整对话链路：记忆读取、知识库检索、意图识别、多 Agent 路由、回答校验和记忆写入。")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        // ===== 1. 身份与会话标识解析 =====
        String userId = request.userIdOrDefault();
        // conversationId 缺省时新建一个会话；requestId 取短 UUID 用于全链路追踪
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? UUID.randomUUID().toString()
                : request.conversationId();
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        // 会话开始事件：给 Hook 一个初始化 / 审计的切入点，返回值此处不消费
        hookDispatcher.dispatch(new HookContext(HookEventType.SESSION_START, requestId, userId, conversationId, "", "", Map.of()));

        // ===== 2. 记忆读取与历史窗口 =====
        MemoryContext memoryContext = memoryManager.getContext(userId, conversationId, request.message());
        String memoryText = memoryContext.toPromptText(objectMapper);
        // 只取最近 5 轮消息作为对话历史，并转换成 {role, content} 结构供下游使用
        List<Map<String, String>> history = memoryContext.recentMessages().stream()
                .skip(Math.max(0, memoryContext.recentMessages().size() - 5))
                .map(m -> Map.of("role", m.role().name().toLowerCase(), "content", m.content()))
                .toList();

        // ===== 3. 意图识别 =====
        IntentResult intentResult = intentRecognizer.recognize(request.message(), history);
        // 依据意图类别判断是否需要检索知识库
        boolean useKnowledge = shouldUseKnowledge(intentResult.intent());

        // ===== 4. 知识库检索（带查询改写）并记录工具轨迹 =====
        ToolCallTrace knowledgeTrace = null;
        // 不需要检索时用空结果占位，保持后续处理统一
        ToolResult<List<SearchResult>> knowledge = useKnowledge
                ? knowledgeToolManager.searchWithRewrite(request.message(), 3)
                : new ToolResult<>(true, List.of(), "knowledge_search", null, false, 0, false);
        if (useKnowledge) {
            knowledgeTrace = new ToolCallTrace(
                    knowledge.toolName() == null || knowledge.toolName().isBlank() ? "knowledge_search" : knowledge.toolName(),
                    knowledge.success(),
                    !knowledge.success() || knowledge.error() != null,
                    knowledge.cached(),
                    knowledge.reranked(),
                    knowledge.latencyMs(),
                    knowledge.error() == null ? "" : knowledge.error()
            );
        }
        String knowledgeText = buildKnowledgeContext(knowledge.data());

        // ===== 5. 售前商品目录检索（仅售前意图，先过 PRE_TOOL_CALL Hook）=====
        String catalogText = "";
        ToolCallTrace catalogTrace = null;
        boolean catalogUsed = false;
        if (isPresales(intentResult.intent())) {
            // 工具调用前置 Hook：可拦截（如安全 / 权限校验），blocked 时不真正访问目录服务
            HookDispatcher.DispatchResult hook = hookDispatcher.dispatch(new HookContext(
                    HookEventType.PRE_TOOL_CALL, requestId, userId, conversationId, "product_advisor",
                    "search_product_catalog", Map.of("query", request.message(), "limit", 5)));
            Map<String, Object> catalog = hook.blocked()
                    ? Map.of("success", false, "available", false, "status", "BLOCKED", "results", List.of(), "error", hook.decision().message())
                    : productCatalogService.search(request.message(), "", 5);
            catalogText = buildCatalogContext(catalog);
            // 只有检索成功且返回非空结果才算“真正用到了目录数据”
            catalogUsed = Boolean.TRUE.equals(catalog.get("success"))
                    && catalog.get("results") instanceof List<?> items && !items.isEmpty();
            String catalogStatus = String.valueOf(catalog.getOrDefault("status", "UNKNOWN"));
            // 目录轨迹额外标注超时 / 拦截状态，便于监控与排查
            catalogTrace = new ToolCallTrace("search_product_catalog",
                    Boolean.TRUE.equals(catalog.get("success")),
                    !Boolean.TRUE.equals(catalog.get("success")), false, false, 0,
                    String.valueOf(catalog.getOrDefault("error", "")),
                    "TIMEOUT".equals(catalogStatus), "BLOCKED".equals(catalogStatus), catalogStatus);
        }

        // ===== 6. 组装完整上下文并执行多 Agent 编排 =====
        ConversationContext assembled = contextAssembler.assemble(request.message(), memoryContext, catalogText, knowledgeText);
        String fullContext = assembled.prompt();
        OrchestratorResult result = orchestrator.run(
                AgentRequest.of(request.message(), userId, conversationId, fullContext, history, intentResult, requestId),
                mergeTraces(knowledgeTrace, catalogTrace)
        );

        // ===== 7. 回答校验与人工升级判断 =====
        AnswerVerifier.VerificationResult verification = answerVerifier.verify(request.message(), result.response(), fullContext);
        // 编排器主动升级 或 校验判定需要升级，任一成立即视为升级
        boolean escalated = result.escalated() || verification.needEscalation();

        // ===== 8. 写回记忆（用户消息 + 助手回答）并更新用户画像 =====
        memoryManager.addMessage(userId, conversationId, MessageRole.USER, request.message());
        memoryManager.addMessage(userId, conversationId, MessageRole.ASSISTANT, result.response());
        memoryManager.updateProfile(userId, conversationId);

        // ===== 9. 构造响应：回填意图 / Agent 路由 / 工具使用 / 校验 / 置信度等信息 =====
        return new ChatResponse(
                conversationId,
                result.requestId(),
                result.response(),
                result.intent() == null ? IntentCategory.OTHER.wireValue() : result.intent().wireValue(),
                intentResult.intentGroup(),
                result.agentType().wireValue(),
                agentNames(result.agentTypes()),
                result.primaryAgent() == null ? null : result.primaryAgent().wireValue(),
                agentNames(result.supportingAgents()),
                result.routingReason(),
                result.routingConfidence(),
                escalated,
                result.latencyMs(),
                (knowledge.success() && knowledge.data() != null && !knowledge.data().isEmpty()) || catalogUsed,
                verification.pass(),
                verification.grounded(),
                intentResult.entities(),
                round(intentResult.confidence(), 4),
                intentResult.sourceScores()
        );
    }

    @PostMapping("/search")
    @Operation(summary = "知识库检索", description = "对用户查询做查询改写、并行召回和 LLM rerank。")
    public Map<String, Object> search(
            @Parameter(description = "检索关键词或用户问题", example = "退款多久能到账") @RequestParam String query,
            @Parameter(description = "返回结果数量", example = "5") @RequestParam(defaultValue = "5") int topK) {
        ToolResult<List<SearchResult>> result = knowledgeToolManager.searchWithRewrite(query, topK);
        return Map.of("query", query, "results", result.data(), "reranked", result.reranked());
    }

    @PostMapping("/knowledge/add")
    @Operation(summary = "批量添加知识文档", description = "将文档切片后写入 Java 版持久化知识库。")
    public Map<String, Object> addKnowledge(@Valid @RequestBody BatchDocInput input) {
        List<Map<String, String>> docs = input.documents().stream()
                .map(d -> Map.of("title", d.title(), "content", d.content()))
                .toList();
        int added = knowledgeBaseService.addDocuments(docs);
        return Map.of("added_chunks", added, "total_chunks", knowledgeBaseService.docCount());
    }

    @PostMapping(value = "/knowledge/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传知识库文件", description = "支持上传 .txt、.md、.json 文件，最大 10MB。JSON 文件格式为数组：[{\"title\":\"...\",\"content\":\"...\"}]。")
    public Map<String, Object> uploadKnowledge(@Parameter(description = "知识库文件") @RequestParam("file") MultipartFile file) throws Exception {
        if (file.getSize() > 10L * 1024 * 1024) {
            throw new IllegalArgumentException("文件大小超过 10MB 限制");
        }
        String filename = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String text = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        List<Map<String, String>> docs;
        if (filename.endsWith(".json")) {
            docs = parseJsonDocs(text);
        } else {
            String title = filename.contains(".") ? filename.substring(0, filename.lastIndexOf('.')) : filename;
            docs = List.of(Map.of("title", title, "content", text));
        }
        int added = knowledgeBaseService.addDocuments(docs);
        return Map.of("message", "文件 " + filename + " 导入成功", "added_chunks", added, "total_chunks", knowledgeBaseService.docCount());
    }

    @GetMapping("/knowledge/stats")
    @Operation(summary = "知识库统计", description = "返回当前知识库片段数量。")
    public Map<String, Object> knowledgeStats() {
        return Map.of("total_chunks", knowledgeBaseService.docCount());
    }

    @GetMapping("/monitor")
    @Operation(summary = "监控摘要", description = "返回 Agent 指标、工具统计、告警和优化建议。")
    public Map<String, Object> monitor() {
        return performanceMonitor.summary();
    }

    @GetMapping("/trace/tool/{requestId}")
    @Operation(summary = "查看单次请求的工具轨迹", description = "返回指定 requestId 对应的工具调用详情。")
    public Map<String, Object> toolTrace(@Parameter(description = "请求 ID") @org.springframework.web.bind.annotation.PathVariable String requestId) {
        return orchestrator.getToolTrace(requestId)
                .<Map<String, Object>>map(trace -> Map.of("found", true, "trace", trace))
                .orElseGet(() -> Map.of("found", false, "trace", Map.of()));
    }

    @GetMapping("/trace/tools")
    @Operation(summary = "查看最近工具轨迹", description = "返回最近 N 次请求的工具调用详情。")
    public Map<String, Object> recentToolTraces(@RequestParam(defaultValue = "20") int limit) {
        return Map.of("items", orchestrator.getRecentToolTraces(limit));
    }

    @GetMapping("/skills")
    @Operation(summary = "Skills 摘要", description = "查看当前已加载的动态 Skills，便于确认热加载结果和排查解析错误。")
    public Map<String, Object> skills() {
        return skillManager.summary();
    }

    @PostMapping("/skills/reload")
    @Operation(summary = "重新加载 Skills", description = "运行时重新扫描 Skill 目录，不需要重启服务。")
    public Map<String, Object> reloadSkills() {
        skillManager.reload();
        return skillManager.summary();
    }

    @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
    @Operation(summary = "Prometheus 指标", description = "返回 Prometheus 文本格式指标，兼容 Python 版 /metrics 路径。")
    public ResponseEntity<String> metrics() {
        return ResponseEntity.ok(prometheusMeterRegistry.scrape());
    }

    @PostMapping(value = "/eval/run", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "运行评测", description = "运行意图识别和对话质量评测；请求体为空时使用内置默认用例。")
    public Map<String, Object> eval(@RequestBody(required = false) EvalRunRequest request) {
        return evaluator.run(request);
    }

    private boolean shouldUseKnowledge(IntentCategory intent) {
        if (intent == null) {
            return false;
        }
        return switch (intent) {
            case QUERY,
                    COMPLAINT,
                    REQUEST,
                    TECHNICAL,
                    BILLING,
                    ACCOUNT,
                    ORDER_STATUS,
                    LOGISTICS,
                    REFUND,
                    INVOICE,
                    PAYMENT_ISSUE,
                    ACCOUNT_SECURITY,
                    TECHNICAL_LOGIN,
                    TECHNICAL_CRASH -> true;
            case PRODUCT_COMPARE, PRODUCT_RECOMMEND, SPEC_INQUIRY, AVAILABILITY, PRICE_PROMOTION -> true;
            case GREETING, ESCALATION, HUMAN_HANDOFF, FEEDBACK, OTHER -> false;
        };
    }

    private boolean isPresales(IntentCategory intent) {
        return intent == IntentCategory.PRODUCT_COMPARE || intent == IntentCategory.PRODUCT_RECOMMEND
                || intent == IntentCategory.SPEC_INQUIRY || intent == IntentCategory.AVAILABILITY
                || intent == IntentCategory.PRICE_PROMOTION;
    }

    private List<ToolCallTrace> mergeTraces(ToolCallTrace first, ToolCallTrace second) {
        List<ToolCallTrace> traces = new ArrayList<>();
        if (first != null) traces.add(first);
        if (second != null) traces.add(second);
        return traces;
    }

    private String buildCatalogContext(Map<String, Object> result) {
        if (result == null || result.isEmpty()) return "";
        return "[商品目录检索结果]\n" + objectMapper.valueToTree(result).toString()
                + "\n商品目录不可用或结果为空时，不得猜测商品事实。";
    }

    private String buildKnowledgeContext(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        parts.add("[知识库检索结果]");
        for (int i = 0; i < results.size(); i++) {
            SearchResult item = results.get(i);
            parts.add((i + 1) + ". 标题: " + item.title() + "\n   相关度: " + item.score() + "\n   内容: " + item.content());
        }
        parts.add("请优先依据以上知识库内容回答；如果知识库内容不足，再结合通用客服能力说明。");
        return String.join("\n", parts);
    }

    private String join(String left, String right) {
        if (left == null || left.isBlank()) {
            return right == null ? "" : right;
        }
        if (right == null || right.isBlank()) {
            return left;
        }
        return left + "\n\n" + right;
    }

    private List<String> agentNames(List<AgentType> agentTypes) {
        if (agentTypes == null) {
            return List.of();
        }
        return agentTypes.stream()
                .map(AgentType::wireValue)
                .toList();
    }

    private double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseJsonDocs(String text) throws Exception {
        List<?> values = objectMapper.readValue(text, List.class);
        List<Map<String, String>> docs = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                Map<String, String> doc = new LinkedHashMap<>();
                Object title = map.containsKey("title") ? map.get("title") : "未命名文档";
                Object content = map.containsKey("content") ? map.get("content") : "";
                doc.put("title", String.valueOf(title));
                doc.put("content", String.valueOf(content));
                docs.add(doc);
            }
        }
        return docs;
    }
}
