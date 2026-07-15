package com.okestro.ragbot.resource

import com.okestro.ragbot.resource.application.WidgetBuilder
import com.okestro.ragbot.resource.domain.LabeledSample
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

    // ── status_donut (1b) ──────────────────────────────────────────────────────

    /** count by(status)(...) 결과 한 건. 라벨 status + 개수. */
    private fun st(status: String, count: Int) = LabeledSample(mapOf("status" to status), count.toDouble())

    @Test
    fun `status 라벨별 세그먼트 생성, total은 합계`() {
        // 라이브 실측(2026-07-09): ACTIVE=116, SHUTOFF=2, ERROR=1
        val w = WidgetBuilder.statusDonut(listOf(st("ACTIVE", 116), st("SHUTOFF", 2), st("ERROR", 1)))
        assertEquals(119, w.total)
        assertEquals(3, w.segments.size)
        assertEquals("인스턴스", w.label)
        assertEquals(116, w.segments.single { it.status == "ACTIVE" }.count)
    }

    @Test
    fun `level은 소문자 - ACTIVE good, ERROR crit, SHUTOFF muted`() {
        // 프론트 status-donut.js의 LEVEL_CLASS가 소문자 키만 가짐 —
        // Severity.name(대문자)을 넣으면 전부 seg-muted 회색으로 죽는다.
        val w = WidgetBuilder.statusDonut(listOf(st("ACTIVE", 3), st("ERROR", 2), st("SHUTOFF", 1)))
        assertEquals("good", w.segments.single { it.status == "ACTIVE" }.level)
        assertEquals("crit", w.segments.single { it.status == "ERROR" }.level)
        assertEquals("muted", w.segments.single { it.status == "SHUTOFF" }.level)
    }

    @Test
    fun `알 수 없는 status는 muted로 폴백`() {
        val w = WidgetBuilder.statusDonut(listOf(st("VERIFY_RESIZE", 1)))
        assertEquals("muted", w.segments.single().level)
    }

    @Test
    fun `세그먼트는 count 내림차순 - Prometheus 순서와 무관하게 안정적`() {
        val w = WidgetBuilder.statusDonut(listOf(st("ERROR", 1), st("ACTIVE", 116), st("SHUTOFF", 2)))
        assertEquals(listOf("ACTIVE", "SHUTOFF", "ERROR"), w.segments.map { it.status })
    }

    @Test
    fun `빈 samples → empty=true, total 0`() {
        val w = WidgetBuilder.statusDonut(emptyList())
        assertTrue(w.empty)
        assertEquals(0, w.total)
        assertTrue(w.segments.isEmpty())
    }

    @Test
    fun `status 라벨 없는 샘플은 제외`() {
        val w = WidgetBuilder.statusDonut(listOf(st("ACTIVE", 5), LabeledSample(emptyMap(), 9.0)))
        assertEquals(1, w.segments.size)
        assertEquals(5, w.total, "라벨 없는 9는 total에도 안 들어간다")
    }

    @Test
    fun `label 인자로 대상 이름을 바꿀 수 있다`() {
        val w = WidgetBuilder.statusDonut(listOf(st("available", 3)), label = "볼륨")
        assertEquals("볼륨", w.label)
    }
}
