package com.okestro.ragbot.embedding.infrastructure

import com.okestro.ragbot.embedding.application.EmbeddingService
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.stereotype.Component

/**
 * Spring AI 자동구성 [EmbeddingModel](yml `spring.ai.openai.embedding.options.model` =
 * text-embedding-3-small)에 1회 위임한다. 모델·차원은 yml 단일 소스 — 여기서 하드코딩하지 않는다.
 */
@Component
class OpenAiEmbeddingClient(
    private val embeddingModel: EmbeddingModel,
) : EmbeddingService {
    override fun embed(text: String): FloatArray = embeddingModel.embed(text)
}
