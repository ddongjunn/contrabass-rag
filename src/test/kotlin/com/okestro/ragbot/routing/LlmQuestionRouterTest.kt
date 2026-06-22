package com.okestro.ragbot.routing

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.routing.application.LlmQuestionRouter
import com.okestro.ragbot.routing.domain.ConversationMessage
import com.okestro.ragbot.routing.domain.ConversationMessage.Role
import com.okestro.ragbot.routing.domain.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmQuestionRouterTest {

    private val props = AppProperties.Router(minConfidence = 0.5, historyTurns = 2)
    private fun routerWith(response: String) =
        LlmQuestionRouter(StubLlmClient(response), AppProperties(router = props), jacksonObjectMapper())

    private fun ask(text: String) = listOf(ConversationMessage(Role.USER, text))

    @Test
    fun `정상 JSON이면 해당 route로 매핑한다`() {
        val r = routerWith("""{"route":"DOC","confidence":0.9,"reason":"가이드"}""")
        val d = r.route(ask("임베딩 설정 어떻게 해?"))
        assertEquals(Route.DOC, d.route)
        assertEquals(0.9, d.confidence)
    }

    @Test
    fun `confidence가 임계값 미만이면 CLARIFY로 폴백한다`() {
        val r = routerWith("""{"route":"RESOURCE","confidence":0.2,"reason":"불확실"}""")
        val d = r.route(ask("그거 보여줘"))
        assertEquals(Route.CLARIFY, d.route)
    }

    @Test
    fun `깨진 JSON이면 CLARIFY로 폴백한다`() {
        val r = routerWith("이건 JSON이 아니다")
        val d = r.route(ask("아무거나"))
        assertEquals(Route.CLARIFY, d.route)
        assertTrue(d.reason.contains("parse"))
    }

    @Test
    fun `알 수 없는 route 값이면 CLARIFY로 폴백한다`() {
        val r = routerWith("""{"route":"FOO","confidence":0.9,"reason":"x"}""")
        val d = r.route(ask("아무거나"))
        assertEquals(Route.CLARIFY, d.route)
    }

    @Test
    fun `최근 N턴만 LLM에 전달한다 - 맥락 의존 케이스`() {
        val stub = StubLlmClient("""{"route":"RESOURCE","confidence":0.9,"reason":"맥락"}""")
        val router = LlmQuestionRouter(stub, AppProperties(router = props), jacksonObjectMapper())
        val history = listOf(
            ConversationMessage(Role.USER, "인스턴스 목록 보여줘"),
            ConversationMessage(Role.ASSISTANT, "1) web-01 2) web-02"),
            ConversationMessage(Role.USER, "1번 상세 알려줘"),
        )
        val d = router.route(history)
        assertEquals(Route.RESOURCE, d.route)
        // historyTurns=2 → 마지막 2개만 전달
        assertEquals(2, stub.lastRequest!!.messages.size)
        assertEquals("1번 상세 알려줘", stub.lastRequest!!.messages.last().content)
    }
}
