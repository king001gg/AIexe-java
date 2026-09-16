package com.ai.rag.controller;

import com.ai.rag.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文档读接口契约测试（缺陷 D8 的回归防护）
 *
 * <p><b>背景：</b>这 4 个端点此前**必定 500**——它们直接返回 JPA 实体，
 * 而实体上挂着 {@code @OneToMany(fetch = LAZY) documents}（KnowledgeBase 侧）和
 * {@code @ManyToOne(fetch = LAZY) knowledgeBase}（Document 侧）。
 * Jackson 序列化会去调这些 getter，此时事务已提交、Session 已关闭，
 * 于是抛 {@code LazyInitializationException}。
 *
 * <p><b>为什么以前没被测出来：</b>测试上下文的 {@code open-in-view} 取了默认值 {@code true}，
 * Session 被拖到响应渲染之后才关，懒加载「恰好」能工作。
 * 而生产/本地配置是 {@code open-in-view: false}，必现。
 * 现已把测试上下文也设为 {@code false}（见 {@link IntegrationTestSupport}），
 * 这类「返回实体 + 序列化懒关联」的写法会在这里直接暴露。
 */
class DocumentReadEndpointTest extends IntegrationTestSupport {

    private static final String KB_NAME = "D8 回归知识库";

    @Test
    @DisplayName("D8：GET /documents/knowledge-bases 返回 200，且不因懒关联 500")
    void listKnowledgeBasesSucceeds() throws Exception {
        indexDocument();

        mockMvc.perform(apiGet("/documents/knowledge-bases"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].name").value(KB_NAME))
                .andExpect(jsonPath("$[0].docCount").value(1));
    }

    @Test
    @DisplayName("D8：GET /documents/knowledge-bases/{id} 返回 200，且不含内部字段 filePath")
    void knowledgeBaseDetailSucceeds() throws Exception {
        long kbId = indexDocument();

        mockMvc.perform(apiGet("/documents/knowledge-bases/" + kbId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(KB_NAME))
                .andExpect(jsonPath("$.filePath").doesNotExist())
                .andExpect(jsonPath("$.documents").doesNotExist());
    }

    @Test
    @DisplayName("D8：GET /documents/knowledge-bases/{id}/documents 返回 200，关联只给 id")
    void knowledgeBaseDocumentsSucceeds() throws Exception {
        long kbId = indexDocument();

        mockMvc.perform(apiGet("/documents/knowledge-bases/" + kbId + "/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].chunkId").exists())
                .andExpect(jsonPath("$[0].knowledgeBaseId").value((int) kbId))
                // 关联对象不能整个序列化出来，只暴露主键
                .andExpect(jsonPath("$[0].knowledgeBase").doesNotExist());
    }

    @Test
    @DisplayName("D8：GET /documents/search 返回 200（此前无 API Key 时依赖向量路，也会 500）")
    void searchDocumentsSucceeds() throws Exception {
        indexDocument();

        mockMvc.perform(apiGet("/documents/search").param("query", "D8"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("D8：根路径返回端点清单，而不是 404")
    void apiIndexIsServed() throws Exception {
        mockMvc.perform(apiGet("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.application").value("ai-rag-agent"))
                .andExpect(jsonPath("$.endpoints").isMap());
    }

    /**
     * 上传一篇文档并返回知识库 id
     *
     * <p>走真实的 multipart 上传路径，而不是直接塞 repository —— 这样
     * 「上传 → 落库 → 读回」整条链路的实体状态才和线上一致。
     */
    private long indexDocument() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "d8.txt", "text/plain",
                "D8 回归测试用的文档内容，包含可检索的关键词 GAMMA-3000。".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart(CONTEXT_PATH + "/documents/upload")
                        .file(file)
                        .param("knowledgeBaseName", KB_NAME)
                        .param("chunkSize", "1000")
                        .param("chunkOverlap", "0")
                        .contextPath(CONTEXT_PATH))
                .andExpect(status().isOk());

        return jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_bases WHERE name = ?", Long.class, KB_NAME);
    }
}
