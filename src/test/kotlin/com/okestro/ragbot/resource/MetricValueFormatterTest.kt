package com.okestro.ragbot.resource

import com.okestro.ragbot.resource.application.MetricValueFormatter
import com.okestro.ragbot.resource.application.ResourceAnswerTemplate
import com.okestro.ragbot.resource.application.WidgetBuilder
import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.MetricSample
import com.okestro.ragbot.resource.domain.ResourceQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BE-A: 포맷 단일화 회귀(설계 §5.2) — answer(Slack)와 widget(웹)이 동일 문자열을 공유하는지. */
class MetricValueFormatterTest {

    @Test
    fun `answer와 widget이 동일한 display 문자열을 공유(드리프트 방지)`() {
        val query = ResourceQuery(MetricPattern.INSTANCE_CPU)
        val samples = listOf(MetricSample("web-01", 91.234, "%", "service-prod"))

        val answer = ResourceAnswerTemplate.build(query, samples)
        val widget = WidgetBuilder.metricRank(query, samples, "q", "%", 70, 85)
        val display = widget.rows[0].display

        assertEquals("91.2%", display)
        assertTrue(answer.contains(display), "평문 answer가 위젯과 동일한 '$display' 를 포함")
    }

    @Test
    fun `MB 단위는 1024 이상에서 GB로 — 51200 MB → 50 GB`() {
        assertEquals("50 GB", MetricValueFormatter.format(51_200.0, "MB"))
        assertEquals("512 MB", MetricValueFormatter.format(512.0, "MB"))
    }
}
