package com.ai.rag.config;

import com.ai.rag.support.DeterministicEmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 余弦口径适配器的单元测试（缺陷 D3）
 *
 * <p>不启 Spring：直接构造 store 与代理，验证的是 D3 修复的核心承诺——
 * <b>{@code min-score} 在任何 store 上都表示「余弦 ≥ X」，且返回的 score 就是原始余弦。</b>
 *
 * <p>第一条用例是全篇的重点：同一个「零重叠文档 + {@code minScore=0.1}」的组合，
 * 裸 {@link InMemoryEmbeddingStore} 会召回（因为它的 score 是 {@code (0+1)/2 = 0.5}），
 * 包过之后被正确挡掉。这个对比就是 D3 本体。
 */
class ScoreNormalizingEmbeddingStoreTest {

    private static final String DOC = "Milvus 向量库的默认端口是 19530。";

    /** 词袋桩下与 {@link #DOC} 没有任何共享词元 → 原始余弦恰为 0 */
    private static final String UNRELATED_QUERY = "请背诵一段完全无关的莎士比亚台词";

    // ------------------------------------------------------------------
    // 一、降级 store：阈值换算 + 分数还原
    // ------------------------------------------------------------------

    @Test
    @DisplayName("D3：min-score 统一为余弦口径——零重叠文档 + minScore=0.1 必须被挡掉（裸内存库会召回）")
    void minScoreMeansCosineNotShiftedRelevanceScore() {
        DeterministicEmbeddingModel model = new DeterministicEmbeddingModel();
        InMemoryEmbeddingStore<TextSegment> raw = new InMemoryEmbeddingStore<>();
        raw.add(model.embedText(DOC), TextSegment.from(DOC));

        Embedding query = model.embedText(UNRELATED_QUERY);

        // 裸内存库：score = (0+1)/2 = 0.5 >= 0.1 → 召回。这正是 D3 的缺陷现场。
        assertThat(matches(raw, query, 0.1))
                .as("未包装时 minScore 是 RelevanceScore 口径，0.1 形同虚设")
                .hasSize(1);

        ScoreNormalizingEmbeddingStore store = ScoreNormalizingEmbeddingStore.wrap(raw);

        assertThat(matches(store, query, 0.1))
                .as("包装后 minScore 是余弦口径：余弦 0 < 0.1，必须挡掉")
                .isEmpty();
        assertThat(matches(store, query, 0.0))
                .as("阈值为 0.0 时余弦 0 恰好落在边界上（闭区间）——说明阈值真的在余弦轴上，"
                        + "而不是像裸内存库那样被整体平移")
                .hasSize(1);
    }

