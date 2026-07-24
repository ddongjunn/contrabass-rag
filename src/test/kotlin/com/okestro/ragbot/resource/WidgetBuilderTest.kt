package com.okestro.ragbot.resource

import com.okestro.ragbot.resource.application.WidgetBuilder
import com.okestro.ragbot.resource.domain.LabeledSample
import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.MetricSample
import com.okestro.ragbot.resource.domain.RangeSeries
import com.okestro.ragbot.resource.domain.TimePoint
import com.okestro.ragbot.resource.domain.TrendQuery
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

    // ── threshold_banner (1b) ──────────────────────────────────────────────────

    @Test
    fun `초과 있으면 CRIT 배너와 대수`() {
        val w = WidgetBuilder.thresholdBanner(count = 2, critPercent = 85)
        assertEquals(Severity.CRIT, w.level)
        assertEquals(2, w.count)
        assertEquals("CPU 85% 초과 인스턴스 2대", w.title)
    }

    @Test
    fun `초과 0대면 GOOD 배너 - 안심 정보지 경고가 아니다`() {
        // 실측(2026-07-15): 최고 CPU가 34.78%라 crit=85 초과는 0건 — 이게 지금 흔한 경우다.
        val w = WidgetBuilder.thresholdBanner(count = 0, critPercent = 85)
        assertEquals(Severity.GOOD, w.level)
        assertEquals(0, w.count)
        assertEquals("CPU 85% 초과 인스턴스 없음", w.title)
        assertNull(w.detail, "초과가 없으면 나열할 것도 없다")
    }

    @Test
    fun `level은 대문자 Severity - status_donut(소문자)과 반대`() {
        // 프론트 threshold-banner.js의 LEVEL_CLASS는 대문자 키(CRIT/WARN/GOOD)를 쓴다.
        // 도넛의 DonutLevel(소문자)과 헷갈리면 배너가 통째로 "info"로 떨어진다.
        assertEquals("CRIT", WidgetBuilder.thresholdBanner(1, 85).level.name)
    }

    @Test
    fun `offenders가 있으면 detail에 나열`() {
        val w = WidgetBuilder.thresholdBanner(2, 85, listOf("web-prod-07", "api-prod-02"))
        assertEquals("CPU 85%↑ : web-prod-07, api-prod-02", w.detail)
    }

    @Test
    fun `offenders 비면 detail null - 프론트가 있을 때만 그린다`() {
        assertNull(WidgetBuilder.thresholdBanner(2, 85).detail)
    }

    // ── metric_line (TREND) ─────────────────────────────────────────────────

    private fun rangeSeries(name: String, vararg values: Double) = RangeSeries(
        labels = mapOf("instance_name" to name, "project_name" to "prod"),
        points = values.mapIndexed { i, v -> TimePoint(1_700_000_000L + i * 60, v) },
    )

    @Test
    fun `metricLine - 시리즈 이름은 instance_name, 포인트 보존`() {
        val w = WidgetBuilder.metricLine(
            TrendQuery(MetricPattern.INSTANCE_CPU), listOf(rangeSeries("web-01", 10.0, 20.0)),
            promql = "expr", unit = "%", maxSeries = 5,
        )
        assertEquals("metric_line", w.type)
        assertEquals("CPU 사용률 추이", w.title)
        assertEquals("web-01", w.series.single().name)
        assertEquals("prod", w.series.single().projectName)
        assertEquals(listOf(10.0, 20.0), w.series.single().points.map { it.value })
        assertTrue(!w.empty)
    }

    @Test
    fun `metricLine - maxSeries 초과 시 마지막 값 큰 순으로 자른다`() {
        val w = WidgetBuilder.metricLine(
            TrendQuery(MetricPattern.INSTANCE_CPU),
            listOf(rangeSeries("low", 1.0, 2.0), rangeSeries("high", 1.0, 90.0), rangeSeries("mid", 1.0, 50.0)),
            promql = "expr", unit = "%", maxSeries = 2,
        )
        assertEquals(listOf("high", "mid"), w.series.map { it.name })
    }

    @Test
    fun `metricLine - instance_name 없으면 domain으로 폴백, 둘 다 없으면 버린다`() {
        val w = WidgetBuilder.metricLine(
            TrendQuery(MetricPattern.INSTANCE_CPU),
            listOf(
                RangeSeries(mapOf("domain" to "instance-0007"), listOf(TimePoint(1L, 1.0))),
                RangeSeries(mapOf("other" to "x"), listOf(TimePoint(1L, 2.0))),
            ),
            promql = "expr", unit = "%", maxSeries = 5,
        )
        assertEquals(listOf("instance-0007"), w.series.map { it.name })
    }

    @Test
    fun `metricLine - 시리즈 0건이면 empty`() {
        val w = WidgetBuilder.metricLine(
            TrendQuery(MetricPattern.INSTANCE_CPU), emptyList(), promql = "expr", unit = "%", maxSeries = 5,
        )
        assertTrue(w.empty)
    }
}
