package com.okestro.ragbot.resource.application

import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.resource.domain.PromQlBuilder
import com.okestro.ragbot.resource.domain.QuotaInput
import com.okestro.ragbot.resource.domain.ResourceExtraction
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service

@Service
class DefaultResourceService(
    private val extractor: MetricQueryExtractor,
    private val catalog: MetricCatalog,
    private val prometheus: PrometheusClient,
    private val inventoryRepository: ObjectProvider<InventoryRepository>,
    private val properties: AppProperties,
) : ResourceService {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(history: List<ConversationMessage>): ResourceService.Result {
        return when (val extraction = extractor.extract(history)) {
            is ResourceExtraction.NeedsClarification -> ResourceService.Result(extraction.message, needsClarification = true)
            is ResourceExtraction.StatusResolved -> statusDonut()
            is ResourceExtraction.ThresholdResolved -> thresholdBanner()
            is ResourceExtraction.QuotaResolved -> quotaGauge(extraction.project)
            is ResourceExtraction.ProjectUsageResolved -> projectUsageBar()
            is ResourceExtraction.Resolved -> {
                val query = extraction.query
                val entry = catalog.lookup(query.metric)
                val promql = PromQlBuilder.build(query, entry)
                log.info("resource-pipeline metric={} promql=\"{}\"", query.metric, promql)

                val samples = prometheus.query(promql, entry.unit)
                val answer = ResourceAnswerTemplate.build(query, samples)
                val sev = properties.resource.severity
                val widget = WidgetBuilder.metricRank(query, samples, promql, entry.unit, sev.warnPercent, sev.critPercent)
                val followups = FollowupBuilder.forMetric(query, samples)
                ResourceService.Result(answer, widgets = listOf(widget), followups = followups)
            }
            is ResourceExtraction.InventoryResolved -> {
                // INVENTORY(cb_common) 트랙. enabled=false면 리포지토리 빈이 없으므로 안내 후 종료.
                val repository = inventoryRepository.ifAvailable
                    ?: return ResourceService.Result(
                        "리소스 인벤토리 조회가 비활성화되어 있습니다.",
                        needsClarification = true,
                    )
                val inv = properties.resource.inventory
                val result = repository.find(extraction.query, inv.providerUuid, inv.maxRows)
                log.info("resource-inventory kind={} mode={} total={}", result.kind, extraction.query.mode, result.total)
                val widget = WidgetBuilder.inventoryCount(result)
                val followups = FollowupBuilder.forInventory(result)
                ResourceService.Result(
                    InventoryAnswerTemplate.render(result),
                    widgets = listOf(widget),
                    followups = followups,
                )
            }
        }
    }

    // ── STATUS / THRESHOLD 트랙 ────────────────────────────────────────────────
    //
    // 둘 다 쿼리가 고정이라 조건 추출이 없다 — 추출기는 target만 정하고 나머지는 여기서 결정한다.
    // (임계값은 질문이 아니라 application.yml에서 온다 — 불변식 7)

    private val statusPromql = "count by(status)(openstack_nova_server_status)"

    /** CPU 사용률 식(ratio_topk 내부와 동일) — 임계 비교용. */
    private fun cpuPercentExpr() =
        "(sum by(domain)(rate(libvirt_domain_info_cpu_time_seconds_total[5m])) " +
            "/ on(domain) max by(domain)(libvirt_domain_info_virtual_cpus) * 100)"

    private fun statusDonut(): ResourceService.Result {
        log.info("resource-status promql=\"{}\"", statusPromql)
        val widget = WidgetBuilder.statusDonut(prometheus.queryLabeled(statusPromql))
        return ResourceService.Result(StatusAnswerTemplate.render(widget), widgets = listOf(widget))
    }

    private fun thresholdBanner(): ResourceService.Result {
        val crit = properties.resource.severity.critPercent
        val countPromql = "count(${cpuPercentExpr()} > $crit)"
        log.info("resource-threshold promql=\"{}\"", countPromql)

        // ⚠️ PromQL count()는 매칭 0건이면 0이 아니라 빈 벡터를 준다 → firstOrNull ?: 0.
        //    실측(2026-07-15): 최고 CPU 34.78%라 crit=85 초과 0건 → 빈 벡터가 흔한 경로다.
        val count = prometheus.queryLabeled(countPromql).firstOrNull()?.value?.toInt() ?: 0
        // ⚠️ 라벨은 instance_name이 아니라 **domain**이다 — 식이 by(domain)으로 집계하므로 그것만 남는다.
        //    실측(2026-07-15): instance_name 보유 0/9, domain 9/9. 설계 TODO의 "instance_name"은 틀렸다.
        //    instance_name을 우선 보는 건 MetricSample.toSample과 같은 규칙(소스가 바뀌어도 견디게).
        val offenders = if (count > 0) {
            prometheus.queryLabeled("${cpuPercentExpr()} > $crit")
                .mapNotNull { it.labels["instance_name"] ?: it.labels["domain"] }
        } else {
            emptyList()
        }

        val widget = WidgetBuilder.thresholdBanner(count, crit, offenders)
        return ResourceService.Result(ThresholdAnswerTemplate.render(widget, crit), widgets = listOf(widget))
    }

    /**
     * 쿼터 6개 메트릭(vCPU/메모리/디스크 × max/used)을 **정규식 한 방**으로 받는다.
     * 메트릭당 1번씩 6번 쏠 이유가 없다(실측 확인: 1방에 6건).
     *
     * 라벨은 tenant(=프로젝트 이름, 조인 불필요) + tenant_id. 무제한은 max=-1로 실관측된다.
     */
    private fun quotaGauge(project: String): ResourceService.Result {
        val promql = """{__name__=~"$QUOTA_METRIC_RE", tenant="$project"}"""
        log.info("resource-quota project={} promql=\"{}\"", project, promql)

        val byName = prometheus.queryLabeled(promql).associate { it.labels["__name__"] to it.value }
        val sev = properties.resource.severity
        val inputs = QUOTA_SPECS.mapNotNull { spec ->
            val used = byName[spec.usedMetric] ?: return@mapNotNull null
            val max = byName[spec.maxMetric] ?: return@mapNotNull null
            // 메모리만 MB로 온다(실측 51200=50GB) — 그대로 두면 "25600 / 51200"이라 사람이 못 읽는다.
            QuotaInput(spec.label, used / spec.divisor, if (max < 0) max else max / spec.divisor)
        }

        val widget = WidgetBuilder.quotaGauge(inputs, sev.warnPercent, sev.critPercent)
        return ResourceService.Result(QuotaAnswerTemplate.render(widget, project), widgets = listOf(widget))
    }

    /**
     * tenant별 vCPU 쿼터 사용률 → project_usage_bar.
     *
     * ⚠️ 무제한(max=-1)을 **PromQL에서 거른다**(`max > 0`). Kotlin에서 `value >= 0`으로 거르면
     * `used=0, max=-1` → **-0.0**이 통과해 무제한 테넌트가 "0% 사용"으로 둔갑한다.
     * 실측(2026-07-15): 43개 중 무제한 16개(음수 11 + -0.0 5) → 필터 후 27개.
     *
     * "프로젝트별 실사용률" 단일 소스는 없어서 쿼터 사용률로 재정의한 것이다(설계 정정).
     */
    private fun projectUsageBar(): ResourceService.Result {
        val promql = "($PROJECT_USAGE_USED / ($PROJECT_USAGE_MAX > 0)) * 100"
        log.info("resource-project-usage promql=\"{}\"", promql)

        val sev = properties.resource.severity
        val widget = WidgetBuilder.projectUsageBar(
            prometheus.queryLabeled(promql), metric = "vCPU", unit = "%", sev.warnPercent, sev.critPercent,
        )
        return ResourceService.Result(ProjectUsageAnswerTemplate.render(widget), widgets = listOf(widget))
    }

    private data class QuotaSpec(val label: String, val usedMetric: String, val maxMetric: String, val divisor: Double)

    private companion object {
        const val PROJECT_USAGE_USED = "openstack_nova_limits_vcpus_used"
        const val PROJECT_USAGE_MAX = "openstack_nova_limits_vcpus_max"

        const val QUOTA_METRIC_RE =
            "openstack_nova_limits_(vcpus|memory)_(max|used)|openstack_cinder_limits_volume_(max|used)_gb"

        val QUOTA_SPECS = listOf(
            QuotaSpec("vCPU", "openstack_nova_limits_vcpus_used", "openstack_nova_limits_vcpus_max", 1.0),
            QuotaSpec("메모리(GB)", "openstack_nova_limits_memory_used", "openstack_nova_limits_memory_max", 1024.0),
            QuotaSpec("디스크(GB)", "openstack_cinder_limits_volume_used_gb", "openstack_cinder_limits_volume_max_gb", 1.0),
        )
    }
}
