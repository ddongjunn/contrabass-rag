package com.okestro.ragbot.chat.application

import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.embedding.application.EmbeddingService
import com.okestro.ragbot.generation.application.AnswerGenerationService
import com.okestro.ragbot.guard.application.InputGuard
import com.okestro.ragbot.guard.application.RateLimitGuard
import com.okestro.ragbot.guard.domain.GuardDecision
import com.okestro.ragbot.resource.application.ResourceService
import com.okestro.ragbot.retrieval.application.DocumentSearchService
import com.okestro.ragbot.retrieval.domain.RetrievalPolicy
import com.okestro.ragbot.retrieval.domain.Source
import com.okestro.ragbot.routing.application.QuestionRouter
import com.okestro.ragbot.routing.domain.Route
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 파이프라인: 레이트리밋 → 입력 가드(차단 시 단락) → 라우팅 → when(route) 분기
 *   DOC      → 임베딩 1회 → documents 검색 → 생성 (불변식 2·3·4)
 *   RESOURCE → MetricQueryExtractor → PromQL → Prometheus → 템플릿 답변 (LLM 무호출)
 *   CLARIFY  → 되물음 응답 (유료호출 0)
 */
@Service
class DefaultChatService(
    private val rateLimitGuard: RateLimitGuard,
    private val inputGuard: InputGuard,
    private val router: QuestionRouter,
    private val embeddingService: EmbeddingService,
    private val documentSearch: DocumentSearchService,
    private val retrievalPolicy: RetrievalPolicy,
    private val answerGeneration: AnswerGenerationService,
    private val resourceService: ResourceService,
    private val props: AppProperties,
) : ChatService {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(command: ChatCommand): ChatResult {
        if (!rateLimitGuard.tryAcquire(command.userId)) {
            log.info("chat rate-limited userId={} routingCalls=0 llmCalls=0", command.userId)
            return ChatResult(answer = "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.", sources = emptyList())
        }
        val guardDecision = inputGuard.inspect(command.question)
        if (guardDecision is GuardDecision.Blocked) {
            log.info("chat blocked userId={} reason={} routingCalls=0 llmCalls=0", command.userId, guardDecision.reason)
            return ChatResult(answer = guardDecision.message, sources = emptyList())
        }

        val history = command.history + ConversationMessage(ConversationMessage.Role.USER, command.question)
        val routeDecision = router.route(history)
        log.info("chat routed userId={} route={} confidence={}", command.userId, routeDecision.route, "%.2f".format(routeDecision.confidence))

        return when (routeDecision.route) {
            Route.DOC      -> handleDoc(command)
            Route.RESOURCE -> handleResource(history, command.userId)
            Route.CLARIFY  -> {
                log.info("chat clarify userId={} routingCalls=1 llmCalls=0", command.userId)
                ChatResult(
                    answer = "질문의 유형을 파악하기 어렵습니다. 문서 기반 질문(사용법·API 문서 등)인지, 인프라 지표 조회(CPU·메모리·네트워크 사용량 등)인지 구체적으로 알려주세요.",
                    sources = emptyList(),
                )
            }
        }
    }

    private fun handleDoc(command: ChatCommand): ChatResult {
        val queryVector = embeddingService.embed(command.question)
        val retrieved = documentSearch.search(queryVector, props.retrieval.topK)
        log.info("retrieval userId={} scores={} minScore={}", command.userId, retrieved.map { "%.3f".format(it.score) }, props.retrieval.minScore)
        val chunks = retrievalPolicy.apply(retrieved)
        if (chunks.isEmpty()) {
            log.info("chat doc no-doc userId={} routingCalls=1 embeddingCalls=1 llmCalls=0", command.userId)
            return ChatResult(answer = "관련 문서를 찾지 못했습니다.", sources = emptyList())
        }
        val answer = answerGeneration.generate(command.question, chunks)
        val sources = chunks.map { Source.of(it).display() }
        log.info("chat doc answered userId={} routingCalls=1 embeddingCalls=1 llmCalls=1 sources={}", command.userId, sources.size)
        return ChatResult(answer = answer, sources = sources)
    }

    private fun handleResource(history: List<ConversationMessage>, userId: String): ChatResult {
        val result = resourceService.handle(history)
        val callType = if (result.needsClarification) "clarify" else "answered"
        log.info("chat resource-{} userId={} routingCalls=1 extractionCalls=1 llmCalls=0", callType, userId)
        return ChatResult(answer = result.answer, sources = emptyList())
    }
}
