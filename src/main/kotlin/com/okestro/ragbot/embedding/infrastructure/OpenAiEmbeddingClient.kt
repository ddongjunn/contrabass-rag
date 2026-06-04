package com.okestro.ragbot.embedding.infrastructure

import com.okestro.ragbot.embedding.application.EmbeddingService
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.stereotype.Component

/**
 * Spring AI 자동구성 [EmbeddingModel](yml `spring.ai.openai.embedding.options.model` =
 * text-embedding-3-small)에 1회 위임한다. 모델·차원은 yml 단일 소스 — 여기서 하드코딩하지 않는다.
 * OpenAI 호출은 Resilience4j `openai` 인스턴스로 Retry(429/5xx)+CircuitBreaker 보호(설정 resilience4j.*).
 */
@Component
class OpenAiEmbeddingClient(
    private val embeddingModel: EmbeddingModel,
) : EmbeddingService {
    @Retry(name = "openai")
    @CircuitBreaker(name = "openai")
    override fun embed(text: String): FloatArray = embeddingModel.embed(text)
}
