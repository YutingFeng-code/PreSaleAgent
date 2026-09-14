package com.presaleagent.intent;

import com.presaleagent.llm.LlmGateway;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图识别器：将 LLM 语义判断、字符 n-gram 相似度和关键词规则组合起来，
 * 输出统一的意图、意图分组、置信度、紧急度和结构化实体。
 */
@Service
public class IntentRecognizer {

    // 最终融合分数低于该阈值时返回 OTHER，由上层触发澄清，而不是贸然路由。
    private static final double CONFIDENCE_THRESHOLD = 0.5;

    // Few-shot 示例同时用于 LLM Prompt 和本地 n-gram 相似度匹配。
    private static final Map<IntentCategory, List<String>> TEMPLATES = Map.ofEntries(
            Map.entry(IntentCategory.QUERY, List.of("我的订单状态是什么？", "如何重置密码？", "快递什么时候到？")),
            Map.entry(IntentCategory.COMPLAINT, List.of("等了好几个小时！", "服务太差了！", "一直没人处理！")),
            Map.entry(IntentCategory.REQUEST, List.of("帮我取消订单", "我需要修改地址", "请协助退款")),
            Map.entry(IntentCategory.GREETING, List.of("你好", "嗨，有人吗", "早上好")),
            Map.entry(IntentCategory.ESCALATION, List.of("我要投诉！", "转人工客服", "找你们经理")),
            Map.entry(IntentCategory.TECHNICAL, List.of("应用一直崩溃", "无法登录", "出现500错误")),
            Map.entry(IntentCategory.BILLING, List.of("为什么扣了两次款？", "申请退款", "发票问题")),
            Map.entry(IntentCategory.ACCOUNT, List.of("修改邮箱", "注销账户", "更新个人信息")),
            Map.entry(IntentCategory.FEEDBACK, List.of("服务很棒！", "非常满意", "给个好评")),
            Map.entry(IntentCategory.ORDER_STATUS, List.of("我的订单现在是什么状态？", "订单有没有发货？", "订单处理到哪一步了？")),
            Map.entry(IntentCategory.LOGISTICS, List.of("快递什么时候到？", "物流一直不更新", "配送要多久？")),
            Map.entry(IntentCategory.REFUND, List.of("我要申请退款", "退货退款怎么处理？", "退款多久到账？")),
            Map.entry(IntentCategory.INVOICE, List.of("帮我开发票", "发票抬头怎么改？", "电子发票在哪里？")),
            Map.entry(IntentCategory.PAYMENT_ISSUE, List.of("为什么重复扣款？", "支付失败怎么办？", "这个月多扣了钱")),
            Map.entry(IntentCategory.ACCOUNT_SECURITY, List.of("账户被盗了", "发现异常登录", "我要重置密码")),
            Map.entry(IntentCategory.TECHNICAL_LOGIN, List.of("登录一直报401", "验证码收不到", "无法登录账号")),
            Map.entry(IntentCategory.TECHNICAL_CRASH, List.of("应用一直崩溃", "页面报500错误", "系统闪退")),
            Map.entry(IntentCategory.HUMAN_HANDOFF, List.of("转人工客服", "我要找人工", "请升级处理"))
            ,Map.entry(IntentCategory.PRODUCT_COMPARE, List.of("A 和 B 哪个好？", "比较这两款商品", "两款产品有什么区别"))
            ,Map.entry(IntentCategory.PRODUCT_RECOMMEND, List.of("给女朋友送什么礼物？", "预算 500 怎么选", "推荐一款适合通勤的产品"))
            ,Map.entry(IntentCategory.SPEC_INQUIRY, List.of("这款支持 NFC 吗？", "续航时间是多少", "商品的尺寸和重量是多少"))
            ,Map.entry(IntentCategory.AVAILABILITY, List.of("这款有现货吗？", "什么时候发货", "几天能送到"))
            ,Map.entry(IntentCategory.PRICE_PROMOTION, List.of("现在多少钱？", "最近有什么优惠", "可以使用优惠券吗"))
    );

    // 细粒度意图优先级更高。例如“我要退款”应识别为 REFUND，而不是 BILLING。
    private static final Set<IntentCategory> SPECIFIC_INTENTS = Set.of(
            IntentCategory.ORDER_STATUS,
            IntentCategory.LOGISTICS,
            IntentCategory.REFUND,
            IntentCategory.INVOICE,
            IntentCategory.PAYMENT_ISSUE,
            IntentCategory.ACCOUNT_SECURITY,
            IntentCategory.TECHNICAL_LOGIN,
            IntentCategory.TECHNICAL_CRASH,
            IntentCategory.HUMAN_HANDOFF,
            IntentCategory.PRODUCT_COMPARE,
            IntentCategory.PRODUCT_RECOMMEND,
            IntentCategory.SPEC_INQUIRY,
            IntentCategory.AVAILABILITY,
            IntentCategory.PRICE_PROMOTION
    );

