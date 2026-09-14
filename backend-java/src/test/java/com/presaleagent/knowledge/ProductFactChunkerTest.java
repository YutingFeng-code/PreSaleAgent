package com.presaleagent.knowledge;

import dev.langchain4j.data.document.splitter.DocumentSplitters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductFactChunkerTest {

    private final ProductFactChunker chunker = new ProductFactChunker(DocumentSplitters.recursive(500, 80));

    @Test
    void splitsProductSpecificationIntoCompleteFactUnits() {
        String content = """
                商品名称：通勤降噪耳机 Pro
                型号：PA-100
                价格：499 元
                核心规格：头戴式，蓝牙 5.3
                功能支持：支持 NFC 和主动降噪
                续航：单次 30 小时
                尺寸重量：重量 250 克
                适用场景：通勤、差旅和办公室
                """;

        ProductFactChunker.ChunkingResult result = chunker.split("耳机规格", content);

        assertTrue(result.structured());
        assertEquals(2, result.chunks().size());
        assertTrue(result.chunks().stream().allMatch(chunk -> chunk.text().contains("商品文档：耳机规格")));
        ProductFactChunker.DocumentChunk overview = result.chunks().getFirst();
        ProductFactChunker.DocumentChunk capability = result.chunks().getLast();
        assertEquals("商品概览", overview.field());
        assertEquals("核心能力与场景", capability.field());
        assertTrue(overview.text().contains("价格：499 元"));
        assertTrue(overview.text().contains("核心规格：头戴式，蓝牙 5.3"));
        assertTrue(capability.text().contains("续航：单次 30 小时"));
        assertTrue(capability.text().contains("适用场景：通勤、差旅和办公室"));
    }

    @Test
    void keepsOrdinaryFaqOnRecursiveFallback() {
        ProductFactChunker.ChunkingResult result = chunker.split("FAQ", "如何申请退货？请在订单页提交申请。");

        assertTrue(!result.structured());
        assertNull(result.chunks().getFirst().field());
    }
}
