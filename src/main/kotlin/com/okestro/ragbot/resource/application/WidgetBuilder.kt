package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.InventoryFilters
import com.okestro.ragbot.resource.domain.InventoryKind
import com.okestro.ragbot.resource.domain.InventoryResult
import com.okestro.ragbot.resource.domain.InventoryCountWidget
import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.MetricRankRow
import com.okestro.ragbot.resource.domain.MetricRankWidget
import com.okestro.ragbot.resource.domain.MetricSample
import com.okestro.ragbot.resource.domain.ProjectUsageBarWidget
import com.okestro.ragbot.resource.domain.ProjectUsageRow
import com.okestro.ragbot.resource.domain.QuotaGaugeWidget
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
                // TODO(new-dev): 1b 스파크라인 — Prometheus range 쿼리(/api/v1/query_range)로 행별 시계열을 채운다. 설계 §5.4
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

    // ── MOCK: 신규 집계 미연동(1b) — new-dev가 채우는 seam(설계 §5.4) ───────────

    fun quotaGauge(warnPercent: Int, critPercent: Int): QuotaGaugeWidget =
        // TODO(new-dev): replace mock with real query — 쿼터 데이터 소스 확인 후 연결. 설계 §5.4
        QuotaGaugeWidget(
            items = listOf(
                quotaItem("vCPU", 820.0, 1000.0, warnPercent, critPercent),
                quotaItem("메모리(GB)", 512.0, -1.0, warnPercent, critPercent),
            ),
        )

    fun statusDonut(): StatusDonutWidget =
        // TODO(new-dev): replace mock with real query — cb_common GROUP BY status. 설계 §5.4
        StatusDonutWidget(
            label = "인스턴스",
            total = 140,
            segments = listOf(
                StatusSegment("ACTIVE", 128, "good"),
                StatusSegment("SHUTOFF", 9, "muted"),
                StatusSegment("ERROR", 3, "crit"),
            ),
        )

    fun thresholdBanner(): ThresholdBannerWidget =
        // TODO(new-dev): replace mock with real query — PromQL count(metric>threshold) + 초과 인스턴스명. 설계 §5.4
        ThresholdBannerWidget(
            level = Severity.CRIT,
            title = "임계 초과 노드 2대",
            detail = "CPU 85%↑ : web-prod-07, api-prod-02",
            count = 2,
        )

    fun projectUsageBar(warnPercent: Int, critPercent: Int): ProjectUsageBarWidget =
        // TODO(new-dev): replace mock with real query — PromQL avg by(project). 설계 §5.4
        ProjectUsageBarWidget(
            metric = "CPU",
            unit = "%",
            rows = listOf(
                ProjectUsageRow("service-prod", 78.4, "78.4%", severityForPercent(78.4, "%", warnPercent, critPercent)),
                ProjectUsageRow("data-platform", 61.9, "61.9%", severityForPercent(61.9, "%", warnPercent, critPercent)),
            ),
        )

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
