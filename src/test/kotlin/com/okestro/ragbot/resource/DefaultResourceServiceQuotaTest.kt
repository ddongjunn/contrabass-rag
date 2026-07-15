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
import com.okestro.ragbot.resource.domain.QuotaGaugeWidget
import com.okestro.ragbot.resource.domain.ResourceExtraction
import com.okestro.ragbot.resource.domain.Severity
import org.springframework.beans.factory.ObjectProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QUOTA 트랙 — 추출기가 QuotaResolved를 냈을 때 서비스가 뭘 하는가.
 *
 * 실측(2026-07-15, tenant=AUTOTEST): vcpus_max=20/used=0, memory_max=51200(MB)/used=0,
 * volume_max_gb=1000/used_gb=50. 무제한 테넌트도 실존(304-quota-test → 전부 -1).
 */
class DefaultResourceServiceQuotaTest {

    private val props = AppProperties()

    private class FixedExtractor(private val out: ResourceExtraction) : MetricQueryExtractor {
        override fun extract(history: List<ConversationMessage>): ResourceExtraction = out
    }

    private class StubPrometheus(private val labeled: List<LabeledSample>) : PrometheusClient {
        val seen = mutableListOf<String>()
        override fun query(promql: String, unit: String): List<MetricSample> = emptyList()
        override fun queryLabeled(promql: String): List<LabeledSample> {
            seen += promql
            return labeled
        }
    }

    private fun m(name: String, v: Double) = LabeledSample(mapOf("__name__" to name, "tenant" to "AUTOTEST"), v)

    /** AUTOTEST 실측값 그대로. */
    private fun live() = listOf(
        m("openstack_nova_limits_vcpus_max", 20.0),
        m("openstack_nova_limits_vcpus_used", 8.0),
        m("openstack_nova_limits_memory_max", 51200.0),   // MB = 50GB
        m("openstack_nova_limits_memory_used", 25600.0),  // MB = 25GB
        m("openstack_cinder_limits_volume_max_gb", 1000.0),
        m("openstack_cinder_limits_volume_used_gb", 50.0),
    )

    private fun handle(prom: PrometheusClient, project: String = "AUTOTEST") =
        DefaultResourceService(FixedExtractor(ResourceExtraction.QuotaResolved(project)), MetricCatalog(props), prom, emptyProvider(), props)
            .handle(listOf(ConversationMessage(Role.USER, "$project 쿼터 얼마나 썼어?")))

    @Test
    fun `6개 메트릭을 한 방에 조회한다 - tenant 필터 포함`() {
        // 메트릭당 1번씩 6번 쏘면 낭비다. 정규식으로 1방(실측 확인).
        val prom = StubPrometheus(live())
        handle(prom)

        val q = prom.seen.single()
        assertTrue(q.contains("""tenant="AUTOTEST""""), q)
        assertTrue(q.contains("__name__=~"), "정규식으로 6개를 한 번에 받아야 한다: $q")
    }

    @Test
    fun `vCPU 메모리 디스크 3항목으로 변환`() {
        val w = assertIs<QuotaGaugeWidget>(handle(StubPrometheus(live())).widgets.single())
        assertEquals(listOf("vCPU", "메모리(GB)", "디스크(GB)"), w.items.map { it.resource })
    }

    @Test
    fun `메모리는 MB를 GB로 환산 - 51200MB는 50GB`() {
        // ⚠️ 실측: memory_max=51200. 그대로 쓰면 "25600 / 51200"이라 사람이 못 읽는다.
        val w = assertIs<QuotaGaugeWidget>(handle(StubPrometheus(live())).widgets.single())
        val mem = w.items.single { it.resource == "메모리(GB)" }
        assertEquals(50.0, mem.quota)
        assertEquals(25.0, mem.used)
        assertEquals("25 / 50", mem.display)
    }

    @Test
    fun `사용률로 severity 계산 - vCPU 8 of 20은 40퍼센트라 GOOD`() {
        val w = assertIs<QuotaGaugeWidget>(handle(StubPrometheus(live())).widgets.single())
        val cpu = w.items.single { it.resource == "vCPU" }
        assertEquals(0.4, cpu.ratio!!, 1e-9)
        assertEquals(Severity.GOOD, cpu.severity)
    }

    @Test
    fun `무제한(-1)은 quota null과 무제한 표기 - 실존하는 테넌트다`() {
        val unlimited = listOf(
            m("openstack_nova_limits_vcpus_max", -1.0),
            m("openstack_nova_limits_vcpus_used", 3.0),
        )
        val w = assertIs<QuotaGaugeWidget>(handle(StubPrometheus(unlimited)).widgets.single())
        val cpu = w.items.single { it.resource == "vCPU" }
        assertNull(cpu.quota)
        assertNull(cpu.severity)
        assertEquals("3 / 무제한", cpu.display)
    }

    @Test
    fun `메트릭이 아예 없으면 그 항목은 빠진다`() {
        val onlyCpu = listOf(
            m("openstack_nova_limits_vcpus_max", 20.0),
            m("openstack_nova_limits_vcpus_used", 8.0),
        )
        val w = assertIs<QuotaGaugeWidget>(handle(StubPrometheus(onlyCpu)).widgets.single())
        assertEquals(listOf("vCPU"), w.items.map { it.resource })
    }

    @Test
    fun `평문 answer가 함께 나간다`() {
        val out = handle(StubPrometheus(live()))
        assertTrue(out.answer.contains("AUTOTEST"), out.answer)
        assertTrue(out.answer.contains("vCPU"), out.answer)
    }

    private fun emptyProvider(): ObjectProvider<InventoryRepository> =
        object : ObjectProvider<InventoryRepository> {
            override fun getObject(vararg args: Any?): InventoryRepository = throw UnsupportedOperationException()
            override fun getObject(): InventoryRepository = throw UnsupportedOperationException()
            override fun getIfAvailable(): InventoryRepository? = null
            override fun getIfUnique(): InventoryRepository? = null
        }
}
