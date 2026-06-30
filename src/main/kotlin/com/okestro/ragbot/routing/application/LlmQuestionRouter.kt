package com.okestro.ragbot.routing.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.routing.domain.Route
import com.okestro.ragbot.routing.domain.RouteDecision
import com.okestro.ragbot.routing.domain.RoutingPolicy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 기본 라우터 구현. 프롬프트 구성 → LlmClient 호출 → JSON 파싱 → 신뢰도 폴백 → 로깅.
 * 파싱 실패·빈 응답·알 수 없는 enum → CLARIFY 안전 폴백(불변식: 모호하면 되묻는다).
 * 모든 결정을 route·confidence·reason·latencyMs로 로깅한다(추후 메트릭).
 */
@Service
class LlmQuestionRouter(
    private val llmClient: LlmClient,
    private val properties: AppProperties,
    private val objectMapper: ObjectMapper,
) : QuestionRouter {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cfg get() = properties.router

    override fun route(history: List<ConversationMessage>): RouteDecision {
        val recent = history.takeLast(cfg.historyTurns)
        val request = LlmRequest(
            system = RoutingPrompts.SYSTEM,
            messages = recent,
            model = cfg.model,
            temperature = cfg.temperature,
            jsonSchema = RoutingPrompts.SCHEMA,
        )

        val start = System.nanoTime()
        val decision = try {
            val raw = llmClient.complete(request)
            val parsed = objectMapper.readValue(raw, RawDecision::class.java)
            RoutingPolicy.applyConfidenceFloor(
                RouteDecision(Route.valueOf(parsed.route), parsed.confidence, parsed.reason),
                cfg.minConfidence,
            )
        } catch (e: Exception) {
            RouteDecision(Route.CLARIFY, 0.0, "parse_failed: ${e.javaClass.simpleName}")
        }
        val latencyMs = (System.nanoTime() - start) / 1_000_000

        log.info(
            "routing decision route={} confidence={} reason={} latencyMs={}",
            decision.route, decision.confidence, decision.reason, latencyMs,
        )
        return decision
    }

    /** LLM 원시 JSON 매핑용. route는 String으로 받아 valueOf에서 검증(알 수 없으면 예외 → CLARIFY). */
    private data class RawDecision(
        val route: String = "",
        val confidence: Double = 0.0,
        val reason: String = "",
    )
}
