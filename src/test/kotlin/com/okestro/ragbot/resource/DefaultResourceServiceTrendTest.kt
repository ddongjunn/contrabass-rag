package com.okestro.ragbot.resource

import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.chat.domain.ConversationMessage.Role
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.resource.application.DefaultResourceService
import com.okestro.ragbot.resource.application.InventoryRepository
import com.okestro.ragbot.resource.application.MetricCatalog
import com.okestro.ragbot.resource.application.MetricQueryExtractor
import com.okestro.ragbot.resource.application.PrometheusClient
import com.okestro.ragbot.resource.domain.LabeledSample
import com.okestro.ragbot.resource.domain.MetricLineWidget
import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.MetricSample
import com.okestro.ragbot.resource.domain.RangeSeries
import com.okestro.ragbot.resource.domain.ResourceExtraction
import com.okestro.ragbot.resource.domain.TimePoint
import com.okestro.ragbot.resource.domain.TrendQuery
import org.springframework.beans.factory.ObjectProvider
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * TREND 트랙 — 추출기가 TrendResolved를 냈을 때 서비스가 뭘 하는가.
 * "그 질문이 TREND로 분류되는가"는 별개 관심사다(MetricExtractionAccuracyTest, 실 OpenAI).
 */
class DefaultResourceServiceTrendTest {

    /** METRIC 트랙과 같은 이유로 카탈로그가 필요하다(promql은 카탈로그에서 조립). */
    private val props = AppProperties(
        resource = AppProperties.Resource(
            catalog = mapOf(
                "INSTANCE_CPU" to AppProperties.Resource.CatalogEntryConfig(
                    pattern = "RATIO_TOPK", rawMetric = "libvirt_domain_info_cpu_time_seconds_total", unit = "%",
                ),
            ),
        ),
    )

    private class FixedExtractor(private val out: ResourceExtraction) : MetricQueryExtractor {
        override fun extract(history: List<ConversationMessage>): ResourceExtraction = out
    }

    private class StubPrometheus(private val series: List<RangeSeries>) : PrometheusClient {
        var lastPromql: String? = null
        var lastStart: Instant? = null
        var lastEnd: Instant? = null
        var lastStep: Duration? = null
        override fun query(promql: String, unit: String): List<MetricSample> = emptyList()
        override fun queryLabeled(promql: String): List<LabeledSample> = emptyList()
        override fun queryRange(promql: String, start: Instant, end: Instant, step: Duration): List<RangeSeries> {
            lastPromql = promql
            lastStart = start
            lastEnd = end
            lastStep = step
            return series
        }
    }

    private fun handle(
        prom: PrometheusClient,
        query: TrendQuery = TrendQuery(MetricPattern.INSTANCE_CPU),
        properties: AppProperties = props,
    ) = DefaultResourceService(
        FixedExtractor(ResourceExtraction.TrendResolved(query)), MetricCatalog(properties), prom, emptyProvider(), properties,
    ).handle(listOf(ConversationMessage(Role.USER, "CPU 사용률 추이 보여줘")))

    private fun series(name: String, vararg values: Double): RangeSeries =
        RangeSeries(
            labels = mapOf("instance_name" to name, "project_name" to "prod"),
            points = values.mapIndexed { i, v -> TimePoint(1_700_000_000L + i * 60, v) },
        )

    @Test
    fun `TrendResolved면 metric_line 위젯과 평문 answer`() {
        val out = handle(StubPrometheus(listOf(series("web-01", 10.0, 20.0), series("web-02", 5.0, 15.0))))

        val widget = assertIs<MetricLineWidget>(out.widgets.single())
        assertEquals("1h", widget.range)
        assertEquals(2, widget.series.size)
        assertEquals("web-01", widget.series.first().name)
        assertEquals(listOf(10.0, 20.0), widget.series.first().points.map { it.value })
        assertTrue(out.answer.contains("추이"), out.answer)
    }

    @Test
    fun `조회 구간과 스텝은 range와 설정 points에서 계산된다`() {
        val prom = StubPrometheus(listOf(series("web-01", 1.0)))
        handle(prom, TrendQuery(MetricPattern.INSTANCE_CPU, range = "1h"))

        val span = Duration.between(prom.lastStart!!, prom.lastEnd!!)
        assertEquals(Duration.ofHours(1), span)
        // 기본 points=60 → 1h/60 = 60s 스텝
        assertEquals(Duration.ofSeconds(60), prom.lastStep)
        assertTrue(prom.lastPromql!!.contains("rate("), prom.lastPromql!!)
    }

    @Test
    fun `결과 0건이면 empty 위젯과 없음 답변`() {
        val out = handle(StubPrometheus(emptyList()))

        val widget = assertIs<MetricLineWidget>(out.widgets.single())
        assertTrue(widget.empty)
        assertTrue(out.answer.contains("없"), out.answer)
    }

    private fun emptyProvider(): ObjectProvider<InventoryRepository> =
        object : ObjectProvider<InventoryRepository> {
            override fun getObject(vararg args: Any?): InventoryRepository = throw UnsupportedOperationException()
            override fun getObject(): InventoryRepository = throw UnsupportedOperationException()
            override fun getIfAvailable(): InventoryRepository? = null
            override fun getIfUnique(): InventoryRepository? = null
        }
}
