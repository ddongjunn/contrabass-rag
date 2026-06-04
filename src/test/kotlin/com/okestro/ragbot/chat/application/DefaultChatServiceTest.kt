package com.okestro.ragbot.chat.application

import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.embedding.application.EmbeddingService
import com.okestro.ragbot.generation.application.AnswerGenerationService
import com.okestro.ragbot.retrieval.application.DocumentSearchService
import com.okestro.ragbot.retrieval.domain.RetrievalPolicy
import com.okestro.ragbot.retrieval.domain.RetrievedChunk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Phase 3: 임베딩 1회 → 검색 → 생성 → top-k 출처 배선, 검색 0건 시 생성 0회(불변식 3). 페이크로만. */
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

    private class FakeGeneration(private val answer: String) : AnswerGenerationService {
        var calls = 0
        var receivedQuestion: String? = null
        var receivedChunks: List<RetrievedChunk>? = null
        override fun generate(question: String, chunks: List<RetrievedChunk>): String {
            calls++; receivedQuestion = question; receivedChunks = chunks; return answer
        }
    }

    private val props = AppProperties(retrieval = AppProperties.Retrieval(topK = 5))

    @Test
    fun `embeds once, searches, generates from chunks, returns answer with formatted sources`() {
        val vector = FloatArray(1536) { 0.1f }
        val embedding = FakeEmbedding(vector)
        val chunks = listOf(
            RetrievedChunk("c1", "가이드", null, 2, 0.91),
            RetrievedChunk("c2", "가이드", 5, 8, 0.80),
        )
        val search = FakeSearch(chunks)
        val generation = FakeGeneration("근거 기반 답변")
        val service = DefaultChatService(embedding, search, RetrievalPolicy(props), generation, props)

        val result = service.handle(ChatCommand(question = "질문", userId = "u1"))

        assertThat(embedding.calls).isEqualTo(1)              // 불변식 2
        assertThat(search.receivedVector).isSameAs(vector)
        assertThat(search.receivedTopK).isEqualTo(5)
        assertThat(generation.calls).isEqualTo(1)             // 검색 성공 → 생성 1회
        assertThat(generation.receivedQuestion).isEqualTo("질문")
        assertThat(generation.receivedChunks).isEqualTo(chunks)
        assertThat(result.answer).isEqualTo("근거 기반 답변")
        assertThat(result.sources).containsExactly("가이드 #2", "가이드 p.5 #8")
    }

    @Test
    fun `empty retrieval skips generation and returns not-found (불변식 3)`() {
        val generation = FakeGeneration("호출되면 안 됨")
        val service = DefaultChatService(
            FakeEmbedding(FloatArray(1536)), FakeSearch(emptyList()), RetrievalPolicy(props), generation, props,
        )

        val result = service.handle(ChatCommand(question = "질문", userId = "u1"))

        assertThat(generation.calls).isEqualTo(0)             // 생성 0회
        assertThat(result.answer).isEqualTo("관련 문서를 찾지 못했습니다.")
        assertThat(result.sources).isEmpty()
    }
}
