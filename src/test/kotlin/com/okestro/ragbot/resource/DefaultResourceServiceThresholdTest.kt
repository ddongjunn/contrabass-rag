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
import com.okestro.ragbot.resource.domain.Severity
import com.okestro.ragbot.resource.domain.ThresholdBannerWidget
import org.springframework.beans.factory.ObjectProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TEMP(#21): threshold_banner 임시 키워드 배선. #21에서 의도 분류가 완성되면 대상 코드와 함께 삭제.
 */
class DefaultResourceServiceThresholdTest {

    private val props = AppProperties()   // severity 기본값: warn=70, crit=85

    private class SpyExtractor : MetricQueryExtractor {
        var called = false
        override fun extract(history: List<ConversationMessage>): ResourceExtraction {
            called = true
            return ResourceExtraction.NeedsClarification("추출기 호출됨")
        }
    }

    /** promql별로 응답을 정해 돌려주는 스텁. count 쿼리와 offenders 쿼리를 구분한다. */
    private class MapPrometheus(private val byQuery: (String) -> List<LabeledSample>) : PrometheusClient {
        val seen = mutableListOf<String>()
        override fun query(promql: String, unit: String): List<MetricSample> = emptyList()
        override fun queryLabeled(promql: String): List<LabeledSample> {
            seen += promql
            return byQuery(promql)
        }
    }

    private fun ask(question: String, prom: PrometheusClient, extractor: MetricQueryExtractor = SpyExtractor()) =
        DefaultResourceService(extractor, MetricCatalog(props), prom, emptyProvider(), props)
            .handle(listOf(ConversationMessage(Role.USER, question)))

    @Test
    fun `빈 벡터면 0대 - PromQL count()는 매칭 0건에 0이 아니라 빈 벡터를 준다`() {
        // 실측(2026-07-15): 최고 CPU 34.78% → crit=85 초과 0건 → result:[] 가 실제로 흔한 경로다.
        val prom = MapPrometheus { emptyList() }
        val out = ask("임계 넘은 노드 있어?", prom)

        val w = assertIs<ThresholdBannerWidget>(out.widgets.single())
        assertEquals(0, w.count)
        assertEquals(Severity.GOOD, w.level)
        assertNull(w.detail)
        assertTrue(out.answer.contains("초과하는 인스턴스는 없습니다"), out.answer)
        assertNotEquals(w.title, out.answer, "평문이 배너 제목을 그대로 반복하면 캡션이 두 번 쌓인다")
    }

    @Test
    fun `초과가 있으면 CRIT과 인스턴스명 - 라벨은 domain이다`() {
        // ⚠️ 실측(2026-07-15): 식이 by(domain)으로 집계하므로 결과 라벨은 domain뿐이다.
        //    instance_name 보유 0/9. 설계 TODO엔 instance_name이라 적혀 있었으나 사실과 다르다.
        val prom = MapPrometheus { q ->
            if (q.startsWith("count(")) listOf(LabeledSample(emptyMap(), 2.0))
            else listOf(
                LabeledSample(mapOf("domain" to "instance-00003906"), 91.2),
                LabeledSample(mapOf("domain" to "instance-00003909"), 88.4),
            )
        }
        val out = ask("임계 초과한 인스턴스 알려줘", prom)

        val w = assertIs<ThresholdBannerWidget>(out.widgets.single())
        assertEquals(2, w.count)
        assertEquals(Severity.CRIT, w.level)
        assertEquals("CPU 85%↑ : instance-00003906, instance-00003909", w.detail)
    }

    @Test
    fun `instance_name 라벨이 있으면 그걸 우선한다`() {
        // MetricSample.toSample과 같은 우선순위(instance_name ?: domain) — 소스가 바뀌어도 견디게.
        val prom = MapPrometheus { q ->
            if (q.startsWith("count(")) listOf(LabeledSample(emptyMap(), 1.0))
            else listOf(LabeledSample(mapOf("instance_name" to "web-prod-07", "domain" to "instance-0001"), 91.2))
        }
        val w = assertIs<ThresholdBannerWidget>(ask("임계 초과", prom).widgets.single())
        assertEquals("CPU 85%↑ : web-prod-07", w.detail)
    }

    @Test
    fun `초과 0대면 offenders 쿼리를 아예 안 쏜다 - 불필요한 조회 금지`() {
        val prom = MapPrometheus { emptyList() }
        ask("임계 넘은 노드 있어?", prom)
        assertEquals(1, prom.seen.size, "count 쿼리 1방이면 충분한데 더 쐈다: ${prom.seen}")
    }

    @Test
    fun `임계값은 application-yml에서 온다 - 하드코딩 금지`() {
        val custom = AppProperties(
            resource = AppProperties.Resource(severity = AppProperties.Resource.Severity(warnPercent = 50, critPercent = 60)),
        )
        val prom = MapPrometheus { emptyList() }
        val out = DefaultResourceService(SpyExtractor(), MetricCatalog(custom), prom, emptyProvider(), custom)
            .handle(listOf(ConversationMessage(Role.USER, "임계 넘은 노드 있어?")))

        assertTrue(prom.seen.single().contains("> 60"), prom.seen.single())
        assertTrue(out.answer.contains("60%"), out.answer)
    }

    @Test
    fun `임계 질문은 추출기를 안 부른다 - LLM 토큰 0`() {
        val spy = SpyExtractor()
        ask("임계 넘은 노드 있어?", MapPrometheus { emptyList() }, spy)
        assertTrue(!spy.called)
    }

    @Test
    fun `무관한 질문은 기존 경로로`() {
        val spy = SpyExtractor()
        ask("볼륨 몇 개야?", MapPrometheus { emptyList() }, spy)
        assertTrue(spy.called)
    }

    private fun emptyProvider(): ObjectProvider<InventoryRepository> =
        object : ObjectProvider<InventoryRepository> {
            override fun getObject(vararg args: Any?): InventoryRepository = throw UnsupportedOperationException()
            override fun getObject(): InventoryRepository = throw UnsupportedOperationException()
            override fun getIfAvailable(): InventoryRepository? = null
            override fun getIfUnique(): InventoryRepository? = null
        }
}
