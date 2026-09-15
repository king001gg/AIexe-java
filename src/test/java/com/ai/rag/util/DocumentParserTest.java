package com.ai.rag.util;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文档解析器测试类
 */
class DocumentParserTest {

    @Test
    void testParsePlainTextUtf8() throws Exception {
        String content = "知识库检索支持多路召回与 RRF 融合。\n\n第二段内容。";
        File tempFile = File.createTempFile("upload_", "demo.txt");
        Files.write(tempFile.toPath(), content.getBytes(StandardCharsets.UTF_8));

        try {
            assertEquals(content, DocumentParser.parseDocument(tempFile));
        } finally {
            tempFile.delete();
        }
    }

    @Test
    void testParseMarkdown() throws Exception {
        File tempFile = File.createTempFile("upload_", "readme.md");
        Files.write(tempFile.toPath(), "# 标题\n正文".getBytes(StandardCharsets.UTF_8));

        try {
            assertEquals("# 标题\n正文", DocumentParser.parseDocument(tempFile));
        } finally {
            tempFile.delete();
        }
    }

    @Test
    void testUnsupportedFileTypeThrows() throws Exception {
        File tempFile = File.createTempFile("upload_", "demo.zip");
        try {
            assertThrows(IllegalArgumentException.class, () -> DocumentParser.parseDocument(tempFile));
        } finally {
            tempFile.delete();
        }
    }

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