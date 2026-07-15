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
import com.okestro.ragbot.resource.domain.ProjectUsageBarWidget
import com.okestro.ragbot.resource.domain.ResourceExtraction
import com.okestro.ragbot.resource.domain.Severity
import org.springframework.beans.factory.ObjectProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * PROJECT_USAGE 트랙 — tenant별 쿼터 사용률 바.
 *
 * ⚠️ 무제한(max=-1) 함정: 실측 43개 테넌트 중 **16개가 무제한**이다.
 *   - 11개는 used>0 → -9600% 같은 음수 (눈에 띈다)
 *   - 5개는 used=0 → **-0.0** → `value >= 0` 필터를 통과해 "0% 사용"으로 둔갑한다 (안 띈다)
 *   그래서 PromQL에서 `max > 0`으로 거른다(43-16=27건 확인). Kotlin 필터보다 안전하다.
 */
class DefaultResourceServiceProjectUsageTest {

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
    }

    private fun t(tenant: String, pct: Double) = LabeledSample(mapOf("tenant" to tenant), pct)

    private fun handle(prom: PrometheusClient) =
        DefaultResourceService(FixedExtractor(ResourceExtraction.ProjectUsageResolved), MetricCatalog(props), prom, emptyProvider(), props)
            .handle(listOf(ConversationMessage(Role.USER, "프로젝트별 사용률 알려줘")))

    @Test
    fun `PromQL에서 무제한을 거른다 - max 0 초과 필터`() {
        // Kotlin에서 value>=0으로 거르면 -0.0(used=0,max=-1)이 통과해 "0%"로 둔갑한다. 소스에서 막는다.
        val prom = StubPrometheus(listOf(t("AUTOTEST", 20.0)))
        handle(prom)

        val q = prom.lastPromql!!
        assertTrue(q.contains("openstack_nova_limits_vcpus_max > 0"), "무제한 필터가 PromQL에 없다: $q")
    }

    @Test
    fun `tenant 라벨을 프로젝트 이름으로 쓴다`() {
        val w = assertIs<ProjectUsageBarWidget>(handle(StubPrometheus(listOf(t("test123", 20.0)))).widgets.single())
        assertEquals("test123", w.rows.single().projectName)
        assertEquals("vCPU", w.metric)
        assertEquals("%", w.unit)
    }

    @Test
    fun `사용률 내림차순 정렬 - Prometheus 순서와 무관하게 안정적`() {
        val w = assertIs<ProjectUsageBarWidget>(
            handle(StubPrometheus(listOf(t("viola", 12.0), t("test123", 20.0), t("AUTOTEST", 0.0)))).widgets.single()
        )
        assertEquals(listOf("test123", "viola", "AUTOTEST"), w.rows.map { it.projectName })
    }

    @Test
    fun `severity는 임계치 기준 - 90퍼센트는 CRIT`() {
        val w = assertIs<ProjectUsageBarWidget>(handle(StubPrometheus(listOf(t("hot", 90.0)))).widgets.single())
        assertEquals(Severity.CRIT, w.rows.single().severity)
        assertEquals("90.0%", w.rows.single().display)
    }

    @Test
    fun `tenant 라벨 없는 샘플은 제외`() {
        val w = assertIs<ProjectUsageBarWidget>(
            handle(StubPrometheus(listOf(t("ok", 10.0), LabeledSample(emptyMap(), 99.0)))).widgets.single()
        )
        assertEquals(1, w.rows.size)
    }

    @Test
    fun `음수가 들어와도 방어적으로 제외 - PromQL이 먼저 막지만 이중 안전장치`() {
        val w = assertIs<ProjectUsageBarWidget>(
            handle(StubPrometheus(listOf(t("ok", 10.0), t("unlimited", -9600.0), t("zero-unlimited", -0.0)))).widgets.single()
        )
        assertEquals(listOf("ok"), w.rows.map { it.projectName }, "-0.0도 걸러야 한다(무제한인데 0%로 보임)")
    }

    @Test
    fun `평문 answer가 함께 나간다`() {
        val out = handle(StubPrometheus(listOf(t("test123", 20.0))))
        assertTrue(out.answer.contains("test123"), out.answer)
    }

    @Test
    fun `테넌트가 많으면 상위 10개만 그린다 - 27개 다 그리면 채팅창이 도배된다`() {
        // 실측: 무제한 제외 27개. 전부 그리면 대부분 0.0%인 바가 화면을 덮는다(실 화면에서 확인).
        val many = (1..27).map { t("tenant-%02d".format(it), it.toDouble()) }
        val w = assertIs<ProjectUsageBarWidget>(handle(StubPrometheus(many)).widgets.single())

        assertEquals(10, w.rows.size)
        assertEquals("tenant-27", w.rows.first().projectName, "사용률 높은 순이라 잘리는 건 안 쓰는 프로젝트")
    }

    @Test
    fun `평문은 틀린 총계를 주장하지 않는다`() {
        // 위젯은 이미 상위 10개로 잘린 뒤라 거기서 세면 "외 7개"가 나오는데 실제로는 24개가 더 있다.
        // 정확히 말할 수 없으면 아예 말하지 않는 게 맞다(환각 방지).
        val many = (1..27).map { t("tenant-%02d".format(it), it.toDouble()) }
        val answer = handle(StubPrometheus(many)).answer

        assertTrue(!answer.contains("외 "), "총계를 주장하면 안 된다: $answer")
        assertTrue(answer.contains("tenant-27"), answer)
    }

    private fun emptyProvider(): ObjectProvider<InventoryRepository> =
        object : ObjectProvider<InventoryRepository> {
            override fun getObject(vararg args: Any?): InventoryRepository = throw UnsupportedOperationException()
            override fun getObject(): InventoryRepository = throw UnsupportedOperationException()
            override fun getIfAvailable(): InventoryRepository? = null
            override fun getIfUnique(): InventoryRepository? = null
        }
}
