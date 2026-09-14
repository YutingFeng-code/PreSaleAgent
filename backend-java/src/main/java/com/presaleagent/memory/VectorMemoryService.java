package com.presaleagent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Vector-store adapter for long-lived memory.
 *
 * <p>Only compacted summaries are written as episodic memory. Raw conversation
 * messages remain in Redis working memory and are never copied to this store.</p>
 */
@Service
public class VectorMemoryService {
    private static final Logger log = LoggerFactory.getLogger(VectorMemoryService.class);
    private static final String EPISODIC = "episodic";
    private static final String PROFILE = "user_profile";

    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;

    public VectorMemoryService(ObjectProvider<VectorStore> vectorStoreProvider, ObjectMapper objectMapper) {
        this.vectorStore = vectorStoreProvider.getIfAvailable();
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        return vectorStore != null;
    }

    /** Queue an episodic write so Chroma latency cannot block /chat. */
    @Async("agentTaskExecutor")
    public void saveEpisodicSummaryAsync(String userId, String conversationId, String summary, Instant createdAt) {
        saveEpisodicSummary(userId, conversationId, summary, createdAt);
    }

    /** Save only the compressed, structured conversation summary. */
    public void saveEpisodicSummary(String userId, String conversationId, String summary, Instant createdAt) {
        if (!isAvailable() || summary == null || summary.isBlank()) return;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("user_id", safe(userId));
        metadata.put("conversation_id", safe(conversationId));
        metadata.put("memory_type", EPISODIC);
        metadata.put("source", "conversation_compaction");
        metadata.put("created_at", (createdAt == null ? Instant.now() : createdAt).toString());
        try {
            vectorStore.add(List.of(new Document(memoryId(EPISODIC, userId, conversationId, summary), summary, metadata)));
        } catch (Exception ex) {
            log.warn("Vector episodic memory write failed: {}", ex.getMessage());
        }
    }

    /** Queue a profile write; profile data is preference context, never product facts. */
    @Async("agentTaskExecutor")
    public void saveUserProfileAsync(String userId, Map<String, Object> profile, Instant updatedAt) {
        saveUserProfile(userId, profile, updatedAt);
    }

    public void saveUserProfile(String userId, Map<String, Object> profile, Instant updatedAt) {
        if (!isAvailable() || profile == null || profile.isEmpty()) return;
        try {
            String content = objectMapper.writeValueAsString(profile);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("user_id", safe(userId));
            metadata.put("memory_type", PROFILE);
            metadata.put("source", "profile_extraction");
            metadata.put("updated_at", (updatedAt == null ? Instant.now() : updatedAt).toString());
            // Stable id lets Chroma upsert the current profile instead of creating a
            // new profile document for every chat turn.
            vectorStore.add(List.of(new Document(profileId(userId), content, metadata)));
        } catch (Exception ex) {
            log.warn("Vector user profile write failed: {}", ex.getMessage());
        }
    }

    /** Retrieve the newest profile for a user; callers can fall back to local legacy storage. */
    public Optional<Map<String, Object>> loadUserProfile(String userId) {
        if (!isAvailable()) return Optional.empty();
        try {
            List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query("user profile " + safe(userId))
                    .topK(20)
                    .filterExpression(filter("user_id", userId) + " && " + filter("memory_type", PROFILE))
                    .build());
            return docs == null ? Optional.empty() : docs.stream()
                    .max(Comparator.comparing(this::updatedAt))
                    .flatMap(this::parseProfile);
        } catch (Exception ex) {
            log.debug("Vector user profile read unavailable: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** Retrieve compressed summaries only; raw messages are intentionally excluded. */
    public List<String> searchEpisodic(String userId, String query, int topK) {
        if (!isAvailable() || query == null || query.isBlank()) return List.of();
        try {
            List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(Math.max(1, Math.min(topK, 20)))
                    .filterExpression(filter("user_id", userId) + " && " + filter("memory_type", EPISODIC))
                    .build());
            if (docs == null) return List.of();
            return docs.stream().map(Document::getText).filter(s -> s != null && !s.isBlank()).toList();
        } catch (Exception ex) {
            log.debug("Vector episodic memory read unavailable: {}", ex.getMessage());
            return List.of();
        }
    }

    private Optional<Map<String, Object>> parseProfile(Document document) {
        try {
            return Optional.of(objectMapper.readValue(document.getText(), new TypeReference<>() {}));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private Instant updatedAt(Document document) {
        Object value = document.getMetadata().get("updated_at");
        try { return value == null ? Instant.EPOCH : Instant.parse(String.valueOf(value)); }
        catch (Exception ignored) { return Instant.EPOCH; }
    }

    private String filter(String key, String value) {
        // Metadata filters are built from trusted request identifiers only; strip
        // expression characters so a user id cannot alter the Chroma predicate.
        String normalized = safe(value);
        return key + " == '" + normalized + "'";
    }

    private String memoryId(String type, String userId, String conversationId, String content) {
        String input = type + ":" + safe(userId) + ":" + safe(conversationId) + ":" + content;
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(type).append(":");
            for (int i = 0; i < 12 && i < bytes.length; i++) out.append(String.format("%02x", bytes[i]));
            return out.toString();
        } catch (Exception ex) { return type + ":" + Integer.toHexString(input.hashCode()); }
    }

    private String profileId(String userId) {
        return PROFILE + ":" + Integer.toHexString(safe(userId).hashCode());
    }

    private String safe(String value) {
        return value == null ? "" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
