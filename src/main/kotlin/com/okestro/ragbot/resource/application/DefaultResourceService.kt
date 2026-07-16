package com.okestro.ragbot.resource.application

import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.resource.domain.MetricPattern
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

    /**
     * CPU 사용률 식(PromQlBuilder.RATIO_TOPK 내부와 동일) — 임계 비교용.
     *
     * ⚠️ 메트릭명·윈도우를 하드코딩하면 안 된다(불변식 7). yml의 catalog.INSTANCE_CPU.raw-metric을
     * 바꿨을 때 METRIC 경로만 따라가고 THRESHOLD는 죽은 메트릭을 조회해 **"초과 없음"이라는 거짓 안심**을
     * 준다. 같은 소스에서 읽어 divergence를 원천 차단한다.
     */
    private fun cpuPercentExpr(): String {
        val raw = catalog.lookup(MetricPattern.INSTANCE_CPU).rawMetric
        val window = properties.resource.defaultWindow
        return "(sum by(domain)(rate(${raw}[$window])) " +
            "/ on(domain) max by(domain)(${PromQlBuilder.VCPUS_METRIC}) * 100)"
    }

    private fun statusDonut(): ResourceService.Result {
        log.info("resource-status promql=\"{}\"", statusPromql)
        val widget = WidgetBuilder.statusDonut(prometheus.queryLabeled(statusPromql))
        return ResourceService.Result(StatusAnswerTemplate.render(widget), widgets = listOf(widget))
    }

    /**
     * 쿼리 **한 방**으로 초과 인스턴스를 받고 개수는 `.size`로 센다.
     *
     * 예전엔 `count(expr > crit)`와 `expr > crit`를 따로 쐈는데, `count(X)`는 X의 시리즈 수라 첫 쿼리가
     * 아무것도 안 사면서 **불일치 창**만 만들었다(t1과 t2 사이에 인스턴스가 85% 아래로 떨어지면
     * "3대"라면서 이름은 2개). 한 방이면 항상 자기정합적이고 Prometheus 부하도 절반이며,
     * `count()`의 빈 벡터 함정도 사라진다(빈 리스트는 그냥 size=0).
     */
    private fun thresholdBanner(): ResourceService.Result {
        val crit = properties.resource.severity.critPercent
        val promql = "${cpuPercentExpr()} > $crit"
        log.info("resource-threshold promql=\"{}\"", promql)

        // ⚠️ 라벨은 instance_name이 아니라 **domain**이다 — 식이 by(domain)으로 집계하므로 그것만 남는다.
        //    실측(2026-07-15): instance_name 보유 0/9, domain 9/9. 설계 TODO의 "instance_name"은 틀렸다.
        //    instance_name을 우선 보는 건 MetricSample.toSample과 같은 규칙(소스가 바뀌어도 견디게).
        val offenders = prometheus.queryLabeled(promql)
            .mapNotNull { it.labels["instance_name"] ?: it.labels["domain"] }

        val widget = WidgetBuilder.thresholdBanner(offenders.size, crit, offenders)
        return ResourceService.Result(ThresholdAnswerTemplate.render(widget, crit, offenders), widgets = listOf(widget))
    }

    /**
     * 쿼터 6개 메트릭(vCPU/메모리/디스크 × max/used)을 **정규식 한 방**으로 받는다.
     * 메트릭당 1번씩 6번 쏠 이유가 없다(실측 확인: 1방에 6건).
     *
     * 라벨은 tenant(=프로젝트 이름, 조인 불필요) + tenant_id. 무제한은 max=-1로 실관측된다.
     */
    private fun quotaGauge(project: String): ResourceService.Result {
        val promql = """{__name__=~"$QUOTA_METRIC_RE", tenant="${escapePromQlLabel(project)}"}"""
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
     * 무제한(max=-1)도 **계약대로 표시한다**(d.ts: `value: null` + "무제한", 프론트는 muted 100% 바).
     * 실측 43개 중 16개가 무제한이라 거르면 이유 없이 3분의 1이 사라진다. 소스에서 안 거르고
     * 빌더가 부호로 판별해 null 처리한다 — `used/max`는 max=-1일 때 음수(used>0) 또는 **-0.0**(used=0)이다.
     *
     * "프로젝트별 실사용률" 단일 소스는 없어서 쿼터 사용률로 재정의한 것이다(설계 정정).
     */
    private fun projectUsageBar(): ResourceService.Result {
        val promql = "($PROJECT_USAGE_USED / $PROJECT_USAGE_MAX) * 100"
        log.info("resource-project-usage promql=\"{}\"", promql)

        val sev = properties.resource.severity
        val widget = WidgetBuilder.projectUsageBar(
            prometheus.queryLabeled(promql),
            metric = "vCPU",
            unit = "%",
            sev.warnPercent,
            sev.critPercent,
            topN = properties.resource.widgets.projectUsageTopN,
        )
        return ResourceService.Result(ProjectUsageAnswerTemplate.render(widget), widgets = listOf(widget))
    }

    /**
     * PromQL 라벨값 이스케이프. **project는 LLM이 사용자 질문에서 뽑은 자유 문자열**이라 그대로 넣으면
     * 셀렉터를 닫고 다른 테넌트를 붙일 수 있다(리뷰에서 실증):
     *   `a"} or {__name__=~"...", tenant="admin`  → 유효한 PromQL이 되어 admin의 쿼터가 렌더된다.
     *
     * `tenant=`는 정확 일치라 정규식 메타문자는 무해하고, 위험한 건 `"`와 `\` 둘뿐이다.
     * 이스케이프하면 그런 이름의 테넌트를 찾다가 0건 → "쿼터 정보를 찾지 못했습니다" 경로로 안전하게 떨어진다.
     */
    private fun escapePromQlLabel(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

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
