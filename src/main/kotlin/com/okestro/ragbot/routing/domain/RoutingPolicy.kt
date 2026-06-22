package com.okestro.ragbot.routing.domain

/**
 * 결정 후처리 정책(순수 함수). LLM이 낮은 confidence를 줬으면 안전하게 CLARIFY로 내린다.
 * 이미 CLARIFY면 그대로 둔다.
 */
object RoutingPolicy {
    fun applyConfidenceFloor(decision: RouteDecision, minConfidence: Double): RouteDecision =
        if (decision.route != Route.CLARIFY && decision.confidence < minConfidence) {
            RouteDecision(
                route = Route.CLARIFY,
                confidence = decision.confidence,
                reason = "low_confidence(${decision.confidence}) < $minConfidence; orig=${decision.route}",
            )
        } else {
            decision
        }
}
