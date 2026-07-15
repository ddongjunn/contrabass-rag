package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.InventoryFilters
import com.okestro.ragbot.resource.domain.InventoryKind
import com.okestro.ragbot.resource.domain.InventoryResult
import com.okestro.ragbot.resource.domain.InventoryCountWidget
import com.okestro.ragbot.resource.domain.LabeledSample
import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.MetricRankRow
import com.okestro.ragbot.resource.domain.MetricRankWidget
import com.okestro.ragbot.resource.domain.MetricSample
import com.okestro.ragbot.resource.domain.ProjectUsageBarWidget
import com.okestro.ragbot.resource.domain.ProjectUsageRow
import com.okestro.ragbot.resource.domain.QuotaGaugeWidget
import com.okestro.ragbot.resource.domain.QuotaInput
import com.okestro.ragbot.resource.domain.QuotaItem
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

    /** project_usage_bar 표시 상한. 실측 테넌트 27개(무제한 제외)를 다 그리면 채팅창이 도배된다. */
    private const val PROJECT_USAGE_TOP_N = 10

    private val METRIC_LABEL = mapOf(
        MetricPattern.INSTANCE_CPU        to "CPU 사용률",
        MetricPattern.INSTANCE_MEMORY     to "메모리 사용률",
        MetricPattern.INSTANCE_NETWORK_RX to "네트워크 수신량",
        MetricPattern.INSTANCE_NETWORK_TX to "네트워크 송신량",
        MetricPattern.INSTANCE_DISK_READ  to "디스크 읽기량",
        MetricPattern.INSTANCE_DISK_WRITE to "디스크 쓰기량",
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

    /** cb_common COUNT 결과 → inventory_count 위젯(REAL). */
    fun inventoryCount(result: InventoryResult): InventoryCountWidget =
        InventoryCountWidget(
            label = INVENTORY_LABEL.getValue(result.kind),
            total = result.total,
            condition = describeCondition(result.appliedFilters),
        )

    // ── severity / quota 변환 규칙(REAL, 테스트 대상) ──────────────────────────

    /** %<warn→GOOD, warn≤%<crit→WARN, %≥crit→CRIT. `%` 지표만, 그 외 null(§5.1). */
    fun severityForPercent(value: Double, unit: String, warnPercent: Int, critPercent: Int): Severity? {
        if (unit != "%") return null
        return when {
            value >= critPercent -> Severity.CRIT
            value >= warnPercent -> Severity.WARN
            else -> Severity.GOOD
        }
    }

    /** 쿼터 한 항목 변환. quota<0(무제한) → quota/ratio/severity null, display "N / 무제한". */
    fun quotaItem(resource: String, used: Double, quota: Double, warnPercent: Int, critPercent: Int): QuotaItem {
        if (quota < 0) {
            return QuotaItem(resource, used, null, null, "${"%.0f".format(used)} / 무제한", null)
        }
        val ratio = if (quota == 0.0) 0.0 else used / quota
        return QuotaItem(
            resource = resource,
            used = used,
            quota = quota,
            ratio = ratio,
            display = "${"%.0f".format(used)} / ${"%.0f".format(quota)}",
            severity = severityForPercent(ratio * 100, "%", warnPercent, critPercent),
        )
    }

    // ── MOCK: 신규 집계 미연동(1b) — 본문만 목업, 시그니처는 확정(2026-07-15) ───────────
    //
    // 시그니처 규약: 1a와 동일하게 **서비스가 조회해서 넣어준다**. WidgetBuilder는 순수 변환 object로
    // 남고 PrometheusClient를 의존하지 않는다. 라벨 있는 결과는 PrometheusClient.queryLabeled()로
    // 받는다 — query()는 instance_name/domain 없는 시계열을 버려서 1b 쿼리엔 못 쓴다.

    /**
     * 쿼터 사용량 → quota_gauge 위젯(REAL).
     *
     * 소스(라이브 검증 2026-07-15): Prometheus openstack-exporter. cb_common 아님.
     * 라벨은 tenant(=프로젝트 이름, 조인 불필요) + tenant_id. 무제한(max=-1)은 실존한다.
     * 단위 환산(메모리 MB→GB)은 서비스가 끝내서 넘긴다 — 빌더는 표시 규칙만 안다.
     *
     * @param inputs 대상 tenant의 resource별 (used, max). max<0 = 무제한.
     */
    fun quotaGauge(inputs: List<QuotaInput>, warnPercent: Int, critPercent: Int): QuotaGaugeWidget =
        QuotaGaugeWidget(
            items = inputs.map { quotaItem(it.resource, it.used, it.max, warnPercent, critPercent) },
        )

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

    /**
     * 도넛 세그먼트 색. **소문자**여야 한다 — 프론트 status-donut.js의 LEVEL_CLASS가 소문자 키만
     * 가져서 Severity.name(대문자)을 넣으면 전부 seg-muted 회색으로 죽는다(계약: DonutLevel).
     */
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
            detail = offenders.takeIf { it.isNotEmpty() }?.let { "CPU $critPercent%↑ : ${it.joinToString(", ")}" },
            count = count,
        )
    }

    /**
     * tenant별 쿼터 사용률 → project_usage_bar 위젯(REAL).
     *
     * ⚠️ 무제한(max=-1) 테넌트는 비율이 음수이거나 **-0.0**이다. ProjectUsageRow.value가 non-null이라
     * 넣을 수 없고, -0.0을 통과시키면 무제한이 "0% 사용"으로 둔갑한다. 호출부가 PromQL에서 이미
     * 거르지만(`max > 0`), 소스가 바뀌어도 안전하도록 여기서도 막는다.
     *
     * @param samples labels["tenant"] = 프로젝트 이름, value = 퍼센트.
     */
    fun projectUsageBar(
        samples: List<LabeledSample>,
        metric: String,
        unit: String,
        warnPercent: Int,
        critPercent: Int,
    ): ProjectUsageBarWidget {
        val rows = samples
            .mapNotNull { s -> s.labels["tenant"]?.let { it to s.value } }
            // 무제한 방어: 음수(-9600 등)뿐 아니라 **-0.0**도 막아야 한다. `v >= 0`은 -0.0을 통과시키고,
            // 그러면 무제한 테넌트가 "0% 사용"으로 보인다. 부호 비트로 판별한다.
            .filterNot { (_, v) -> v < 0.0 || (v == 0.0 && 1.0 / v < 0) }
            // Prometheus 결과 순서는 보장되지 않는다 — 새로고침마다 바가 재배열되지 않도록 고정
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            // 실측 27개 테넌트를 다 그리면 채팅창이 0.0% 바로 도배된다(실제 화면에서 확인).
            // 사용률 높은 순이라 잘리는 건 안 쓰는 프로젝트뿐 — 평문 answer가 "외 N개"로 알려준다.
            .take(PROJECT_USAGE_TOP_N)
            .map { (tenant, pct) ->
                ProjectUsageRow(
                    projectName = tenant,
                    value = pct,
                    display = MetricValueFormatter.format(pct, unit),
                    severity = severityForPercent(pct, unit, warnPercent, critPercent),
                )
            }
        return ProjectUsageBarWidget(metric = metric, unit = unit, rows = rows)
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
