package com.okestro.ragbot.resource.application

import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.PromQlBuilder
import com.okestro.ragbot.resource.domain.ResourceExtraction
import com.okestro.ragbot.resource.domain.TrendQuery
import java.time.Duration
import java.time.Instant
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

    override fun handle(history: List<ConversationMessage>, contextProject: String?): ResourceService.Result {
        return when (val extraction = extractor.extract(history)) {
            is ResourceExtraction.NeedsClarification -> ResourceService.Result(extraction.message, needsClarification = true)
            is ResourceExtraction.StatusResolved -> statusDonut()
            is ResourceExtraction.ThresholdResolved -> thresholdBanner()
            is ResourceExtraction.ProjectUsageResolved -> projectUsageBar()
            is ResourceExtraction.TrendResolved -> metricLine(extraction.query)
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

    /**
     * TREND 트랙 — query_range 시계열 → metric_line. 구간·스텝·라인 상한은 전부 yml(불변식 7).
     * 표현식엔 topk가 없다(시점별 순위 변동으로 시리즈에 구멍) — 상한은 WidgetBuilder가 자른다.
     */
    private fun metricLine(query: TrendQuery): ResourceService.Result {
        val entry = catalog.lookup(query.metric)
        val promql = PromQlBuilder.buildTrend(query, entry)
        val trend = properties.resource.trend

        val range = query.rangeDuration()
        val end = Instant.now()
        val start = end.minus(range)
        val step = Duration.ofSeconds((range.seconds / trend.points).coerceAtLeast(15))
        log.info("resource-trend metric={} range={} step={}s promql=\"{}\"", query.metric, query.range, step.seconds, promql)

        val series = prometheus.queryRange(promql, start, end, step)
        val widget = WidgetBuilder.metricLine(query, series, promql, entry.unit, trend.maxSeries)
        return ResourceService.Result(TrendAnswerTemplate.render(widget), widgets = listOf(widget))
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

    private companion object {
        const val PROJECT_USAGE_USED = "openstack_nova_limits_vcpus_used"
        const val PROJECT_USAGE_MAX = "openstack_nova_limits_vcpus_max"
    }
}
