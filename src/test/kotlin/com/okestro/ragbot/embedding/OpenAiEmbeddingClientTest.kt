package com.okestro.ragbot.embedding

import com.okestro.ragbot.embedding.infrastructure.OpenAiEmbeddingClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse

/** Phase 2-a: 임베딩 1회 위임·결과 전달 검증. 외부(OpenAI) 호출 없이 EmbeddingModel 페이크로만. */
class OpenAiEmbeddingClientTest {
    /** embed(String) 호출만 기록하는 최소 페이크 — 외부 호출 없음. */
    private class FakeEmbeddingModel(private val vector: FloatArray) : EmbeddingModel {
        var calls = 0
        var lastText: String? = null

        override fun embed(text: String): FloatArray {
            calls++
            lastText = text
            return vector
        }

        override fun call(request: EmbeddingRequest): EmbeddingResponse = throw UnsupportedOperationException()
        override fun embed(document: Document): FloatArray = throw UnsupportedOperationException()
    }

    @Test
    fun `delegates to EmbeddingModel once and returns its vector`() {
        val expected = FloatArray(1536) { it.toFloat() }
        val fake = FakeEmbeddingModel(expected)

        val result = OpenAiEmbeddingClient(fake).embed("질문")

        assertThat(result).isSameAs(expected)
        assertThat(fake.calls).isEqualTo(1)
        assertThat(fake.lastText).isEqualTo("질문")
    }
}