    // 宽泛意图只用于无法进一步判断业务类型的情况。
    private static final Set<IntentCategory> GENERIC_INTENTS = Set.of(
            IntentCategory.QUERY,
            IntentCategory.BILLING,
            IntentCategory.TECHNICAL,
            IntentCategory.ACCOUNT,
            IntentCategory.ESCALATION
    );

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private final Map<String, IntentResult> cache = new ConcurrentHashMap<>();

    public IntentRecognizer(LlmGateway llmGateway, ObjectMapper objectMapper) {
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
    }

    public IntentResult recognize(String message, List<Map<String, String>> history) {
        // 历史消息参与缓存 key，避免相同问题在不同会话上下文中复用错误结果。
        String key = cacheKey(message, history);
        IntentResult cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Instant start = Instant.now();
        // 三路识别：LLM 负责语义理解，本地相似度负责泛化，Pattern 负责高精度兜底。
        Map<String, Object> llm = llmRecognize(message, history);
        Map<String, Object> semantic = semanticRecognize(message);
        Map<String, Object> pattern = patternRecognize(message);
        // 按权重合并三路结果，并应用“细粒度意图优先”规则。
        VoteResult vote = vote(llm, semantic, pattern);
        IntentCategory intent = vote.intent();
        UrgencyLevel urgency = urgency(message, intent);
        // 将识别结果统一封装，供 AgentOrchestrator、RAG 和监控链路共同使用。
        IntentResult result = new IntentResult(
                intent,
                vote.confidence(),
                urgency,
                intentGroup(intent),
                extractEntities(message),
                String.valueOf(llm.getOrDefault("reasoning", "")),
                Duration.between(start, Instant.now()).toMillis(),
                vote.sourceScores()
        );
        // 当前使用有界的简单缓存，避免识别缓存无限增长。
        if (cache.size() > 1000) {
            cache.clear();
        }
        cache.put(key, result);
        return result;
    }

    private Map<String, Object> llmRecognize(String message, List<Map<String, String>> history) {
        // 将每类意图的一个示例拼入 Prompt，控制 Prompt 长度并保持标签覆盖面。
        StringBuilder examples = new StringBuilder();
        TEMPLATES.forEach((intent, samples) ->
                examples.append("消息: \"").append(samples.getFirst()).append("\" -> 意图: ")
                        .append(intent.wireValue()).append('\n'));
        String prompt = """
                你是 PreSaleAgent 的售前导购意图分析专家。根据示例判断用户意图，只返回 JSON。
                当前默认业务域是售前导购，请优先识别商品对比、商品推荐、规格参数、库存/发货时效和价格优惠五类细粒度意图。
                售前问题不要退化为宽泛的 query、request 或 other；只有无法判断具体售前诉求时才使用宽泛意图。

                售前优先示例：
                - “A 和 B 哪个更适合通勤？” -> product_compare
                - “给女朋友选一份 500 元左右的礼物” -> product_recommend
                - “这款支持 NFC、续航多久？” -> spec_inquiry
                - “这款现在有现货吗？几天能发货？” -> availability
                - “现在多少钱？可以用优惠券吗？” -> price_promotion

                严格区分售前与售后：
                - “这款什么时候发货”是 availability；“我的订单什么时候发货”是 logistics 或 order_status。
                - 退款、发票、支付、登录和账号安全问题必须保留为对应售后意图，不要路由到 product_advisor。

                示例:
                %s
                最近对话:
                %s
                用户消息: "%s"
                返回格式: {"intent":"product_recommend","confidence":0.9,"reasoning":"一句话说明"}
                可选意图: %s
                """.formatted(examples, history == null ? "" : history, message, allIntentNames());
        try {
            // 意图分类使用低温度，减少同一问题在多次调用中的随机漂移。
            String raw = llmGateway.chat("", prompt, 0.1, 256);
            String json = sliceJsonObject(raw);
            Map<String, Object> data = objectMapper.readValue(json, new TypeReference<>() {
            });
            data.put("intent", parseIntent(String.valueOf(data.get("intent"))));
            return data;
        } catch (Exception ex) {
            // LLM 不可用时返回失败标记，由 vote() 退化到本地识别结果。
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("intent", IntentCategory.OTHER);
            fallback.put("confidence", 0.0);
            fallback.put("reasoning", "LLM recognition failed");
            fallback.put("failed", true);
            return fallback;
        }
    }

