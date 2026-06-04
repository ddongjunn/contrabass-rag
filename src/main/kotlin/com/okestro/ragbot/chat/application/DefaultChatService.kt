package com.okestro.ragbot.chat.application

import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.embedding.application.EmbeddingService
import com.okestro.ragbot.generation.application.AnswerGenerationService
import com.okestro.ragbot.guard.application.InputGuard
import com.okestro.ragbot.guard.application.RateLimitGuard
import com.okestro.ragbot.guard.domain.GuardDecision
import com.okestro.ragbot.retrieval.application.DocumentSearchService
import com.okestro.ragbot.retrieval.domain.RetrievalPolicy
import com.okestro.ragbot.retrieval.domain.Source
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 파이프라인: 레이트리밋 → 입력 가드(차단 시 즉시 단락) → 질문 임베딩(1회, 불변식 2) → documents 검색 →
 * 정책 필터 → 생성 → top-k 출처. 레이트리밋 초과·가드 차단·검색 0건 시 임베딩/생성 0회(불변식 3·4).
 * 캐시는 Phase 4(고도화 보류)에서 임베딩 직후에 삽입한다.
 * 케이스별 OpenAI 호출수(embedding·llm)는 INFO 로그로 남겨 비용/불변식을 검증한다.
 */
@Service
class DefaultChatService(
    private val rateLimitGuard: RateLimitGuard,
    private val inputGuard: InputGuard,
    private val embeddingService: EmbeddingService,
    private val documentSearch: DocumentSearchService,
    private val retrievalPolicy: RetrievalPolicy,
    private val answerGeneration: AnswerGenerationService,
    private val props: AppProperties,
) : ChatService {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(command: ChatCommand): ChatResult {
        if (!rateLimitGuard.tryAcquire(command.userId)) {
            log.info("chat rate-limited userId={} embeddingCalls=0 llmCalls=0", command.userId)
            return ChatResult(answer = "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.", sources = emptyList())
        }
        val decision = inputGuard.inspect(command.question)
        if (decision is GuardDecision.Blocked) {
            log.info("chat blocked userId={} reason={} embeddingCalls=0 llmCalls=0", command.userId, decision.reason)
            return ChatResult(answer = decision.message, sources = emptyList())
        }
        val queryVector = embeddingService.embed(command.question)
        val retrieved = documentSearch.search(queryVector, props.retrieval.topK)
        log.info(
            "retrieval userId={} scores={} minScore={}",
            command.userId, retrieved.map { "%.3f".format(it.score) }, props.retrieval.minScore,
        )
        val chunks = retrievalPolicy.apply(retrieved)
        if (chunks.isEmpty()) {
            log.info("chat no-doc userId={} embeddingCalls=1 llmCalls=0", command.userId)
            return ChatResult(answer = "관련 문서를 찾지 못했습니다.", sources = emptyList())
        }
        val answer = answerGeneration.generate(command.question, chunks)
        val sources = chunks.map { Source.of(it).display() }
        log.info("chat answered userId={} embeddingCalls=1 llmCalls=1 sources={}", command.userId, sources.size)
        return ChatResult(answer = answer, sources = sources)
    }
}
