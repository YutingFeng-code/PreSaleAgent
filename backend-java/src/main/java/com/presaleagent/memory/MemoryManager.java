package com.presaleagent.memory;

import com.presaleagent.config.PreSaleAgentProperties;
import com.presaleagent.llm.LlmGateway;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class MemoryManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final PreSaleAgentProperties properties;
    private final LlmGateway llmGateway;
    private final ContextCompactionService compactionService;
    private final VectorMemoryService vectorMemoryService;
    // 旧版本 JSON/JVM 情景记忆，仅在 Chroma VectorStore 不可用时回退读取。
    private final List<EpisodicEntry> episodicStore = new CopyOnWriteArrayList<>();
    private final Map<String, Map<String, Object>> profileStore = new ConcurrentHashMap<>();

    // 长期偏好采用追加去重，避免最近一轮画像覆盖历史事实。
    private static final Set<String> LONG_TERM_PROFILE_FIELDS = Set.of(
            "preferences", "purchase_history", "scenarios", "features");
    // 预算、收礼对象和限制通常属于当前购买任务，新值非空时覆盖旧值。
    private static final Set<String> CURRENT_PROFILE_FIELDS = Set.of(
            "budget", "recipients", "constraints", "considered_products");

    public MemoryManager(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, PreSaleAgentProperties properties, LlmGateway llmGateway,
                         ContextCompactionService compactionService, VectorMemoryService vectorMemoryService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.llmGateway = llmGateway;
        this.compactionService = compactionService;
        this.vectorMemoryService = vectorMemoryService;
    }

    @PostConstruct
    public void init() {
        loadPersistentMemory();
    }

    /**
     * 构建完整的记忆上下文，聚合四类记忆源供 Agent 编排器使用：
     * <ol>
     *   <li>工作记忆（Working Memory）—— 当前会话最近的对话消息列表，从 Redis 读取</li>
     *   <li>情景记忆（Episodic Memory）—— Chroma 中与当前查询相关的压缩摘要</li>
     *   <li>用户画像（User Profile）—— 持久化的购物偏好、预算、场景等结构化标签</li>
     *   <li>对话摘要（Conversation Summary）—— Redis 中的滚动摘要；同一摘要也异步写入 Chroma 情景记忆</li>
     * </ol>
     *
     * @param userId         用户唯一标识
     * @param conversationId 当前会话 ID
     * @param query          用户本轮输入，用于情景记忆检索
     * @return 聚合后的 {@link MemoryContext}，不会返回 null；各字段为空时以空集合/空字符串填充
     */
    public MemoryContext getContext(String userId, String conversationId, String query) {
        // 1. 从 Redis 获取当前会话最近 N 条消息（工作记忆）
        List<ConversationMessage> recent = getWorkingMemory(userId, conversationId);
        // 2. 基于用户输入检索向量库中的压缩摘要（情景记忆）；不可用时回退旧本地数据
        List<String> relevantHistory = searchEpisodic(userId, query);
        // 3. 优先读取向量库用户画像，缓存和旧 JSON 仅作为兼容回退
        Map<String, Object> profile = vectorMemoryService.loadUserProfile(userId)
                .orElseGet(() -> profileStore.getOrDefault(userId, Map.of()));
        // 4. 从 Redis 获取对话滚动摘要（工作记忆压缩后产生的摘要）
        String summary = safeRedisGet(summaryKey(userId, conversationId));
        return new MemoryContext(recent, relevantHistory, profile, summary == null ? "" : summary);
    }

    /**
     * 向工作记忆（Working Memory）中追加一条对话消息并持久化到 Redis。
     * <p>
     * 消息以 JSON 序列化后通过 Redis List 的 leftPush 写入，读取时按倒序取最近 N 条，
     * 因此 List 尾部对应最早的对话消息。
     * <p>
     * 写入后会执行两项维护操作：
     * <ol>
     *   <li>递增会话版本号（用于下游感知工作记忆变更）</li>
     *   <li>刷新 TTL 过期时间，避免长时间不活跃后会话数据残留</li>
     * </ol>
     * 当消息总数达到压缩阈值时，自动触发 {@link #compress} 进行工作记忆压缩，
     * 将早期对话摘要为滚动摘要以控制上下文长度。
     *
     * @param userId         用户唯一标识
     * @param conversationId 当前会话 ID
     * @param role           消息角色（USER / ASSISTANT / SYSTEM）
     * @param content        消息文本内容，会经过 {@link #safe} 空值保护
     */
    public void addMessage(String userId, String conversationId, MessageRole role, String content) {
        // 构造不可变的对话消息对象，时间戳取当前时刻
        ConversationMessage message = new ConversationMessage(role, safe(content), Instant.now(), Map.of());
        String key = wmKey(userId, conversationId);
        try {
            // 将消息序列化后推入 Redis List 头部
            redisTemplate.opsForList().leftPush(key, objectMapper.writeValueAsString(message));
            // 递增会话版本号，供下游组件检测工作记忆是否发生变化
            redisTemplate.opsForValue().increment(versionKey(userId, conversationId));
            // 刷新 TTL，防止会话数据过期前被意外清除
            redisTemplate.expire(key, Duration.ofSeconds(properties.getMemory().getTtlSeconds()));
            // 检查当前消息数量是否达到压缩阈值
            Long size = redisTemplate.opsForList().size(key);
            if (size != null && size >= properties.getMemory().getCompressAt()) {
                // 达到阈值，触发工作记忆压缩（将早期对话摘要为滚动摘要）
                compress(userId, conversationId);
            }
        } catch (Exception ex) {
            log.warn("Failed to write working memory: {}", ex.getMessage());
        }
    }

    /**
     * 异步更新用户画像：从当前会话的最近 10 条对话中，利用 LLM 提取用户的购物偏好。
     * <p>
     * 提取的画像维度包括：品牌/风格偏好、购买历史、预算区间、使用场景、
     * 收礼对象、关注功能、约束条件、考虑中的商品等。
     * <p>
     * 核心约束：
     * <ul>
     *   <li>画像只服务于售前个性化表达，商品价格、库存和规格必须来自目录或知识库，不能由画像推断</li>
     *   <li>只记录用户明确表达或已确认的信息，不把导购建议、商品目录结果或助手推测写成用户事实</li>
     *   <li>只有用户明确表示“已购买/使用过”的商品才能写入 purchase_history</li>
     *   <li>当前请求始终优先于历史画像</li>
     * </ul>
     * 提取结果写入 {@code profileStore} 作为缓存、持久化到本地文件作为兼容回退，
     * 同时异步写入 Chroma 的 {@code memory_type=user_profile} 文档。
     *
     * @param userId         用户唯一标识
     * @param conversationId 当前会话 ID
     */
    @Async
    public void updateProfile(String userId, String conversationId) {
        List<ConversationMessage> messages = getWorkingMemory(userId, conversationId);
        if (messages.isEmpty()) {
            return;
        }
        // 取最近 10 条对话，拼接为 "role: content" 格式的纯文本供 LLM 分析
        String text = tail(messages, 10).stream()
                .map(m -> m.role().name().toLowerCase() + ": " + m.content())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        // 画像只服务于售前个性化表达；商品价格、库存和规格必须来自目录或知识库，不能由画像推断。
        String prompt = """
                你是 PreSaleAgent 的售前用户画像提炼器。请从下面的售前导购对话中提取稳定、可复用的购物偏好，严格只返回 JSON。
                只记录用户明确表达或已经确认的信息，不要把导购建议、商品目录结果或助手推测写成用户事实。
                特别注意：只有用户明确表示“已经购买/使用过”的商品或品类，才能写入 purchase_history；用户询价、收藏、比较或考虑购买都不能算已购买。
                当前一轮的临时需求也可以记录，但应与长期偏好区分；当前请求始终优先于历史画像。
                没有明确证据的字段返回空数组，不要补全、猜测或编造。

                售前对话：
                %s

                返回格式：
                {
                  "preferences": ["明确表达的品牌、风格、价格敏感度或功能偏好"],
                  "purchase_history": ["用户明确提到已购买或使用过的商品/品类"],
                  "budget": ["用户明确说过的预算区间，保留原始单位"],
                  "scenarios": ["明确表达的使用场景"],
                  "recipients": ["明确表达的收礼对象或使用对象"],
                  "features": ["明确关注或要求的功能"],
                  "constraints": ["明确的尺寸、颜色、配送或其他限制"],
                  "considered_products": ["本轮明确比较或考虑的商品/型号"]
                }
                """.formatted(text);
        try {
            // 调用 LLM 提取画像，temperature=0 确保结果稳定可复现
            String raw = llmGateway.chat("", prompt, 0.0, 512);
            // 从 LLM 返回文本中截取 JSON 部分，防止前后有额外说明文字
            Map<String, Object> profile = objectMapper.readValue(sliceJson(raw), new TypeReference<>() {
            });
            // LLM 只返回本轮增量画像，不能直接覆盖历史画像。
            // 用户级同步保护“读取旧画像 -> 合并 -> 写回”这个复合操作，避免异步更新互相覆盖。
            synchronized (profileStore) {
                Map<String, Object> previous = profileStore.getOrDefault(userId, Map.of());
                Map<String, Object> merged = mergeProfile(previous, profile);
                profileStore.put(userId, merged);
                // 同步持久化到本地文件，保证合并后的完整画像可在重启后恢复。
                persistMemory();
                // 向量库保存跨会话画像；这里只保存画像 JSON，不保存商品事实。
                vectorMemoryService.saveUserProfileAsync(userId, merged, Instant.now());
            }
        } catch (Exception ex) {
            log.warn("Profile update failed: {}", ex.getMessage());
        }
    }

    /**
     * 合并一轮 LLM 提取的画像增量。
     * <p>
     * 长期字段（偏好、购买历史、常用场景、关注功能）做稳定去重追加；
     * 当前任务字段（预算、收礼对象、限制、考虑中的商品）只在新值非空时覆盖，
     * 从而既不丢失长期历史，也不会让上一次任务的约束污染当前推荐。
     * 该方法包可见，便于用纯单元测试验证合并规则。
     */
    static Map<String, Object> mergeProfile(Map<String, Object> previous, Map<String, Object> delta) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (previous != null) {
            previous.forEach((key, value) -> {
                if (key != null && value != null) {
                    merged.put(key, value);
                }
            });
        }
        if (delta == null) {
            return Map.copyOf(merged);
        }
        for (Map.Entry<String, Object> entry : delta.entrySet()) {
            String field = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (value instanceof List<?> newValues) {
                if (newValues.isEmpty()) {
                    // LLM 返回空数组表示“本轮没有新信息”，不能清空旧画像。
                    continue;
                }
                if (CURRENT_PROFILE_FIELDS.contains(field)) {
                    merged.put(field, cleanProfileList(newValues));
                } else if (LONG_TERM_PROFILE_FIELDS.contains(field)) {
                    merged.put(field, mergeProfileLists(merged.get(field), newValues));
                } else if (merged.get(field) instanceof List<?> oldValues) {
                    // 对历史版本中的未知数组字段也采用保守的追加去重策略。
                    merged.put(field, mergeProfileLists(oldValues, newValues));
                } else {
                    merged.put(field, cleanProfileList(newValues));
                }
            } else if (value instanceof Map<?, ?> newMap && merged.get(field) instanceof Map<?, ?> oldMap) {
                // 兼容旧画像中的嵌套 entities 字段，数组仍按去重规则合并。
                merged.put(field, mergeProfileMaps(oldMap, newMap));
            } else if (!(value instanceof String string) || !string.isBlank()) {
                merged.put(field, value);
            }
        }
        return Map.copyOf(merged);
    }

    private static Map<String, Object> mergeProfileMaps(Map<?, ?> previous, Map<?, ?> delta) {
        Map<String, Object> merged = new LinkedHashMap<>();
        previous.forEach((key, value) -> merged.put(String.valueOf(key), value));
        delta.forEach((key, value) -> {
            String field = String.valueOf(key);
            if (value instanceof List<?> values && !values.isEmpty()) {
                merged.put(field, mergeProfileLists(merged.get(field), values));
            } else if (value != null) {
                merged.put(field, value);
            }
        });
        return Map.copyOf(merged);
    }

    private static List<Object> mergeProfileLists(Object previous, List<?> delta) {
        LinkedHashSet<Object> values = new LinkedHashSet<>();
        if (previous instanceof List<?> oldValues) {
            values.addAll(cleanProfileList(oldValues));
        } else if (previous != null && (!(previous instanceof String string) || !string.isBlank())) {
            // 兼容早期版本将单值直接保存为字符串的画像格式。
            values.add(previous instanceof String string ? string.trim() : previous);
        }
        values.addAll(cleanProfileList(delta));
        return List.copyOf(values);
    }

    private static List<Object> cleanProfileList(List<?> values) {
        LinkedHashSet<Object> cleaned = new LinkedHashSet<>();
        for (Object value : values) {
            if (value instanceof String string) {
                if (!string.isBlank()) {
                    cleaned.add(string.trim());
                }
            } else if (value != null) {
                cleaned.add(value);
            }
        }
        return List.copyOf(cleaned);
    }

    /**
     * 从 Redis 读取当前会话的工作记忆（Working Memory）。
     * <p>
     * 工作记忆以 Redis List 存储，新消息通过 {@code leftPush} 写入头部，
     * 因此这里通过 {@code range(0, workingMax-1)} 取头部 N 条后倒序返回，
     * 保证返回结果按时间正序排列（最早的消息在前）。
     * <p>
     * 读取失败时返回空列表，不会抛出异常，保证调用方流程不中断。
     *
     * @param userId         用户唯一标识
     * @param conversationId 当前会话 ID
     * @return 按时间正序排列的最近 {@code workingMax} 条对话消息，不会返回 null
     */
    public List<ConversationMessage> getWorkingMemory(String userId, String conversationId) {
        try {
            String key = wmKey(userId, conversationId);
            // 从 Redis List 头部取最近 workingMax 条消息（List 头部 = 最新消息）
            List<String> raw = redisTemplate.opsForList().range(key, 0, properties.getMemory().getWorkingMax() - 1);
            if (raw == null) {
                return List.of();
            }
            // 倒序遍历，将结果翻转为时间正序（最早的消息在前）
            List<ConversationMessage> messages = new ArrayList<>();
            for (int i = raw.size() - 1; i >= 0; i--) {
                messages.add(objectMapper.readValue(raw.get(i), ConversationMessage.class));
            }
            return messages;
        } catch (Exception ex) {
            log.warn("Failed to read working memory: {}", ex.getMessage());
            return List.of();
        }
    }

    /**
     * 工作记忆压缩：当对话消息数达到压缩阈值时，将早期对话摘要为滚动摘要。
     * <p>
     * 压缩流程：
     * <ol>
     *   <li>获取当前工作记忆中的所有消息</li>
     *   <li>检查消息数是否达到压缩阈值，未达到则跳过</li>
     *   <li>从 Redis 获取已有的滚动摘要（如果有）</li>
     *   <li>调用 {@link ContextCompactionService#compact} 生成新的摘要</li>
     *   <li>压缩成功后持久化内存，保证摘要不丢失</li>
     * </ol>
     * 压缩的目的是控制上下文长度，避免长对话超出 LLM 的 token 限制。
     *
     * @param userId         用户唯一标识
     * @param conversationId 当前会话 ID
     */
    private void compress(String userId, String conversationId) {
        List<ConversationMessage> messages = getWorkingMemory(userId, conversationId);
        // 双重检查：防止并发场景下消息数在检查与压缩之间发生变化
        if (messages.size() < properties.getMemory().getCompressAt()) {
            return;
        }
        // 获取已有的滚动摘要，用于增量压缩（新摘要 = 旧摘要 + 新消息）
        String existingSummary = safeRedisGet(summaryKey(userId, conversationId));
        // 调用压缩服务，传入当前消息、旧摘要和触发原因
        CompactionRecord record = compactionService.compact(userId, conversationId, messages, existingSummary, "message_count");
        if (record.success() || record.fallback()) {
            // Redis 中的压缩摘要同时镜像到旧 JSON/JVM 槽位，作为 VectorStore 不可用时的兼容回退。
            cacheLegacyEpisodicSummary(userId, conversationId, safeRedisGet(summaryKey(userId, conversationId)));
            // 压缩成功（包括静态降级摘要），持久化内存以保证重启后摘要不丢失
            persistMemory();
        } else {
            log.debug("Memory compression deferred: {}", record.error());
        }
    }

    private void cacheLegacyEpisodicSummary(String userId, String conversationId, String summary) {
        if (summary == null || summary.isBlank()) return;
        String safeUser = safe(userId);
        String safeConversation = safe(conversationId);
        episodicStore.removeIf(entry -> entry.userId().equals(safeUser)
                && entry.conversationId().equals(safeConversation));
        episodicStore.add(new EpisodicEntry(safeUser, safeConversation, summary, summary,
                Instant.now(), embed(summary)));
    }

    /**
     * 情景记忆检索：优先从 Chroma 查询压缩摘要，不可用时读取旧 JSON/JVM 回退数据。
     * <p>
     * 检索流程：
     * <ol>
     *   <li>向量库路径：按 user_id + memory_type=episodic 过滤，取 Top-5 压缩摘要</li>
     *   <li>兼容路径：使用旧版本地 hash embedding 和余弦相似度检索</li>
     * </ol>
     * 情景记忆用于补充工作记忆，让 Agent 能够引用用户之前提到的信息，
     * 即使这些信息已经不在当前会话的最近 N 条消息中。
     *
     * @param userId 用户唯一标识
     * @param query  用户输入文本，用于语义检索
     * @return 最相关的 Top-5 历史对话摘要列表，按相似度降序排列；查询为空时返回空列表
     */
    private List<String> searchEpisodic(String userId, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        // 正式情景记忆来自向量库，内容是压缩摘要而非原始聊天消息。
        List<String> vectorResults = vectorMemoryService.searchEpisodic(userId, query, 5);
        if (!vectorResults.isEmpty()) {
            return vectorResults;
        }
        // 将用户输入转换为向量表示（基于 n-gram 哈希的简易嵌入）
        double[] queryVec = embed(query);
        // 过滤当前用户的记忆，按余弦相似度降序排序，取 Top-5
        return episodicStore.stream()
                .filter(e -> e.userId().equals(userId))
                .sorted(Comparator.comparingDouble((EpisodicEntry e) -> cosine(queryVec, e.embedding())).reversed())
                .limit(5)
                .map(EpisodicEntry::summary)
                .toList();
    }

    /**
     * 安全地从 Redis 读取字符串值，屏蔽 Redis 异常。
     * <p>
     * 当 Redis 不可用或发生异常时，返回 null 而不是抛出异常，
     * 保证调用方流程不会因缓存层故障而中断。
     *
     * @param key Redis 键
     * @return 对应的值，或 null（键不存在或读取失败时）
     */
    private String safeRedisGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception ex) {
            // Redis 故障时静默返回 null，由调用方处理默认值
            return null;
        }
    }

    /**
     * 获取列表的最后 n 个元素。
     * <p>
     * 用于提取最近 N 条对话消息进行画像分析，
     * 当列表元素不足 n 个时，返回整个列表。
     *
     * @param messages 原始消息列表
     * @param n        要获取的尾部元素数量
     * @return 包含最后 n 个元素的子列表
     */
    private List<ConversationMessage> tail(List<ConversationMessage> messages, int n) {
        return messages.subList(Math.max(0, messages.size() - n), messages.size());
    }

    /**
     * 从 LLM 返回的文本中提取 JSON 对象部分。
     * <p>
     * LLM 可能在 JSON 前后附加说明文字，此方法通过查找第一个 '{' 和最后一个 '}'
     * 来截取 JSON 对象字符串，避免解析失败。
     * <p>
     * 如果未找到有效的 JSON 边界，返回空对象字符串 "{}"。
     *
     * @param raw LLM 返回的原始文本
     * @return 截取后的 JSON 对象字符串
     */
    private String sliceJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : "{}";
    }

    /**
     * 生成工作记忆的 Redis 键。
     * 格式：{@code wm:{userId}:{conversationId}}
     */
    private String wmKey(String userId, String conversationId) {
        return "wm:" + safe(userId) + ":" + safe(conversationId);
    }

    /**
     * 生成对话摘要的 Redis 键。
     * 格式：{@code summary:{userId}:{conversationId}}
     */
    private String summaryKey(String userId, String conversationId) {
        return "summary:" + safe(userId) + ":" + safe(conversationId);
    }

    /**
     * 生成会话版本号的 Redis 键。
     * 格式：{@code wm-version:{userId}:{conversationId}}
     * 每次写入工作记忆时递增，供下游组件检测变更。
     */
    private String versionKey(String userId, String conversationId) {
        return "wm-version:" + safe(userId) + ":" + safe(conversationId);
    }

    /**
     * 空值保护：将 null 转换为空字符串，防止 Redis 键中出现 null 字面量。
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 将文本转换为固定维度的向量表示（256维），用于记忆相似度检索。
     * <p>
     * 算法原理：基于字符级 n-gram（n=1~3）的哈希向量化（Hashing Vectorizer）。
     * 对每个 n-gram 计算 hashCode，通过取模映射到向量索引，
     * 再根据哈希值奇偶性决定 +1.0 或 -1.0 进行累加，
     * 从而在低开销下获得具备一定语义区分能力的文本向量。
     *
     * @param text 待向量化的原始文本
     * @return 256 维的 double 向量
     */
    private double[] embed(String text) {
        // 固定 256 维向量，作为哈希桶数组
        double[] vector = new double[256];
        // 统一转小写，消除大小写差异对相似度计算的影响
        String normalized = text == null ? "" : text.toLowerCase();
        // 遍历 1-gram、2-gram、3-gram，兼顾单字符特征与局部短语特征
        for (int n = 1; n <= 3; n++) {
            for (int i = 0; i + n <= normalized.length(); i++) {
                String gram = normalized.substring(i, i + n);
                int hash = gram.hashCode();
                // 取模映射到向量索引（floorMod 保证非负）
                int idx = Math.floorMod(hash, vector.length);
                // 根据哈希最低位决定累加方向，模拟有符号哈希计数
                vector[idx] += (hash & 1) == 0 ? 1.0 : -1.0;
            }
        }
        return vector;
    }

    private double cosine(double[] a, double[] b) {
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0.0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private void loadPersistentMemory() {
        Path path = Path.of(properties.getStorage().getMemoryPath());
        if (!Files.exists(path)) {
            return;
        }
        try {
            StoredMemory stored = objectMapper.readValue(path.toFile(), StoredMemory.class);
            if (stored.episodic() != null) {
                stored.episodic().forEach(entry -> {
                    if (entry.summary() != null && !entry.summary().isBlank()) {
                        episodicStore.add(new EpisodicEntry(
                                safe(entry.userId()),
                                safe(entry.conversationId()),
                                safe(entry.summary()),
                                safe(entry.fullText()),
                                entry.timestamp() == null ? Instant.now() : entry.timestamp(),
                                embed(entry.summary())
                        ));
                    }
                });
            }
            if (stored.profiles() != null) {
                profileStore.putAll(stored.profiles());
            }
            log.info("Loaded persisted memory: episodic={}, profiles={}", episodicStore.size(), profileStore.size());
        } catch (Exception ex) {
            log.warn("Failed to load persisted memory store {}: {}", path, ex.getMessage());
        }
    }

    private synchronized void persistMemory() {
        Path path = Path.of(properties.getStorage().getMemoryPath());
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            List<StoredEpisodicEntry> episodic = episodicStore.stream()
                    .map(entry -> new StoredEpisodicEntry(entry.userId(), entry.conversationId(), entry.summary(), entry.fullText(), entry.timestamp()))
                    .toList();
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(path.toFile(), new StoredMemory(episodic, Map.copyOf(profileStore)));
        } catch (Exception ex) {
            log.warn("Failed to persist memory store {}: {}", path, ex.getMessage());
        }
    }

    private record EpisodicEntry(String userId, String conversationId, String summary, String fullText, Instant timestamp, double[] embedding) {
    }

    private record StoredMemory(List<StoredEpisodicEntry> episodic, Map<String, Map<String, Object>> profiles) {
        private StoredMemory {
            episodic = episodic == null ? List.of() : episodic;
            profiles = profiles == null ? Map.of() : profiles;
        }
    }

    private record StoredEpisodicEntry(String userId, String conversationId, String summary, String fullText, Instant timestamp) {
    }
}
