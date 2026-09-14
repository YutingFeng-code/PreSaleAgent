package com.presaleagent.knowledge;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 面向售前商品文档的结构化切块器。
 *
 * <p>如果文档包含多个商品字段，就按“相关字段组 = 事实单元”切分；普通 FAQ 或散文式文档
 * 仍交给 LangChain4j 的递归切分器处理，避免改变已有知识库行为。</p>
 */
public final class ProductFactChunker {

    private static final int MIN_STRUCTURED_FIELDS = 3;

    private final DocumentSplitter fallbackSplitter;

    public ProductFactChunker(DocumentSplitter fallbackSplitter) {
        this.fallbackSplitter = fallbackSplitter;
    }

    public ChunkingResult split(String title, String content) {
        String text = content == null ? "" : content.replace("\r\n", "\n").trim();
        if (text.isBlank()) {
            return new ChunkingResult(List.of(), false);
        }

        List<FieldMatch> fields = findFields(text);
        if (fields.size() < MIN_STRUCTURED_FIELDS) {
            return new ChunkingResult(
                    fallback(text),
                    false
            );
        }

        String documentTitle = title == null || title.isBlank() ? "商品文档" : title.trim();
        // 将字段按业务语义合并，避免“续航：30 小时”这类过小、缺少上下文的 chunk。
        Map<String, List<String>> groupedFacts = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            FieldMatch field = fields.get(i);
            int valueEnd = i + 1 < fields.size() ? fields.get(i + 1).start() : text.length();
            String value = text.substring(field.valueStart(), valueEnd).trim();
            if (value.isBlank()) {
                continue;
            }
            groupedFacts.computeIfAbsent(sectionFor(field.canonicalName()), ignored -> new ArrayList<>())
                    .add(field.canonicalName() + "：" + value);
        }

        List<DocumentChunk> chunks = new ArrayList<>();
        for (Map.Entry<String, List<String>> group : groupedFacts.entrySet()) {
            String fact = "商品文档：" + documentTitle + "\n"
                    + String.join("\n", group.getValue());
            // 分组过长时仍使用递归切分，避免极端商品描述撑爆上下文。
            if (fact.length() > 500) {
                chunks.addAll(fallback(fact).stream()
                        .map(segment -> new DocumentChunk(segment.text(), group.getKey()))
                        .toList());
            } else {
                chunks.add(new DocumentChunk(fact, group.getKey()));
            }
        }
        return new ChunkingResult(chunks, !chunks.isEmpty());
    }

    private String sectionFor(String field) {
        return switch (field) {
            case "商品名称", "型号", "价格", "核心规格" -> "商品概览";
            case "功能支持", "续航", "尺寸重量", "适用场景" -> "核心能力与场景";
            default -> "商品信息";
        };
    }

    private List<DocumentChunk> fallback(String text) {
        try {
            return fallbackSplitter.split(Document.from(text)).stream()
                    .map(segment -> new DocumentChunk(segment.text(), null))
                    .toList();
        } catch (Exception ignored) {
            // 第三方切分器异常时仍保证知识导入可用，使用固定长度降级切分。
            List<DocumentChunk> chunks = new ArrayList<>();
            for (int start = 0; start < text.length(); start += 500) {
                chunks.add(new DocumentChunk(text.substring(start, Math.min(text.length(), start + 500)), null));
            }
            return chunks;
        }
    }

    private List<FieldMatch> findFields(String text) {
        List<FieldMatch> fields = new ArrayList<>();
        int lineStart = 0;
        while (lineStart < text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            String line = text.substring(lineStart, lineEnd);
            String candidate = line.trim();
            while (candidate.startsWith("-") || candidate.startsWith("*") || candidate.startsWith("#")) {
                candidate = candidate.substring(1).trim();
            }
            int colon = Math.max(candidate.indexOf('：'), candidate.indexOf(':'));
            if (colon > 0) {
                String fieldName = canonicalName(candidate.substring(0, colon).trim());
                if (fieldName != null) {
                    int valueOffset = line.indexOf(candidate) + colon + 1;
                    fields.add(new FieldMatch(lineStart, lineEnd, lineStart + valueOffset, fieldName));
                }
            }
            lineStart = lineEnd < text.length() ? lineEnd + 1 : text.length();
        }
        return fields;
    }

    private String canonicalName(String field) {
        return switch (field.toLowerCase(Locale.ROOT)) {
            case "商品名" -> "商品名称";
            case "规格参数" -> "核心规格";
            case "支持功能" -> "功能支持";
            case "续航时间" -> "续航";
            case "尺寸", "重量" -> "尺寸重量";
            case "使用场景" -> "适用场景";
            case "商品名称", "型号", "价格", "核心规格", "功能支持", "续航", "尺寸重量", "适用场景" -> field;
            default -> null;
        };
    }

    /** field 表示业务分组，而不是单个字段名。 */
    public record DocumentChunk(String text, String field) {
    }

    public record ChunkingResult(List<DocumentChunk> chunks, boolean structured) {
    }

    private record FieldMatch(int start, int end, int valueStart, String canonicalName) {
    }
}