    private Map<String, Object> semanticRecognize(String message) {
        // 使用字符 1~3 gram 的 Jaccard 相似度，适合处理中英文混合的短咨询。
        IntentCategory best = IntentCategory.OTHER;
        double bestScore = 0.0;
        for (Map.Entry<IntentCategory, List<String>> entry : TEMPLATES.entrySet()) {
            for (String sample : entry.getValue()) {
                double score = jaccard(charNgrams(message), charNgrams(sample));
                if (score > bestScore) {
                    bestScore = score;
                    best = entry.getKey();
                }
            }
        }
        return Map.of("intent", best, "confidence", bestScore);
    }

    private Map<String, Object> patternRecognize(String message) {
        // 先匹配细粒度业务规则，再匹配 QUERY/BILLING 等宽泛规则，避免售前问题被泛化。
        String msg = normalize(message);
        Map<IntentCategory, List<String>> specificPatterns = Map.ofEntries(
                Map.entry(IntentCategory.HUMAN_HANDOFF, List.of("转人工", "人工客服", "找人工")),
                Map.entry(IntentCategory.ORDER_STATUS, List.of("订单状态", "发货了吗", "处理到哪", "order status")),
                Map.entry(IntentCategory.LOGISTICS, List.of("物流", "快递", "配送", "运单", "delivery", "shipping")),
                Map.entry(IntentCategory.REFUND, List.of("退款", "退货", "refund", "return")),
                Map.entry(IntentCategory.INVOICE, List.of("发票", "抬头", "税号", "invoice")),
                Map.entry(IntentCategory.PAYMENT_ISSUE, List.of("重复扣款", "多扣", "支付失败", "扣费", "payment failed")),
                Map.entry(IntentCategory.ACCOUNT_SECURITY, List.of("被盗", "异常登录", "重置密码", "两步验证", "安全")),
                Map.entry(IntentCategory.TECHNICAL_LOGIN, List.of("无法登录", "登录失败", "401", "验证码")),
                Map.entry(IntentCategory.TECHNICAL_CRASH, List.of("崩溃", "闪退", "500", "报错", "crash"))
                ,Map.entry(IntentCategory.PRODUCT_COMPARE, List.of("哪个更好", "哪个好", "对比", "区别", "比较", "compare"))
                ,Map.entry(IntentCategory.PRODUCT_RECOMMEND, List.of("推荐", "适合", "送给", "礼物", "预算", "recommend"))
                ,Map.entry(IntentCategory.SPEC_INQUIRY, List.of("规格", "参数", "支持", "功能", "续航", "尺寸", "重量", "spec"))
                ,Map.entry(IntentCategory.AVAILABILITY, List.of("现货", "库存", "有货", "发货", "几天到", "到货", "availability", "shipping"))
                ,Map.entry(IntentCategory.PRICE_PROMOTION, List.of("价格", "多少钱", "优惠", "促销", "优惠券", "折扣", "price", "promotion"))
        );
        Map<String, Object> specific = bestPatternMatch(msg, specificPatterns);
        if (specific.get("intent") != IntentCategory.OTHER) {
            return specific;
        }

        Map<IntentCategory, List<String>> genericPatterns = Map.of(
                IntentCategory.ESCALATION, List.of("投诉", "经理", "supervisor"),
                IntentCategory.COMPLAINT, List.of("太差", "糟糕", "horrible", "等了很久"),
                IntentCategory.QUERY, List.of("?", "？", "怎么", "什么", "status"),
                IntentCategory.REQUEST, List.of("帮我", "需要", "please", "help"),
                IntentCategory.GREETING, List.of("你好", "嗨", "hello", "hi"),
                IntentCategory.BILLING, List.of("退款", "扣款", "发票", "refund"),
                IntentCategory.TECHNICAL, List.of("崩溃", "报错", "error", "crash"),
                IntentCategory.ACCOUNT, List.of("密码", "邮箱", "账户", "password")
        );
        return bestPatternMatch(msg, genericPatterns);
    }

    private Map<String, Object> bestPatternMatch(String msg, Map<IntentCategory, List<String>> patterns) {
        // 命中关键词越多，规则置信度越高；上限为 1.0，避免规则分数无限累加。
        IntentCategory best = IntentCategory.OTHER;
        double bestScore = 0.0;
        for (Map.Entry<IntentCategory, List<String>> entry : patterns.entrySet()) {
            long hits = entry.getValue().stream().filter(msg::contains).count();
            if (hits > 0) {
                double score = Math.min(1.0, 0.5 + 0.25 * (hits - 1));
                if (score > bestScore) {
                    bestScore = score;
                    best = entry.getKey();
                }
            }
        }
        return Map.of("intent", best, "confidence", bestScore);
    }

