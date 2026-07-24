package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.InventoryFilters
import com.okestro.ragbot.resource.domain.InventoryKind
import com.okestro.ragbot.resource.domain.InventoryResult
import com.okestro.ragbot.resource.domain.InventoryCountWidget
import com.okestro.ragbot.resource.domain.LabeledSample
import com.okestro.ragbot.resource.domain.MetricLineSeries
import com.okestro.ragbot.resource.domain.MetricLineWidget
import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.MetricRankRow
import com.okestro.ragbot.resource.domain.MetricRankWidget
import com.okestro.ragbot.resource.domain.MetricSample
import com.okestro.ragbot.resource.domain.RangeSeries
import com.okestro.ragbot.resource.domain.TrendQuery
import com.okestro.ragbot.resource.domain.UsageBarWidget
import com.okestro.ragbot.resource.domain.UsageInput
import com.okestro.ragbot.resource.domain.UsageRow
import com.okestro.ragbot.resource.domain.ResourceQuery
import com.okestro.ragbot.resource.domain.Severity
import com.okestro.ragbot.resource.domain.StatusDonutWidget
import com.okestro.ragbot.resource.domain.StatusSegment
import com.okestro.ragbot.resource.domain.ThresholdBannerWidget

/**
 * MetricSample/InventoryResult → Widget 순수 변환(설계 §5·§13). LLM 0회.
 * 숫자 포맷은 MetricValueFormatter 공유, severity는 config 임계치(§5.1)로 계산.
 */
object WidgetBuilder {

    /** 배너 detail에 나열할 인스턴스 이름 상한. 표시용 상수라 튜닝값이 아니다. */
    private const val BANNER_NAME_LIMIT = 5

    private val METRIC_LABEL = mapOf(
        MetricPattern.INSTANCE_CPU        to "CPU 사용률",
        MetricPattern.INSTANCE_MEMORY     to "메모리 사용률",
        MetricPattern.INSTANCE_NETWORK_RX to "네트워크 수신량",
        MetricPattern.INSTANCE_NETWORK_TX to "네트워크 송신량",
        MetricPattern.INSTANCE_DISK_READ  to "디스크 읽기량",
        MetricPattern.INSTANCE_DISK_WRITE to "디스크 쓰기량",
        MetricPattern.TOTAL_VMS           to "전체 VM 수",
        MetricPattern.STORAGE_USED        to "스토리지 사용률",
    )

    private val INVENTORY_LABEL = mapOf(
        InventoryKind.INSTANCE to "인스턴스",
        InventoryKind.INSTANCE_SNAPSHOT to "인스턴스 스냅샷",
        InventoryKind.VOLUME to "볼륨",
        InventoryKind.VOLUME_SNAPSHOT to "볼륨 스냅샷",
    )

    // ── REAL: 기존 조회 결과 재사용(1a) ─────────────────────────────────────────

    /** Prometheus TopN 결과 → metric_rank 위젯(REAL). 0건이면 empty=true. */
    fun metricRank(
        query: ResourceQuery,
        samples: List<MetricSample>,
        promql: String,
        unit: String,
        warnPercent: Int,
        critPercent: Int,
    ): MetricRankWidget {
        val label = METRIC_LABEL[query.metric] ?: query.metric.name
        val direction = if (query.sort == ResourceQuery.Sort.DESC) "높은" else "낮은"
        val rows = samples.map { s ->
            MetricRankRow(
                instanceName = s.instanceName,
                projectName = s.projectName,
                value = s.value,
                display = MetricValueFormatter.format(s.value, s.unit),
                severity = severityForPercent(s.value, s.unit, warnPercent, critPercent),
                // TODO(new-dev): 1b 스파크라인. 실 경로에선 null 유지가 정답(실값 위 가짜 추세선 = 환각). 채우려면:
                //   1) PrometheusClient에 queryRange(promql, window="5m", step="30s") 추가
                //      → /api/v1/query_range (POST form: query,start,end,step). 참조 백엔드 getRange 패턴 검증됨.
                //   2) 행(instance)별 시계열 List<Double>을 이 spark에 부착. topN(5)만, Resilience4j 'prometheus' 재사용.
                spark = null,
            )
        }
        return MetricRankWidget(
            title = "${label}이 $direction 인스턴스",
            unit = unit,
            window = query.window,
            promql = promql,
            rows = rows,
            empty = samples.isEmpty(),
        )
    }

