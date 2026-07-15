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
import com.okestro.ragbot.resource.domain.MetricSample
import com.okestro.ragbot.resource.domain.ResourceExtraction
import com.okestro.ragbot.resource.domain.StatusDonutWidget
import org.springframework.beans.factory.ObjectProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * TEMP(#21): status_donut 임시 키워드 배선. 주니어가 의도 분류를 완성하면 이 테스트와
 * 대상 코드를 함께 삭제한다. 그때까지 화면 검증 경로를 지키는 역할.
 *
 * 임의 if로 뚫는 이유: LLM 프롬프트를 건드리면 (1) 요청당 토큰이 늘고(불변식 2),
 * (2) #21이 소유한 ResourcePrompts/LlmMetricQueryExtractor와 충돌한다.
 */
class DefaultResourceServiceStatusTest {

    private val props = AppProperties()

    /** 추출기는 절대 안 불려야 한다 — 불리면 LLM 토큰이 나간다. */
    private class SpyExtractor : MetricQueryExtractor {
        var called = false
        override fun extract(history: List<ConversationMessage>): ResourceExtraction {
            called = true
            return ResourceExtraction.NeedsClarification("추출기 호출됨")
        }
    }

    private class StubPrometheus(private val labeled: List<LabeledSample>) : PrometheusClient {
        var lastPromql: String? = null
        override fun query(promql: String, unit: String): List<MetricSample> = emptyList()
        override fun queryLabeled(promql: String): List<LabeledSample> {
            lastPromql = promql
            return labeled
        }
    }

    private fun ask(question: String, prom: PrometheusClient, extractor: MetricQueryExtractor) =
        DefaultResourceService(extractor, MetricCatalog(props), prom, emptyProvider(), props)
            .handle(listOf(ConversationMessage(Role.USER, question)))

    private fun live() = listOf(
        LabeledSample(mapOf("status" to "ACTIVE"), 121.0),
        LabeledSample(mapOf("status" to "SHUTOFF"), 5.0),
        LabeledSample(mapOf("status" to "ERROR"), 1.0),
    )

    @Test
    fun `상태 분포 질문 → status_donut 위젯과 평문 answer`() {
        val prom = StubPrometheus(live())
        val out = ask("인스턴스 상태 분포 알려줘", prom, SpyExtractor())

        val widget = assertIs<StatusDonutWidget>(out.widgets.single())
        assertEquals(127, widget.total)
        assertEquals("ACTIVE", widget.segments.first().status)
        assertTrue(out.answer.contains("127대"), out.answer)
        assertTrue(out.answer.contains("ACTIVE 121대"), out.answer)
    }

    @Test
    fun `상태 질문은 추출기를 안 부른다 - LLM 토큰 0`() {
        val spy = SpyExtractor()
        ask("인스턴스 상태 분포 알려줘", StubPrometheus(live()), spy)
        assertTrue(!spy.called, "임시 배선은 LLM 추출기보다 먼저 단락돼야 한다")
    }

    @Test
    fun `count by status 쿼리를 쓴다`() {
        val prom = StubPrometheus(live())
        ask("인스턴스 상태 분포 알려줘", prom, SpyExtractor())
        assertEquals("count by(status)(openstack_nova_server_status)", prom.lastPromql)
    }

    @Test
    fun `무관한 질문은 기존 경로로 넘어간다 - 추출기 호출됨`() {
        val spy = SpyExtractor()
        val out = ask("CPU 높은 VM 알려줘", StubPrometheus(live()), spy)
        assertTrue(spy.called, "상태 질문이 아니면 임시 배선이 가로채면 안 된다")
        assertTrue(out.widgets.isEmpty())
    }

    @Test
    fun `결과 0건이면 empty 위젯과 없음 답변`() {
        val out = ask("인스턴스 상태 분포 알려줘", StubPrometheus(emptyList()), SpyExtractor())
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
