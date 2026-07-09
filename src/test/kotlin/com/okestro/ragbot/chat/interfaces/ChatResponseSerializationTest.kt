package com.okestro.ragbot.chat.interfaces

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.okestro.ragbot.resource.domain.MetricRankRow
import com.okestro.ragbot.resource.domain.MetricRankWidget
import com.okestro.ragbot.resource.domain.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BE-A: ChatResponse 직렬화 계약(설계 §4.1·§13.2) — widgets 기본 빈 배열,
 * `type` 판별자, 폴리모픽 round-trip(같은 subtype으로 역직렬화).
 */
class ChatResponseSerializationTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `widgets-followups 기본 빈 배열로 직렬화(하위호환)`() {
        val json = mapper.writeValueAsString(ChatResponse(answer = "평문", sources = emptyList()))
        val tree = mapper.readTree(json)

        assertTrue(tree.get("widgets").isArray && tree.get("widgets").isEmpty)
        assertTrue(tree.get("followups").isArray && tree.get("followups").isEmpty)
    }

    @Test
    fun `metric_rank 위젯 — type 판별자 방출 및 subtype으로 round-trip`() {
        val widget = MetricRankWidget(
            title = "CPU 사용률이 높은 인스턴스",
            unit = "%",
            window = "5m",
            promql = "topk(5, ...)",
            rows = listOf(MetricRankRow("web-prod-07", "service-prod", 91.2, "91.2%", Severity.CRIT)),
        )
        val response = ChatResponse(answer = "평문", sources = emptyList(), widgets = listOf(widget))

        val json = mapper.writeValueAsString(response)
        assertTrue(json.contains("\"type\":\"metric_rank\""), json)
        assertTrue(json.contains("\"severity\":\"CRIT\""), json)

        val back = mapper.readValue(json, ChatResponse::class.java)
        assertEquals(1, back.widgets.size)
        val w = back.widgets[0]
        assertTrue(w is MetricRankWidget)
        assertEquals("metric_rank", w.type)
        assertEquals(Severity.CRIT, w.rows[0].severity)
        assertEquals("web-prod-07", w.rows[0].instanceName)
    }
}
