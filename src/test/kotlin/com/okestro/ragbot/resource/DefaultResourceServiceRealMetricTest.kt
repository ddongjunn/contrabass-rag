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
import com.okestro.ragbot.resource.domain.RangeSeries
import com.okestro.ragbot.resource.domain.ResourceExtraction
import com.okestro.ragbot.resource.domain.Severity
import com.okestro.ragbot.resource.domain.ThresholdBannerWidget
import com.okestro.ragbot.resource.domain.UsageBarWidget
import org.springframework.beans.factory.ObjectProvider
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * IP_USAGE·CAPACITY·AGENT 트랙 — 추출기가 해당 Resolved를 냈을 때 서비스가 뭘 하는가.
 * 전부 2026-07-24 실측 라벨·값 모양을 그대로 픽스처로 쓴다(실 Prometheus 응답 형태).
 */
class DefaultResourceServiceRealMetricTest {

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

    private fun handle(extraction: ResourceExtraction, prom: PrometheusClient) =
        DefaultResourceService(FixedExtractor(extraction), MetricCatalog(props), prom, emptyProvider(), props)
            .handle(listOf(ConversationMessage(Role.USER, "q")))

    // ── IP_USAGE ────────────────────────────────────────────────────────────

    @Test
    fun `IpUsageResolved면 네트워크별 usage_bar와 평문 answer`() {
        val prom = StubPrometheus(
            listOf(
                LabeledSample(mapOf("network_name" to "auto-cluster-network"), 3.16),
                LabeledSample(mapOf("network_name" to "10.255.43.0/24"), 17.28),
            ),
        )
        val out = handle(ResourceExtraction.IpUsageResolved, prom)

        val widget = assertIs<UsageBarWidget>(out.widgets.single())
        assertEquals("usage_bar", widget.type)
        // 사용률 내림차순
        assertEquals(listOf("10.255.43.0/24", "auto-cluster-network"), widget.rows.map { it.name })
        assertTrue(prom.lastPromql!!.contains("ip_availabilities"), prom.lastPromql!!)
        assertTrue(out.answer.contains("IP"), out.answer)
    }

    @Test
    fun `IP_USAGE 결과 0건이면 empty 위젯`() {
        val out = handle(ResourceExtraction.IpUsageResolved, StubPrometheus(emptyList()))
        assertTrue(assertIs<UsageBarWidget>(out.widgets.single()).empty)
        assertTrue(out.answer.contains("없"), out.answer)
    }

    // ── CAPACITY ────────────────────────────────────────────────────────────

    @Test
    fun `CapacityResolved면 Ceph·cinder 백엔드 사용률 usage_bar`() {
        // 실측 모양: ceph는 bytes 2개, cinder는 GB total/free (백엔드 라벨)
        val prom = StubPrometheus(
            listOf(
                LabeledSample(mapOf("__name__" to "ceph_cluster_total_bytes"), 38_207_576_080_384.0),
                LabeledSample(mapOf("__name__" to "ceph_cluster_total_used_bytes"), 1_091_409_137_664.0),
                LabeledSample(mapOf("__name__" to "openstack_cinder_pool_capacity_total_gb", "volume_backend_name" to "Main_Ceph_ceph_01_hdd_dev"), 9878.44),
                LabeledSample(mapOf("__name__" to "openstack_cinder_pool_capacity_free_gb", "volume_backend_name" to "Main_Ceph_ceph_01_hdd_dev"), 9578.97),
            ),
        )
        val out = handle(ResourceExtraction.CapacityResolved, prom)

        val widget = assertIs<UsageBarWidget>(out.widgets.single())
        assertEquals(2, widget.rows.size)
        val ceph = widget.rows.first { it.name.contains("Ceph 클러스터") }
        assertEquals(2.9, ceph.value, 0.1)
        // display엔 절대량이 있어야 한다 — %만으로는 "얼마나 남았어"에 답이 안 된다
        assertTrue(ceph.display.contains("TB"), ceph.display)
        val cinder = widget.rows.first { it.name.contains("Main_Ceph") }
        assertEquals(3.0, cinder.value, 0.1)
        assertTrue(out.answer.contains("용량"), out.answer)
    }

    @Test
    fun `CAPACITY 결과 0건이면 empty 위젯`() {
        val out = handle(ResourceExtraction.CapacityResolved, StubPrometheus(emptyList()))
        assertTrue(assertIs<UsageBarWidget>(out.widgets.single()).empty)
    }

    // ── AGENT ───────────────────────────────────────────────────────────────

    @Test
    fun `AgentResolved - 다운 에이전트가 있으면 CRIT 배너와 목록`() {
        val prom = StubPrometheus(
            listOf(
                LabeledSample(mapOf("service" to "nova-compute", "hostname" to "rack4-6-con2"), 0.0),
            ),
        )
        val out = handle(ResourceExtraction.AgentResolved, prom)

        val widget = assertIs<ThresholdBannerWidget>(out.widgets.single())
        assertEquals(Severity.CRIT, widget.level)
        assertEquals(1, widget.count)
        assertTrue(widget.detail!!.contains("nova-compute@rack4-6-con2"), widget.detail!!)
        assertTrue(prom.lastPromql!!.contains("agent_state"), prom.lastPromql!!)
        assertTrue(out.answer.contains("다운"), out.answer)
    }

    @Test
    fun `AgentResolved - 다운 0이면 GOOD 배너와 안심 답변`() {
        val out = handle(ResourceExtraction.AgentResolved, StubPrometheus(emptyList()))

        val widget = assertIs<ThresholdBannerWidget>(out.widgets.single())
        assertEquals(Severity.GOOD, widget.level)
        assertEquals(0, widget.count)
        assertTrue(out.answer.contains("정상"), out.answer)
    }

    private fun emptyProvider(): ObjectProvider<InventoryRepository> =
        object : ObjectProvider<InventoryRepository> {
            override fun getObject(vararg args: Any?): InventoryRepository = throw UnsupportedOperationException()
            override fun getObject(): InventoryRepository = throw UnsupportedOperationException()
            override fun getIfAvailable(): InventoryRepository? = null
            override fun getIfUnique(): InventoryRepository? = null
        }
}
