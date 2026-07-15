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
 * THRESHOLD 트랙 — 추출기가 ThresholdResolved를 냈을 때 서비스가 뭘 하는가.
 * "그 질문이 THRESHOLD로 분류되는가"는 별개 관심사다(MetricExtractionAccuracyTest, 실 OpenAI).
 */
class DefaultResourceServiceThresholdTest {

    private val props = AppProperties()   // severity 기본값: warn=70, crit=85

    private class FixedExtractor(private val out: ResourceExtraction) : MetricQueryExtractor {
        override fun extract(history: List<ConversationMessage>): ResourceExtraction = out
    }

    /** promql별로 응답을 정하는 스텁. count 쿼리와 offenders 쿼리를 구분한다. */
    private class MapPrometheus(private val byQuery: (String) -> List<LabeledSample>) : PrometheusClient {
        val seen = mutableListOf<String>()
        override fun query(promql: String, unit: String): List<MetricSample> = emptyList()
        override fun queryLabeled(promql: String): List<LabeledSample> {
            seen += promql
            return byQuery(promql)
        }
    }

    private fun handle(prom: PrometheusClient, p: AppProperties = props) =
        DefaultResourceService(FixedExtractor(ResourceExtraction.ThresholdResolved), MetricCatalog(p), prom, emptyProvider(), p)
            .handle(listOf(ConversationMessage(Role.USER, "임계 넘은 노드 있어?")))

    @Test
    fun `빈 벡터면 0대 - PromQL count()는 매칭 0건에 0이 아니라 빈 벡터를 준다`() {
        // 실측(2026-07-15): 최고 CPU 34.78% → crit=85 초과 0건 → result:[] 가 실제로 흔한 경로다.
        val out = handle(MapPrometheus { emptyList() })

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
        val out = handle(
            MapPrometheus { q ->
                if (q.startsWith("count(")) listOf(LabeledSample(emptyMap(), 2.0))
                else listOf(
                    LabeledSample(mapOf("domain" to "instance-00003906"), 91.2),
                    LabeledSample(mapOf("domain" to "instance-00003909"), 88.4),
                )
            }
        )

        val w = assertIs<ThresholdBannerWidget>(out.widgets.single())
        assertEquals(2, w.count)
        assertEquals(Severity.CRIT, w.level)
        assertEquals("CPU 85%↑ : instance-00003906, instance-00003909", w.detail)
    }

    @Test
    fun `instance_name 라벨이 있으면 그걸 우선한다`() {
        // MetricSample.toSample과 같은 우선순위(instance_name ?: domain) — 소스가 바뀌어도 견디게.
        val out = handle(
            MapPrometheus { q ->
                if (q.startsWith("count(")) listOf(LabeledSample(emptyMap(), 1.0))
                else listOf(LabeledSample(mapOf("instance_name" to "web-prod-07", "domain" to "instance-0001"), 91.2))
            }
        )
        assertEquals("CPU 85%↑ : web-prod-07", assertIs<ThresholdBannerWidget>(out.widgets.single()).detail)
    }

    @Test
    fun `초과 0대면 offenders 쿼리를 아예 안 쏜다 - 불필요한 조회 금지`() {
        val prom = MapPrometheus { emptyList() }
        handle(prom)
        assertEquals(1, prom.seen.size, "count 쿼리 1방이면 충분한데 더 쐈다: ${prom.seen}")
    }

    @Test
    fun `임계값은 application-yml에서 온다 - 하드코딩 금지`() {
        val custom = AppProperties(
            resource = AppProperties.Resource(severity = AppProperties.Resource.Severity(warnPercent = 50, critPercent = 60)),
        )
        val prom = MapPrometheus { emptyList() }
        val out = handle(prom, custom)

        assertTrue(prom.seen.single().contains("> 60"), prom.seen.single())
        assertTrue(out.answer.contains("60%"), out.answer)
    }

    private fun emptyProvider(): ObjectProvider<InventoryRepository> =
        object : ObjectProvider<InventoryRepository> {
            override fun getObject(vararg args: Any?): InventoryRepository = throw UnsupportedOperationException()
            override fun getObject(): InventoryRepository = throw UnsupportedOperationException()
            override fun getIfAvailable(): InventoryRepository? = null
            override fun getIfUnique(): InventoryRepository? = null
        }
}
