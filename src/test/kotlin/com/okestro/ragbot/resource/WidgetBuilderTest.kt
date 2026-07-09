package com.okestro.ragbot.resource

import com.okestro.ragbot.resource.application.WidgetBuilder
import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.MetricSample
import com.okestro.ragbot.resource.domain.ResourceQuery
import com.okestro.ragbot.resource.domain.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BE-A: WidgetBuilder 순수 변환 — severity 경계·empty·단위 display·쿼터 무제한(설계 §5.1·§13). */
class WidgetBuilderTest {

    private val query = ResourceQuery(MetricPattern.INSTANCE_CPU)
    private fun pct(v: Double) = MetricSample("i", v, "%", "service-prod")

    @Test
    fun `severity 경계값 69-70-84-85 (warn=70 crit=85)`() {
        val w = WidgetBuilder.metricRank(
            query, listOf(pct(69.0), pct(70.0), pct(84.0), pct(85.0)),
            promql = "topk(5, ...)", unit = "%", warnPercent = 70, critPercent = 85,
        )
        assertEquals(Severity.GOOD, w.rows[0].severity, "69 → GOOD")
        assertEquals(Severity.WARN, w.rows[1].severity, "70 → WARN(경계 포함)")
        assertEquals(Severity.WARN, w.rows[2].severity, "84 → WARN")
        assertEquals(Severity.CRIT, w.rows[3].severity, "85 → CRIT(경계 포함)")
    }

    @Test
    fun `빈 samples → empty=true, rows 비어있음`() {
        val w = WidgetBuilder.metricRank(query, emptyList(), "topk(5, ...)", "%", 70, 85)
        assertTrue(w.empty)
        assertTrue(w.rows.isEmpty())
    }

    @Test
    fun `단위별 display 포맷과 severity 적용 범위`() {
        val pctWidget = WidgetBuilder.metricRank(query, listOf(pct(91.2)), "q", "%", 70, 85)
        assertEquals("91.2%", pctWidget.rows[0].display)
        assertEquals(Severity.CRIT, pctWidget.rows[0].severity)

        // B/s 지표: severity 없음(색상=액센트), 바이트 포맷
        val bytes = MetricSample("i", 429_916_160.0, "B/s", null) // 410 MiB/s
        val bpsWidget = WidgetBuilder.metricRank(query, listOf(bytes), "q", "B/s", 70, 85)
        assertEquals("410.00 MB/s", bpsWidget.rows[0].display)
        assertNull(bpsWidget.rows[0].severity)
    }

    @Test
    fun `쿼터 무제한(-1) → quota-ratio-severity null, display N 무제한`() {
        val item = WidgetBuilder.quotaItem("vCPU", used = 820.0, quota = -1.0, warnPercent = 70, critPercent = 85)
        assertNull(item.quota)
        assertNull(item.ratio)
        assertNull(item.severity)
        assertEquals("820 / 무제한", item.display)
    }

    @Test
    fun `쿼터 정상 한도 → ratio-severity 계산, display used 슬래시 quota`() {
        val item = WidgetBuilder.quotaItem("vCPU", used = 820.0, quota = 1000.0, warnPercent = 70, critPercent = 85)
        assertEquals(1000.0, item.quota)
        assertEquals(0.82, item.ratio!!, 1e-9)
        assertEquals(Severity.WARN, item.severity) // 82% ∈ [70,85)
        assertEquals("820 / 1000", item.display)
    }
}