    /**
     * query_range 시계열 → metric_line 위젯(REAL). 0건이면 empty=true.
     *
     * buildTrend는 topk를 못 쓴다(시점마다 순위가 바뀌어 시리즈에 구멍) — 상한은 여기서
     * **마지막 값 기준 내림차순**으로 자른다. 이름 없는(instance_name·domain 둘 다 없는) 시리즈는 버린다.
     */
    fun metricLine(
        query: TrendQuery,
        series: List<RangeSeries>,
        promql: String,
        unit: String,
        maxSeries: Int,
    ): MetricLineWidget {
        val label = METRIC_LABEL[query.metric] ?: query.metric.name
        val lines = series
            .map { s ->
                // GAUGE_RAW(클러스터) 시계열은 인스턴스 라벨이 없다 — 버리지 않고 폴백 이름을 쓴다.
                // enrich 조인 경로는 조인이 이름을 보장하므로 폴백이 발동하지 않는다.
                val name = s.labels["instance_name"] ?: s.labels["domain"]
                    ?: s.labels["name"] ?: s.labels["nodename"] ?: "전체"
                MetricLineSeries(
                    name = name,
                    projectName = s.labels["project_name"],
                    points = s.points,
                )
            }
            .sortedByDescending { it.points.lastOrNull()?.value ?: Double.NEGATIVE_INFINITY }
            .take(maxSeries)
        return MetricLineWidget(
            title = "$label 추이",
            unit = unit,
            range = query.range,
            promql = promql,
            series = lines,
            empty = lines.isEmpty(),
        )
    }

    /** cb_common COUNT 결과 → inventory_count 위젯(REAL). */
    fun inventoryCount(result: InventoryResult): InventoryCountWidget =
        InventoryCountWidget(
            label = INVENTORY_LABEL.getValue(result.kind),
            total = result.total,
            condition = describeCondition(result.appliedFilters),
        )

    // ── severity 변환 규칙(REAL, 테스트 대상) ──────────────────────────

    /** %<warn→GOOD, warn≤%<crit→WARN, %≥crit→CRIT. `%` 지표만, 그 외 null(§5.1). */
    fun severityForPercent(value: Double, unit: String, warnPercent: Int, critPercent: Int): Severity? {
        if (unit != "%") return null
        return when {
            value >= critPercent -> Severity.CRIT
            value >= warnPercent -> Severity.WARN
            else -> Severity.GOOD
        }
    }

    // ── MOCK: 신규 집계 미연동(1b) — 본문만 목업, 시그니처는 확정(2026-07-15) ───────────
    //
    // 시그니처 규약: 1a와 동일하게 **서비스가 조회해서 넣어준다**. WidgetBuilder는 순수 변환 object로
    // 남고 PrometheusClient를 의존하지 않는다. 라벨 있는 결과는 PrometheusClient.queryLabeled()로
    // 받는다 — query()는 instance_name/domain 없는 시계열을 버려서 1b 쿼리엔 못 쓴다.

    /**
     * status 분포 → status_donut 위젯(REAL).
     *
     * 호출부는 `queryLabeled("count by(status)(openstack_nova_server_status)")` 결과를 그대로 넘긴다.
     * (cb_common GROUP BY 대신 Prometheus가 더 단순 — DB 불필요. 라이브 검증 2026-07-09)
     *
     * @param samples labels["status"]별 개수. status 라벨이 없는 샘플은 제외한다.
     */
    fun statusDonut(samples: List<LabeledSample>, label: String = "인스턴스"): StatusDonutWidget {
        val segments = samples
            .mapNotNull { s -> s.labels["status"]?.let { it to s.value.toInt() } }
            // Prometheus 결과 순서는 보장되지 않는다 — 새로고침마다 도넛이 재배열되지 않도록 고정
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .map { (status, count) -> StatusSegment(status, count, levelForStatus(status)) }
        return StatusDonutWidget(
            label = label,
            total = segments.sumOf { it.count },
            segments = segments,
            empty = segments.isEmpty(),
        )
    }

    private fun levelForStatus(status: String): String = when (status.uppercase()) {
        "ACTIVE" -> "good"
        "ERROR" -> "crit"
        else -> "muted"   // SHUTOFF·PAUSED·전이 상태 등
    }

