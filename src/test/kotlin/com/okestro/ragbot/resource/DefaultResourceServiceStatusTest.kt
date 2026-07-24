package com.okestro.ragbot.resource

import java.time.Instant
import java.time.Duration
import com.okestro.ragbot.resource.domain.RangeSeries
import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.chat.domain.ConversationMessage.Role
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.resource.application.DefaultResourceService
import com.okestro.ragbot.resource.application.InventoryRepository
import com.okestro.ragbot.resource.application.MetricCatalog
import com.okestro.ragbot.resource.application.MetricQueryExtractor
import com.okestro.ragbot.resource.application.PrometheusClient
import com.okestro.ragbot.resource.domain.LabeledSample
import com.okestro.ragbot.resource.domain.MetricSample
import com.okestro.ragbot.resource.domain.ResourceExtraction
import com.okestro.ragbot.resource.domain.StatusDonutWidget
import org.springframework.beans.factory.ObjectProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * STATUS 트랙 — 추출기가 StatusResolved를 냈을 때 서비스가 뭘 하는가.
 * "그 질문이 STATUS로 분류되는가"는 별개 관심사다(MetricExtractionAccuracyTest, 실 OpenAI).
 */
class DefaultResourceServiceStatusTest {

    private val props = AppProperties()

    private class FixedExtractor(private val out: ResourceExtraction) : MetricQueryExtractor {
        override fun extract(history: List<ConversationMessage>): ResourceExtraction = out
    }

    private class StubPrometheus(private val labeled: List<LabeledSample>) : PrometheusClient {
        var lastPromql: String? = null
        override fun query(promql: String, unit: String): List<MetricSample> = emptyList()
        override fun queryLabeled(promql: String): List<LabeledSample> {
            lastPromql = promql
            return labeled
        }
        override fun queryRange(promql: String, start: Instant, end: Instant, step: Duration): List<RangeSeries> = emptyList()
    }

    private fun handle(prom: PrometheusClient) =
        DefaultResourceService(FixedExtractor(ResourceExtraction.StatusResolved), MetricCatalog(props), prom, emptyProvider(), props)
            .handle(listOf(ConversationMessage(Role.USER, "인스턴스 상태 분포 알려줘")))

    private fun live() = listOf(
        LabeledSample(mapOf("status" to "ACTIVE"), 121.0),
        LabeledSample(mapOf("status" to "SHUTOFF"), 5.0),
        LabeledSample(mapOf("status" to "ERROR"), 1.0),
    )

    @Test
    fun `StatusResolved면 status_donut 위젯과 평문 answer`() {
        val out = handle(StubPrometheus(live()))

        val widget = assertIs<StatusDonutWidget>(out.widgets.single())
        assertEquals(127, widget.total)
        assertEquals("ACTIVE", widget.segments.first().status)
        assertTrue(out.answer.contains("127대"), out.answer)
        assertTrue(out.answer.contains("ACTIVE 121대"), out.answer)
    }

    @Test
    fun `status별 개수를 세는 쿼리를 쓴다`() {
        // 완전일치로 박으면 `count by (status)`처럼 의미가 같은 표기만 바꿔도 깨지고,
        // 메트릭명을 설정으로 빼는 리팩터도 막는다. 의미만 고정한다.
        val prom = StubPrometheus(live())
        handle(prom)

        val q = prom.lastPromql!!
        assertTrue(q.contains("openstack_nova_server_status"), q)
        assertTrue(Regex("""count\s+by\s*\(\s*status\s*\)""").containsMatchIn(q), "status별 집계가 아니다: $q")
    }

    @Test
    fun `결과 0건이면 empty 위젯과 없음 답변`() {
        val out = handle(StubPrometheus(emptyList()))
        val widget = assertIs<StatusDonutWidget>(out.widgets.single())
        assertTrue(widget.empty)
        assertTrue(out.answer.contains("없습니다"), out.answer)
    }

    private fun emptyProvider(): ObjectProvider<InventoryRepository> =
        object : ObjectProvider<InventoryRepository> {
            override fun getObject(vararg args: Any?): InventoryRepository = throw UnsupportedOperationException()
            override fun getObject(): InventoryRepository = throw UnsupportedOperationException()
            override fun getIfAvailable(): InventoryRepository? = null
            override fun getIfUnique(): InventoryRepository? = null
        }
}