    @Test
    @DisplayName("D3：minScore 的合法域由 LangChain4j 限定为 [0,1]（负余弦阈值表达不出来）")
    void minScoreDomainIsZeroToOne() {
        // 这条不是重复造轮子，而是把「适配器为什么只需覆盖 [0,1]」写成可执行事实：
        // 换算 (c+1)/2 把 [0,1] 映到 [0.5,1]，两者都在合法域内，所以不存在漏网的边角。
        assertThatThrownBy(() -> EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1f, 0f}))
                .maxResults(5)
                .minScore(-0.1)
                .build())
                .as("负阈值会被 EmbeddingSearchRequest 直接拒绝，两个 store 都到不了这一步")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minScore must be between");

        assertThatThrownBy(() -> EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1f, 0f}))
                .maxResults(0)
                .build())
                .as("maxResults 必须为正——所以测试里的 maxResults 不能写 0")
                .isInstanceOf(IllegalArgumentException.class);

        // 两端闭区间：0.0 与 1.0 都必须被接受
        assertThatCode(() -> EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1f, 0f}))
                .maxResults(5).minScore(0.0).build()).doesNotThrowAnyException();
        assertThatCode(() -> EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1f, 0f}))
                .maxResults(5).minScore(1.0).build()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("D3：返回的 score 是原始余弦（0.0），而不是 (cos+1)/2 的 0.5")
    void returnedScoreIsRawCosineSimilarity() {
        DeterministicEmbeddingModel model = new DeterministicEmbeddingModel();
        InMemoryEmbeddingStore<TextSegment> raw = new InMemoryEmbeddingStore<>();
        raw.add(model.embedText(DOC), TextSegment.from(DOC));

        Embedding query = model.embedText(UNRELATED_QUERY);
        double rawCosine = dot(query, model.embedText(DOC));

        assertThat(rawCosine)
                .as("前提：词袋桩下两者零共享词元，原始余弦为 0")
                .isEqualTo(0.0);

        // 用最小的合法阈值 0.0 取回全部（余弦 0 恰好卡在边界上）
        List<EmbeddingMatch<TextSegment>> hits = matches(ScoreNormalizingEmbeddingStore.wrap(raw), query, 0.0);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).score())
                .as("对外暴露的 score 必须是原始余弦，调用方不该知道降级库存在过映射")
                .isCloseTo(rawCosine, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(hits.get(0).embedded().text())
                .as("换算只动分数，正文与元数据必须原样保留")
                .isEqualTo(DOC);
    }

    @Test
    @DisplayName("D3：完全匹配的文档在余弦口径下是 1.0（换算两端自洽，没有翻倍/偏移）")
    void perfectMatchScoresOne() {
        DeterministicEmbeddingModel model = new DeterministicEmbeddingModel();
        InMemoryEmbeddingStore<TextSegment> raw = new InMemoryEmbeddingStore<>();
        raw.add(model.embedText(DOC), TextSegment.from(DOC));

        List<EmbeddingMatch<TextSegment>> hits =
                matches(ScoreNormalizingEmbeddingStore.wrap(raw), model.embedText(DOC), 0.99);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).score())
                .as("与自身余弦为 1，若换算写反（r*2-1 vs (r+1)/2）这里会露馅")
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }

    // ------------------------------------------------------------------
    // 二、非降级 store：必须原样透传
    // ------------------------------------------------------------------

    @Test
    @DisplayName("D3：代理不是内存实现时（Milvus COSINE）原样透传——minScore 不被改写、分数不被改写")
    void passesThroughUntouchedForCosineNativeStore() {
        EmbeddingStore<TextSegment> cosineNative = mock(EmbeddingStore.class);

        Embedding queryEmbedding = Embedding.from(new float[]{1f, 0f, 0f});
        EmbeddingMatch<TextSegment> match =
                new EmbeddingMatch<>(0.42, "vec-1", queryEmbedding, TextSegment.from(DOC));
        when(cosineNative.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of(match)));

        EmbeddingSearchRequest given = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(5)
                .minScore(0.3)
                .build();

        EmbeddingSearchResult<TextSegment> result =
                ScoreNormalizingEmbeddingStore.wrap(cosineNative).search(given);

        ArgumentCaptor<EmbeddingSearchRequest> forwarded =
                ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(cosineNative).search(forwarded.capture());

        assertThat(forwarded.getValue().minScore())
                .as("Milvus 已是余弦口径，阈值原样 0.3；若被换成 0.65 就是重复换算")
                .isEqualTo(0.3);
        assertThat(forwarded.getValue().maxResults()).isEqualTo(5);
        assertThat(result.matches())
                .as("分数与命中对象都应原样返回（同一个实例，未被重建）")
                .containsExactly(match);
        assertThat(result.matches().get(0).score()).isEqualTo(0.42);
    }

    @Test
    @DisplayName("D3：重复包装不会把分数换算两次")
    void wrapIsIdempotent() {
        InMemoryEmbeddingStore<TextSegment> raw = new InMemoryEmbeddingStore<>();
        ScoreNormalizingEmbeddingStore once = ScoreNormalizingEmbeddingStore.wrap(raw);

        assertThat(ScoreNormalizingEmbeddingStore.wrap(once)).isSameAs(once);
        assertThat(ScoreNormalizingEmbeddingStore.wrap(once).delegate()).isSameAs(raw);
    }

    // ------------------------------------------------------------------
    // 三、删除能力必须真的委托下去
    // ------------------------------------------------------------------

    @Test
    @DisplayName("D3 附带风险：remove / removeAll 必须委托——接口里它们是 default，不覆盖就抛 UnsupportedOperationException")
    void removalIsDelegatedNotSwallowedByInterfaceDefaults() {
        DeterministicEmbeddingModel model = new DeterministicEmbeddingModel();
        InMemoryEmbeddingStore<TextSegment> raw = new InMemoryEmbeddingStore<>();
        String id = raw.add(model.embedText(DOC), TextSegment.from(DOC));

        ScoreNormalizingEmbeddingStore store = ScoreNormalizingEmbeddingStore.wrap(raw);
        Embedding query = model.embedText(DOC);

        assertThat(matches(store, query, 0.99)).hasSize(1);

        // DocumentService.deleteVectorFromEmbeddingStore 走的就是这一句；
        // 它自身的 catch (Exception) 会把异常吞成一行 warn，所以这里必须断言「不抛」+「真的删掉了」。
        assertThatCode(() -> store.remove(id))
                .as("remove 未委托时这里会抛 UnsupportedOperationException，且调用方会把异常吞掉")
                .doesNotThrowAnyException();
        assertThat(matches(store, query, 0.99))
                .as("删除必须真的落到代理上，否则删知识库会留下孤儿向量")
                .isEmpty();

        assertThatCode(store::removeAll).doesNotThrowAnyException();

        EmbeddingStore<TextSegment> mockDelegate = mock(EmbeddingStore.class);
        ScoreNormalizingEmbeddingStore onMock = ScoreNormalizingEmbeddingStore.wrap(mockDelegate);
        Filter filter = metadataKey("source").isEqualTo("x");
        onMock.remove("x");
        onMock.removeAll(List.of("x", "y"));
        onMock.removeAll(filter);
        verify(mockDelegate).remove("x");
        verify(mockDelegate).removeAll(List.of("x", "y"));
        // 四个 remove 重载必须一个不漏：它们在接口里是 default（抛 UnsupportedOperationException），
        // 漏掉哪一个都是**运行时**才炸的静默缺陷——而 add / addAll 的 5 个是抽象方法，
        // 漏掉是编译错误，所以不需要逐个测。
        verify(mockDelegate).removeAll(filter);
    }

    @Test
    @DisplayName("D3：写入路径也是纯委托（上传文档不能因为包了一层就写不进去）")
    void writeIsDelegated() {
        DeterministicEmbeddingModel model = new DeterministicEmbeddingModel();
        ScoreNormalizingEmbeddingStore store =
                ScoreNormalizingEmbeddingStore.wrap(new InMemoryEmbeddingStore<>());

        Embedding embedding = model.embedText(DOC);
        assertThat(matches(store, embedding, 0.99)).isEmpty();

        store.add(embedding, TextSegment.from(DOC));
        assertThat(matches(store, embedding, 0.99))
                .as("add(Embedding, TextSegment) 必须落到代理上")
                .hasSize(1);

        store.addAll(List.of(embedding), List.of(TextSegment.from(DOC + "（第二段）")));
        assertThat(matches(store, embedding, 0.99)).hasSize(2);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static List<EmbeddingMatch<TextSegment>> matches(EmbeddingStore<TextSegment> store,
                                                             Embedding query, double minScore) {
        return store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(query)
                .maxResults(20)
                .minScore(minScore)
                .build()).matches();
    }

    private static float dot(Embedding left, Embedding right) {
        float[] l = left.vector();
        float[] r = right.vector();
        float sum = 0f;
        for (int i = 0; i < l.length; i++) {
            sum += l[i] * r[i];
        }
        return sum;
    }
}
