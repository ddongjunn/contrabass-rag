package com.okestro.ragbot.resource

import com.okestro.ragbot.resource.domain.MetricCatalogEntry
import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.PromPattern
import com.okestro.ragbot.resource.domain.PromQlBuilder
import com.okestro.ragbot.resource.domain.ResourceQuery
import com.okestro.ragbot.resource.domain.TrendQuery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PromQlBuilderTest {

    private val cpu = MetricCatalogEntry(
        pattern = PromPattern.RATIO_TOPK,
        rawMetric = "libvirt_domain_info_cpu_time_seconds_total",
        unit = "%",
    )
    private val memory = MetricCatalogEntry(
        pattern = PromPattern.GAUGE_TOPK,
        rawMetric = "libvirt_domain_memory_stats_used_percent",
        unit = "%",
    )
    private val networkRx = MetricCatalogEntry(
        pattern = PromPattern.COUNTER_RATE_TOPK,
        rawMetric = "libvirt_domain_interface_stats_receive_bytes_total",
        unit = "B/s",
    )
    private val diskWrite = MetricCatalogEntry(
        pattern = PromPattern.COUNTER_RATE_TOPK,
        rawMetric = "libvirt_domain_block_stats_write_requests_total",
        unit = "req/s",
    )

    @Test
    fun `CPU DESC 기본값 - topk ratio_topk PromQL 생성`() {
        val query = ResourceQuery(metric = MetricPattern.INSTANCE_CPU)

        val result = PromQlBuilder.build(query, cpu)

        assertThat(result).isEqualTo(
            "topk(5, (sum by(domain)(rate(libvirt_domain_info_cpu_time_seconds_total[5m])) " +
                "/ on(domain) max by(domain)(libvirt_domain_info_virtual_cpus) * 100) " +
                "* on(domain) group_left(instance_name, project_name) libvirt_domain_openstack_info)"
        )
    }

    @Test
    fun `CPU ASC - bottomk 사용`() {
        val query = ResourceQuery(metric = MetricPattern.INSTANCE_CPU, sort = ResourceQuery.Sort.ASC, topN = 3)

        val result = PromQlBuilder.build(query, cpu)

        assertThat(result).startsWith("bottomk(3,")
    }

    @Test
    fun `Memory DESC - gauge_topk PromQL 생성`() {
        val query = ResourceQuery(metric = MetricPattern.INSTANCE_MEMORY, topN = 10)

        val result = PromQlBuilder.build(query, memory)

        assertThat(result).isEqualTo(
            "topk(10, libvirt_domain_memory_stats_used_percent " +
                "* on(domain) group_left(instance_name, project_name) libvirt_domain_openstack_info)"
        )
    }

    @Test
    fun `Network RX 1h 윈도우 - counter_rate_topk`() {
        val query = ResourceQuery(metric = MetricPattern.INSTANCE_NETWORK_RX, window = "1h", topN = 5)

        val result = PromQlBuilder.build(query, networkRx)

        assertThat(result).isEqualTo(
            "topk(5, sum by(domain)(rate(libvirt_domain_interface_stats_receive_bytes_total[1h])) " +
                "* on(domain) group_left(instance_name, project_name) libvirt_domain_openstack_info)"
        )
    }

    @Test
    fun `project 필터 - info 메트릭에 project_name 셀렉터 추가`() {
        val query = ResourceQuery(metric = MetricPattern.INSTANCE_CPU, project = "prod")

        val result = PromQlBuilder.build(query, cpu)

        assertThat(result).contains("""libvirt_domain_openstack_info{project_name="prod"}""")
    }

    @Test
    fun `project null - info 메트릭 셀렉터 없음`() {
        val query = ResourceQuery(metric = MetricPattern.INSTANCE_CPU, project = null)

        val result = PromQlBuilder.build(query, cpu)

        assertThat(result).doesNotContain("project_name=")
        assertThat(result).contains("libvirt_domain_openstack_info)")
    }

    @Test
    fun `instanceName 필터 - info 메트릭에 instance_name 셀렉터 추가`() {
        val query = ResourceQuery(metric = MetricPattern.INSTANCE_CPU, instanceName = "web-server-01")

        val result = PromQlBuilder.build(query, cpu)

        assertThat(result).contains("""instance_name="web-server-01"""")
    }

    @Test
    fun `instanceName + project 동시 필터 - 두 셀렉터 모두 포함`() {
        val query = ResourceQuery(
            metric = MetricPattern.INSTANCE_MEMORY,
            project = "prod",
            instanceName = "web-server-01",
        )

        val result = PromQlBuilder.build(query, memory)

        assertThat(result).contains("""project_name="prod"""")
        assertThat(result).contains("""instance_name="web-server-01"""")
    }

    @Test
    fun `GAUGE_RAW - 조인 없이 raw 표현식 그대로 (TREND)`() {
        val entry = MetricCatalogEntry(PromPattern.GAUGE_RAW, "openstack_nova_total_vms", "대")

        assertThat(PromQlBuilder.buildTrend(TrendQuery(metric = MetricPattern.TOTAL_VMS), entry))
            .isEqualTo("openstack_nova_total_vms")
        assertThat(PromQlBuilder.build(ResourceQuery(metric = MetricPattern.TOTAL_VMS), entry))
            .isEqualTo("topk(5, openstack_nova_total_vms)")
    }

    @Test
    fun `TREND CPU - topk 없이 ratio 표현식만 생성`() {
        val query = TrendQuery(metric = MetricPattern.INSTANCE_CPU)

        val result = PromQlBuilder.buildTrend(query, cpu)

        // 조인 우측은 dedupe 필수 — query_range에선 info 메트릭이 domain당 중복 시리즈(하이퍼바이저
        // 라벨 차이)를 갖는 구간이 실존해 many-to-many 422가 난다(실측 2026-07-24).
        assertThat(result).isEqualTo(
            "(sum by(domain)(rate(libvirt_domain_info_cpu_time_seconds_total[5m])) " +
                "/ on(domain) max by(domain)(libvirt_domain_info_virtual_cpus) * 100) " +
                "* on(domain) group_left(instance_name, project_name) " +
                "max by(domain, instance_name, project_name)(libvirt_domain_openstack_info)"
        )
    }

    @Test
    fun `TREND project 필터 - info 셀렉터 추가되고 topk는 없다`() {
        val query = TrendQuery(metric = MetricPattern.INSTANCE_CPU, project = "prod")

        val result = PromQlBuilder.buildTrend(query, cpu)

        assertThat(result).contains("""libvirt_domain_openstack_info{project_name="prod"}""")
        assertThat(result).doesNotContain("topk(")
    }

    @Test
    fun `TREND counter - rate 표현식 생성`() {
        val query = TrendQuery(metric = MetricPattern.INSTANCE_NETWORK_RX)

        val result = PromQlBuilder.buildTrend(query, networkRx)

        assertThat(result).isEqualTo(
            "sum by(domain)(rate(libvirt_domain_interface_stats_receive_bytes_total[5m])) " +
                "* on(domain) group_left(instance_name, project_name) " +
                "max by(domain, instance_name, project_name)(libvirt_domain_openstack_info)"
        )
    }

    @Test
    fun `Disk Write topN=3 ASC - bottomk counter_rate_topk`() {
        val query = ResourceQuery(
            metric = MetricPattern.INSTANCE_DISK_WRITE,
            sort = ResourceQuery.Sort.ASC,
            topN = 3,
            window = "15m",
        )

        val result = PromQlBuilder.build(query, diskWrite)

        assertThat(result).isEqualTo(
            "bottomk(3, sum by(domain)(rate(libvirt_domain_block_stats_write_requests_total[15m])) " +
                "* on(domain) group_left(instance_name, project_name) libvirt_domain_openstack_info)"
        )
    }
}
