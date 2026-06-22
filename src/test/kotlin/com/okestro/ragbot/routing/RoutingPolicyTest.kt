package com.okestro.ragbot.routing

import com.okestro.ragbot.routing.domain.Route
import com.okestro.ragbot.routing.domain.RouteDecision
import com.okestro.ragbot.routing.domain.RoutingPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutingPolicyTest {

    @Test
    fun `confidence가 임계값 미만이면 CLARIFY로 내린다`() {
        val d = RouteDecision(Route.DOC, 0.3, "doc-ish")
        val result = RoutingPolicy.applyConfidenceFloor(d, minConfidence = 0.5)
        assertEquals(Route.CLARIFY, result.route)
        assertEquals(0.3, result.confidence)
    }

    @Test
    fun `confidence가 임계값 이상이면 그대로 둔다`() {
        val d = RouteDecision(Route.RESOURCE, 0.9, "metric")
        val result = RoutingPolicy.applyConfidenceFloor(d, minConfidence = 0.5)
        assertEquals(Route.RESOURCE, result.route)
    }

    @Test
    fun `이미 CLARIFY면 임계값 미만이어도 그대로 둔다`() {
        val d = RouteDecision(Route.CLARIFY, 0.1, "ambiguous")
        val result = RoutingPolicy.applyConfidenceFloor(d, minConfidence = 0.5)
        assertEquals(Route.CLARIFY, result.route)
        assertEquals("ambiguous", result.reason)
    }
}