    /**
     * CPU 임계 초과 집계 → threshold_banner 위젯(REAL).
     *
     * 호출부는 `count( <CPU 사용률 식> > {crit} )`를 `queryLabeled`로 조회해 대수를 넘긴다.
     * ⚠️ PromQL `count()`는 매칭 0건이면 **0이 아니라 빈 벡터**를 준다 — `firstOrNull()?.value?.toInt() ?: 0`.
     *
     * @param count 임계 초과 대수. 쿼리 결과가 라벨 없는 스칼라라 서비스가 Int로 넘긴다.
     * @param offenders 초과 인스턴스명(detail 표기용). 비우면 detail=null — 프론트가 있을 때만 그리므로 안전.
     */
    fun thresholdBanner(count: Int, critPercent: Int, offenders: List<String> = emptyList()): ThresholdBannerWidget {
        val exceeded = count > 0
        return ThresholdBannerWidget(
            // level은 대문자 Severity — 프론트 threshold-banner.js의 LEVEL_CLASS가 대문자 키를 쓴다.
            // status_donut의 DonutLevel(소문자)과 반대다. 헷갈리면 배너가 통째로 "info"로 떨어진다.
            level = if (exceeded) Severity.CRIT else Severity.GOOD,
            title = if (exceeded) "CPU $critPercent% 초과 인스턴스 ${count}대" else "CPU $critPercent% 초과 인스턴스 없음",
            // 상한 없이 나열하면 폭주 시(121대 전부 초과 가능) 배너가 이름 벽이 된다.
            detail = offenders.takeIf { it.isNotEmpty() }?.let { names ->
                val shown = names.take(BANNER_NAME_LIMIT)
                val more = if (names.size > shown.size) " 외 ${names.size - shown.size}대" else ""
                "CPU $critPercent%↑ : ${shown.joinToString(", ")}$more"
            },
            count = count,
        )
    }

    /**
     * 이름별 사용률 → usage_bar 위젯(REAL). IP_USAGE·CAPACITY 공용.
     *
     * NaN/±Inf는 고장 값이라 버린다(정렬 시 1위로 튀어 상위 슬롯을 먹는다). 값 내림차순,
     * 상한은 application.yml(불변식 7). display를 지정한 항목은 그대로 표시한다(용량 절대값 등).
     */
    fun usageBar(
        title: String,
        unit: String,
        inputs: List<UsageInput>,
        warnPercent: Int,
        critPercent: Int,
        topN: Int,
    ): UsageBarWidget {
        val rows = inputs
            .filter { it.value.isFinite() }
            .sortedWith(compareByDescending<UsageInput> { it.value }.thenBy { it.name })
            .take(topN)
            .map {
                UsageRow(
                    name = it.name,
                    value = it.value,
                    display = it.display ?: MetricValueFormatter.format(it.value, unit),
                    severity = severityForPercent(it.value, unit, warnPercent, critPercent),
                )
            }
        return UsageBarWidget(title = title, unit = unit, rows = rows, empty = rows.isEmpty())
    }

    /**
     * 다운 에이전트 목록 → threshold_banner 위젯(REAL, 타입 재사용 — 프론트 변경 0).
     * 호출부는 `agent_state == 0` 시리즈를 "service@hostname"으로 넘긴다. 0건이면 GOOD 안심 배너.
     */
    fun agentDownBanner(offenders: List<String>): ThresholdBannerWidget {
        val down = offenders.isNotEmpty()
        return ThresholdBannerWidget(
            level = if (down) Severity.CRIT else Severity.GOOD,
            title = if (down) "다운된 에이전트 ${offenders.size}개" else "모든 에이전트 정상",
            detail = offenders.takeIf { it.isNotEmpty() }?.let { names ->
                val shown = names.take(BANNER_NAME_LIMIT)
                val more = if (names.size > shown.size) " 외 ${names.size - shown.size}개" else ""
                "다운: ${shown.joinToString(", ")}$more"
            },
            count = offenders.size,
        )
    }

    private fun describeCondition(f: InventoryFilters): String? {
        val parts = buildList {
            f.status?.let {
                val op = if (f.statusOp == InventoryFilters.Op.NEQ) "≠" else "="
                add("상태$op$it")
            }
            f.projectId?.let { add("프로젝트=$it") }
            f.hypervisorHostName?.let { add("호스트=$it") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
