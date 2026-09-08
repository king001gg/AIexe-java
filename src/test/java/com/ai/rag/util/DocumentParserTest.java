package com.ai.rag.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文档解析器测试类
 */
class DocumentParserTest {

    @Test
    void testChunkDocument() {
        String content = "第一章 简介\n\n" +
                "这是一个测试文档。\n\n" +
                "第二章 内容\n\n" +
                "这是第二部分的内容。";

        List<String> chunks = DocumentParser.chunkDocument(content, 100, 20);

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() >= 1);
    }

    @Test
    void testChunkDocumentWithEmptyContent() {
        List<String> chunks = DocumentParser.chunkDocument("", 100, 20);
        assertNotNull(chunks);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void testChunkDocumentWithNullContent() {
        List<String> chunks = DocumentParser.chunkDocument(null, 100, 20);
        assertNotNull(chunks);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void testChunkDocumentWithLongParagraph() {
        String longContent = "这是一个很长的段落。".repeat(50);
        List<String> chunks = DocumentParser.chunkDocument(longContent, 100, 20);

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        // 每个chunk不应超过chunkSize（加上重叠）
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 120);
        }
    }
}