    private VoteResult vote(Map<String, Object> llm, Map<String, Object> semantic, Map<String, Object> pattern) {
        // 记录各来源分数，便于在 API、Trace 和评测中解释最终判断。
        Map<String, Double> sourceScores = new LinkedHashMap<>();
        sourceScores.put("llm", confidence(llm));
        sourceScores.put("embedding", confidence(semantic));
        sourceScores.put("pattern", confidence(pattern));

        if (Boolean.TRUE.equals(llm.get("failed"))) {
            // LLM 失败时优先使用本地语义匹配，其次使用关键词规则。
            if (semantic.get("intent") != IntentCategory.OTHER && confidence(semantic) > 0) {
                return new VoteResult((IntentCategory) semantic.get("intent"), confidence(semantic), sourceScores);
            }
            if (pattern.get("intent") != IntentCategory.OTHER && confidence(pattern) > 0) {
                return new VoteResult((IntentCategory) pattern.get("intent"), confidence(pattern), sourceScores);
            }
            return new VoteResult(IntentCategory.OTHER, 0.0, sourceScores);
        }
        Map<IntentCategory, Double> scores = new EnumMap<>(IntentCategory.class);
        // LLM 权重最高；Embedding/相似度补充泛化能力；Pattern 负责明确关键词兜底。
        addScore(scores, llm, 0.70);
        addScore(scores, semantic, 0.20);
        addScore(scores, pattern, 0.10);
        Map.Entry<IntentCategory, Double> best = scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElse(Map.entry(IntentCategory.OTHER, 0.0));
        IntentCategory patternIntent = (IntentCategory) pattern.getOrDefault("intent", IntentCategory.OTHER);
        double patternConfidence = confidence(pattern);
        // 如果宽泛模型判断与高置信度细粒度规则冲突，以细粒度规则纠正结果。
        if (GENERIC_INTENTS.contains(best.getKey())
                && SPECIFIC_INTENTS.contains(patternIntent)
                && patternConfidence >= 0.5
                && best.getValue() < 0.8) {
            sourceScores.put("refined_by_pattern", patternConfidence);
            return new VoteResult(patternIntent, Math.max(best.getValue(), patternConfidence), sourceScores);
        }
        // 低于阈值不强行猜测，交给上层生成售前澄清问题。
        if (best.getValue() < CONFIDENCE_THRESHOLD) {
            return new VoteResult(IntentCategory.OTHER, best.getValue(), sourceScores);
        }
        return new VoteResult(best.getKey(), best.getValue(), sourceScores);
    }

    private void addScore(Map<IntentCategory, Double> scores, Map<String, Object> result, double weight) {
        // 将单路“置信度 × 来源权重”累加到对应意图上。
        IntentCategory intent = (IntentCategory) result.getOrDefault("intent", IntentCategory.OTHER);
        double confidence = confidence(result);
        scores.merge(intent, weight * confidence, Double::sum);
    }

    private double confidence(Map<String, Object> result) {
        return ((Number) result.getOrDefault("confidence", 0.0)).doubleValue();
    }

    private Map<String, List<String>> extractEntities(String message) {
        // 实体用于 Agent 路由和商品检索，不让 LLM 只返回一个无法解释的分类标签。
        Map<String, List<String>> entities = new LinkedHashMap<>();
        entities.put("order_id", regexFindGroup(message, "(?:订单号?|order(?:_id)?|#)\\s*[:：#]?\\s*([A-Za-z0-9_-]{4,32})", 1));
        entities.put("product", regexFindGroup(message, "(?:商品|产品|型号|model)\\s*[:：]?\\s*([\\w一-龥][\\w一-龥 -]{1,40})", 1));
        entities.put("budget", regexFindGroup(message, "(?:预算|budget)\\s*(?:约|为|是|在)?\\s*(\\d+(?:\\.\\d{1,2})?)", 1));
        entities.put("scenario", regexFindGroup(message, "(?:适合|用于|用来|场景)\\s*([一-龥A-Za-z0-9][一-龥A-Za-z0-9 -]{1,30})", 1));
        entities.put("recipient", regexFindGroup(message, "(?:送给|给)\\s*([一-龥A-Za-z]{1,12})", 1));
        entities.put("feature", regexFindGroup(message, "(?:支持|需要|关注|功能)\\s*([一-龥A-Za-z0-9][一-龥A-Za-z0-9 -]{1,24})", 1));
        entities.put("date", regexFind(message, "(今天|明天|昨天|本周|这周|下周|\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}日?)"));
        entities.put("error_code", regexFind(message, "\\b[45]\\d{2}\\b"));
        entities.put("amount", regexFind(message, "((?:¥|￥)\\s*\\d+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?\\s*(?:元|块|rmb|cny|usd|美元))"));
        entities.put("error_code", uniqueConcat(
                entities.get("error_code"),
                regexFind(message, "\\b[A-Z][A-Z0-9_-]{2,16}\\b")
        ));
        return entities;
    }

