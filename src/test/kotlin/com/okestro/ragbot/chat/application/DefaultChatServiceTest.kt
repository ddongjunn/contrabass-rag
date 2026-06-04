package com.okestro.ragbot.chat.application

import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.embedding.application.EmbeddingService
import com.okestro.ragbot.retrieval.application.DocumentSearchService
import com.okestro.ragbot.retrieval.domain.RetrievalPolicy
import com.okestro.ragbot.retrieval.domain.RetrievedChunk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Phase 2-b: 임베딩 1회 → 검색 → top-k 출처(생성 없음) 파이프라인 배선 검증. 외부 호출 없이 페이크로만. */
class DefaultChatServiceTest {
    private class FakeEmbedding(private val vector: FloatArray) : EmbeddingService {
        var calls = 0
        override fun embed(text: String): FloatArray { calls++; return vector }
    }

    private class FakeSearch(private val chunks: List<RetrievedChunk>) : DocumentSearchService {
        var receivedVector: FloatArray? = null
        var receivedTopK: Int? = null
        override fun search(queryVector: FloatArray, topK: Int): List<RetrievedChunk> {
            receivedVector = queryVector; receivedTopK = topK; return chunks
        }
    }

    @Test
    fun `embeds once, searches with that vector and topK, returns formatted sources without generation`() {
        val vector = FloatArray(1536) { 0.1f }
        val embedding = FakeEmbedding(vector)
        val search = FakeSearch(
            listOf(
                RetrievedChunk("c1", "가이드", null, 2, 0.91),
                RetrievedChunk("c2", "가이드", 5, 8, 0.80),
            ),
        )
        val props = AppProperties(retrieval = AppProperties.Retrieval(topK = 5))
        val service = DefaultChatService(embedding, search, RetrievalPolicy(props), props)

        val result = service.handle(ChatCommand(question = "질문", userId = "u1"))

        assertThat(embedding.calls).isEqualTo(1)                 // 불변식 2: 1회만
        assertThat(search.receivedVector).isSameAs(vector)       // 재임베딩 없이 그 벡터 재사용
        assertThat(search.receivedTopK).isEqualTo(5)             // yml top-k
        assertThat(result.sources).containsExactly("가이드 #2", "가이드 p.5 #8")
        assertThat(result.answer).contains("2건").doesNotContainIgnoringCase("stub")
    }
}
