package com.presaleagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.presaleagent.config.PreSaleAgentProperties;
import com.presaleagent.context.ContextBudget;
import com.presaleagent.llm.LlmGateway;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

/** Bounded, lock-protected summarisation. It never deletes source messages itself. */
@Service
public class ContextCompactionService {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final PreSaleAgentProperties properties;
    private final LlmGateway llm;
    private final VectorMemoryService vectorMemoryService;

    public ContextCompactionService(StringRedisTemplate redis, ObjectMapper mapper,
                                    PreSaleAgentProperties properties, LlmGateway llm,
                                    VectorMemoryService vectorMemoryService) {
        this.redis = redis; this.mapper = mapper; this.properties = properties; this.llm = llm;
        this.vectorMemoryService = vectorMemoryService;
    }

    /**
     * 上下文压缩（Compaction）：将早期对话消息总结为摘要，仅保留最近 N 条消息在工作记忆中。
     * <p>
     * 流程概览：
     * 1. 冷却检查 → 2. 分布式锁 + 版本号快照 → 3. 分离旧消息与尾部消息 →
     * 4. LLM 结构化摘要（失败则降级） → 5. 版本号一致性校验 → 6. 写回 Redis
     *
     * @param userId          用户 ID
     * @param conversationId  会话 ID
     * @param messages        当前工作记忆中的全部消息
     * @param existingSummary 已有的历史摘要（将与新摘要拼接）
     * @param trigger         触发来源标识（如 "token_threshold"、"message_count"），用于追踪
     * @return CompactionRecord 记录本次压缩的结果与状态
     */
    public CompactionRecord compact(String userId, String conversationId, List<ConversationMessage> messages,
                                    String existingSummary, String trigger) {
        // 防御性拷贝，避免压缩过程中外部修改消息列表
        List<ConversationMessage> source = messages == null ? List.of() : List.copyOf(messages);
        String hash = hash(source);
        String lockKey = "compaction:" + safe(userId) + ":" + safe(conversationId);
        String cooldownKey = lockKey + ":cooldown";

        // ── 步骤 1：冷却检查 ──
        // 上一次压缩失败后设置了 30 秒冷却期，防止 LLM 连续超时时反复触发压缩
        try {
            if (Boolean.TRUE.equals(redis.hasKey(cooldownKey))) {
                return new CompactionRecord(userId, conversationId, hash, source.size(), 0, false, true, trigger, Instant.now(), "compaction cooldown");
            }
        } catch (Exception ignored) { }

        // ── 步骤 2：分布式锁 + 版本号快照 ──
        // 记录当前工作记忆版本号，用于压缩完成后检测期间是否有新消息写入
        Boolean locked = false;
        String versionKey = "wm-version:" + safe(userId) + ":" + safe(conversationId);
        String sourceVersion = "";
        try {
            sourceVersion = String.valueOf(redis.opsForValue().get(versionKey));
            // 使用 SETNX 实现互斥锁，30 秒自动过期防止死锁
            locked = redis.opsForValue().setIfAbsent(lockKey, hash, Duration.ofSeconds(30));
        } catch (Exception ignored) { }
        if (!Boolean.TRUE.equals(locked)) {
            return new CompactionRecord(userId, conversationId, hash, source.size(), 0, false,
                    true, trigger, Instant.now(), "compaction already in progress");
        }
        try {
            // ── 步骤 3：分离旧消息与尾部保留消息 ──
            // keep = 配置保留的尾部消息数；old = 需要被总结的早期消息
            // 计算需要保留在工作记忆中的尾部消息数量，取配置值与实际消息总数的较小值，避免越界。
            // 保留最近多少条消息：先以配置的 tailMessages 为上限，
            // 再从最近消息向前累加 token，超过摘要触发阈值前停止，避免一次压缩过度。
            int configuredKeep = Math.max(0, properties.getContext().getTailMessages());
            int maxKeep = Math.min(configuredKeep, source.size());
            int tailTokenBudget = Math.max(1,
                    (int) Math.round(properties.getContext().getMaxTokens() * properties.getContext().getCompactThreshold()));

            int keep = 0;
            int estimatedTailTokens = 0;
            while (keep < maxKeep) {
                ConversationMessage tail = source.get(source.size() - keep - 1);
                int tailTokens = ContextBudget.estimate(tail.role().name().toLowerCase() + ": " + tail.content());

                // 至少保留 1 条最近消息，防止 token 预算估算过紧导致工作记忆被完全清空。
                if (keep > 0 && estimatedTailTokens + tailTokens > tailTokenBudget) {
                    break;
                }

                keep++;
                estimatedTailTokens += tailTokens;
            }
            List<ConversationMessage> old = source.subList(0, Math.max(0, source.size() - keep));
            // 没有需要压缩的旧消息，直接返回
            if (old.isEmpty()) return new CompactionRecord(userId, conversationId, hash, source.size(), keep,
                    true, false, trigger, Instant.now(), "");

            // ── 步骤 4：构建结构化摘要 Prompt 并调用 LLM ──
            // Prompt 要求 LLM 按固定字段提取已确认事实，避免幻觉猜测
            String prompt = "请严格按以下字段总结售前对话，只保留已确认事实，不要猜测：\n"
                    + "当前购买目标：\n预算与限制：\n使用场景：\n收礼对象：\n关注功能：\n"
                    + "已经比较的商品：\n已确认商品事实及来源：\n未解决问题：\n下一步：\n\n"
                    + old.stream().map(m -> m.role().name().toLowerCase() + ": " + m.content())
                    .reduce((a, b) -> a + "\n" + b).orElse("");
            String summary;
            try {
                // 异步调用 LLM，设置超时防止长时间阻塞
                summary = CompletableFuture.supplyAsync(() -> llm.chat("", prompt, 0.0, 512))
                        .get(properties.getContext().getCompactionTimeoutSeconds(), TimeUnit.SECONDS);
            } catch (Exception ex) {
                // ── LLM 降级处理 ──
                // 超时时使用兜底摘要，设置冷却期，不覆盖工作记忆中的原始消息
                // 当 LLM 调用超时或异常时，不使用模型摘要，而是生成一个本地兜底摘要。
                // 这里仍然会更新 summary 缓存，保证后续上下文可以拿到基本的历史压缩信息；
                // 但不会重建工作记忆，避免在 LLM 不稳定时误删或覆盖原始对话消息。
                summary = fallback(old);

                // 将已有历史摘要与本次兜底摘要合并，避免后续读取 summary 时丢失旧摘要。
                // existingSummary 可能为 null，因此先转为空字符串再 trim，防止出现多余空行或 null 文本。
                String combined = ((existingSummary == null ? "" : existingSummary.trim()) + "\n" + summary).trim();

                // 写入合并后的摘要到 Redis summary key，并沿用记忆 TTL。
                // key 命名规则与正常压缩成功路径保持一致，确保后续上下文组装逻辑能读取到同一份摘要。
                redis.opsForValue().set("summary:" + safe(userId) + ":" + safe(conversationId), combined,
                        Duration.ofSeconds(properties.getMemory().getTtlSeconds()));
                // 降级摘要也属于情景记忆，异步写入向量库；失败不影响 Redis 工作记忆。
                vectorMemoryService.saveEpisodicSummaryAsync(userId, conversationId, combined, Instant.now());

                // 设置 30 秒冷却标记，避免 LLM 连续失败时反复触发压缩请求，降低下游模型服务压力。
                redis.opsForValue().set(cooldownKey, "1", Duration.ofSeconds(30));

                // 返回降级压缩结果：
                // 1. compacted=false：表示本次没有完成真正的 LLM 摘要压缩；
                // 2. cooldown=true：表示已设置冷却期，调用方短期内应避免再次触发；
                // 3. reason=异常类型：用于排查是超时、网络错误还是模型响应异常等具体问题。
                return new CompactionRecord(userId, conversationId, hash, source.size(), keep,
                        false, true, trigger, Instant.now(), ex.getClass().getSimpleName());
            }

            // ── 步骤 5：版本号一致性校验 ──
            // 压缩期间如果有新消息写入（版本号变化），放弃本次结果，避免覆盖新数据
            String combined = ((existingSummary == null ? "" : existingSummary.trim()) + "\n" + summary.trim()).trim();
            if (!sourceVersion.equals(String.valueOf(redis.opsForValue().get(versionKey)))) {
                return new CompactionRecord(userId, conversationId, hash, source.size(), 0, false, true, trigger, Instant.now(), "working memory changed during compaction");
            }

            // ── 步骤 6：写回 Redis ──
            // 6a. 保存合并后的摘要（旧摘要 + 新摘要）
            redis.opsForValue().set("summary:" + safe(userId) + ":" + safe(conversationId), combined,
                        Duration.ofSeconds(properties.getMemory().getTtlSeconds()));
            // 情景记忆只接收压缩后的摘要，不接收被保留在 Redis 中的原始消息。
            vectorMemoryService.saveEpisodicSummaryAsync(userId, conversationId, combined, Instant.now());
            // 6b. 重建工作记忆：先清空，再只写入尾部保留的消息
            String key = "wm:" + safe(userId) + ":" + safe(conversationId);
            redis.delete(key);
            for (int i = keep - 1; i >= 0; i--) {
                redis.opsForList().leftPush(key, mapper.writeValueAsString(source.get(source.size() - keep + i)));
            }
            redis.expire(key, Duration.ofSeconds(properties.getMemory().getTtlSeconds()));
            return new CompactionRecord(userId, conversationId, hash, source.size(), keep,
                    true, false, trigger, Instant.now(), "");
        } catch (Exception ex) {
            return new CompactionRecord(userId, conversationId, hash, source.size(), 0, false,
                    true, trigger, Instant.now(), ex.getClass().getSimpleName());
        } finally {
            // 无论成功或异常，始终释放分布式锁
            try { redis.delete(lockKey); } catch (Exception ignored) { }
        }
    }

    public boolean shouldCompact(String text, int messageCount) {
        int used = ContextBudget.estimate(text);
        return used >= Math.round(properties.getContext().getMaxTokens() * properties.getContext().getCompactThreshold())
                || messageCount >= properties.getMemory().getCompressAt();
    }

    /** Keep only stable catalog facts before putting tool output into a summary prompt. */
    public Map<String, Object> pruneCatalogResult(Map<String, Object> result) {
        if (result == null || result.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("id", "name", "brand", "price", "currency", "stock", "delivery_days", "specs", "source")) {
            if (result.containsKey(key)) out.put(key, result.get(key));
        }
        return Map.copyOf(out);
    }

    private String fallback(List<ConversationMessage> old) {
        return "历史对话已压缩，保留 " + old.size() + " 条消息的购买约束和已确认商品事实。";
    }

    private String hash(List<ConversationMessage> messages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (ConversationMessage m : messages) digest.update((m.role() + ":" + m.content()).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest.digest()) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception ex) { return Integer.toHexString(messages.hashCode()); }
    }

    private String safe(String value) { return value == null ? "" : value.replaceAll("[^a-zA-Z0-9._-]", "_"); }
}