    private List<String> regexFind(String text, String regex) {
        // 无捕获组时返回完整匹配，例如金额、日期和错误码。
        Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text == null ? "" : text);
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return unique(values);
    }

    private List<String> regexFindGroup(String text, String regex, int group) {
        // 有捕获组时只返回目标字段，例如商品名、预算和收礼对象。
        Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text == null ? "" : text);
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group(group));
        }
        return unique(values);
    }

    private List<String> uniqueConcat(List<String> left, List<String> right) {
        List<String> values = new ArrayList<>();
        if (left != null) {
            values.addAll(left);
        }
        if (right != null) {
            values.addAll(right);
        }
        return unique(values);
    }

    private List<String> unique(List<String> values) {
        // 清理空值、首尾空格并去重，保持实体输出稳定。
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private UrgencyLevel urgency(String message, IntentCategory intent) {
        // 紧急度主要服务于升级路由，不改变商品意图本身。
        String msg = normalize(message);
        if (msg.contains("紧急") || msg.contains("urgent") || msg.contains("立刻")) {
            return UrgencyLevel.CRITICAL;
        }
        if (msg.contains("今天") || msg.contains("马上") || msg.contains("尽快")
                || intent == IntentCategory.ESCALATION || intent == IntentCategory.HUMAN_HANDOFF) {
            return UrgencyLevel.HIGH;
        }
        if (intent == IntentCategory.COMPLAINT) {
            return UrgencyLevel.MEDIUM;
        }
        return UrgencyLevel.LOW;
    }

    private IntentCategory parseIntent(String value) {
        // 同时兼容 product_recommend 和 PRODUCT_RECOMMEND 两种输入形式。
        return IntentCategory.fromWireValue(value);
    }

    private String sliceJsonObject(String raw) {
        // 模型有时会在 JSON 外附带 Markdown 说明，这里截取最外层对象再解析。
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return "{}";
    }

    private List<String> charNgrams(String text) {
        // 生成 1、2、3 字符片段，降低中文分词缺失对相似度匹配的影响。
        String normalized = normalize(text);
        List<String> grams = new ArrayList<>();
        for (int n = 1; n <= 3; n++) {
            for (int i = 0; i + n <= normalized.length(); i++) {
                grams.add(normalized.substring(i, i + n));
            }
        }
        return grams;
    }

    private double jaccard(List<String> left, List<String> right) {
        // Jaccard = 交集 / 并集，用于衡量用户问题和 Few-shot 示例的相似程度。
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        java.util.Set<String> a = new java.util.HashSet<>(left);
        java.util.Set<String> b = new java.util.HashSet<>(right);
        long intersection = a.stream().filter(b::contains).count();
        long union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private String normalize(String value) {
        // 统一大小写和首尾空格，确保关键词、缓存和相似度计算使用同一输入形态。
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private String cacheKey(String message, List<Map<String, String>> history) {
        // 只拼接最近三轮历史，兼顾上下文敏感性和缓存 key 长度。
        StringBuilder key = new StringBuilder(normalize(message));
        if (history != null && !history.isEmpty()) {
            int start = Math.max(0, history.size() - 3);
            for (Map<String, String> item : history.subList(start, history.size())) {
                key.append('|')
                        .append(normalize(item.getOrDefault("role", "")))
                        .append(':')
                        .append(normalize(item.getOrDefault("content", "")));
            }
        }
        return key.toString();
    }

    private String intentGroup(IntentCategory intent) {
        // 细粒度 intent 与粗粒度 intent_group 分离，售前意图统一归入 presales。
        return (intent == null ? IntentCategory.OTHER : intent).groupWireValue();
    }

    private String allIntentNames() {
        // 将稳定的 wire value 提供给 LLM，避免暴露 Java 枚举名作为 API 契约。
        List<String> names = new ArrayList<>();
        for (IntentCategory category : IntentCategory.values()) {
            names.add(category.wireValue());
        }
        return String.join(", ", names);
    }

    private record VoteResult(IntentCategory intent, double confidence, Map<String, Double> sourceScores) {
    }
}
