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

    // ── MOCK: 신규 집계 미연동(1b) — new-dev가 채우는 seam(설계 §5.4) ───────────

    fun quotaGauge(warnPercent: Int, critPercent: Int): QuotaGaugeWidget =
        // TODO(new-dev): 아래 목업을 실제 쿼터로 교체. (quotaItem 변환 규칙은 이미 완성 — 그대로 재사용)
        //  ── 소스(라이브 검증 2026-07-09): Prometheus openstack-exporter. cb_common 아님.
        //       vCPU:   openstack_nova_limits_vcpus_max        / openstack_nova_limits_vcpus_used
        //       메모리: openstack_nova_limits_memory_max       / openstack_nova_limits_memory_used   (단위 MB, 예 51200=50GB)
        //       디스크: openstack_cinder_limits_volume_max_gb  / openstack_cinder_limits_volume_used_gb
        //  ── 라벨: tenant(프로젝트 이름, 그대로 표기) + tenant_id(uuid). 이름 조인 불필요.
        //  ── 무제한: max=-1 실관측 → quotaItem(max<0)이 quota/ratio/severity=null, "N / 무제한" 처리(그대로 사용).
        //  ── 구현: 대상 tenant의 6개 메트릭을 instant 조회(HttpPrometheusClient) → resource별로 quotaItem(name, used, max, warn, crit).
        //  ── 트리거: "쿼터/사용량" 의도 필요(현재 추출기에 없음) — R1 추출기 확장 또는 요약 경로에서 호출. 설계 §5.4
        QuotaGaugeWidget(
            items = listOf(
                quotaItem("vCPU", 820.0, 1000.0, warnPercent, critPercent),
                quotaItem("메모리(GB)", 512.0, -1.0, warnPercent, critPercent),
            ),
        )

    fun statusDonut(): StatusDonutWidget =
        // TODO(new-dev): 아래 목업을 실제 집계로 교체.
        //  ── 검증된 쿼리(라이브 2026-07-09): count by(status)(openstack_nova_server_status)
        //     → status 라벨별 개수. 실측: ACTIVE=116, SHUTOFF=2, ERROR=1. (cb_common GROUP BY 대신 이게 더 단순 — DB 불필요)
        //  ── 구현: 결과 Map<status,count>를 받아 segment로 매핑. total=합. level: ACTIVE→"good", SHUTOFF/PAUSED→"muted", ERROR→"crit".
        //  ── 트리거: "상태 분포" 의도 필요(현재 라우터/추출엔 없음) — R1 추출기에 추가 또는 요약 경로에서 호출. 설계 §5.4·Phase2
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
        // TODO(new-dev): 아래 목업을 실제 임계 집계로 교체. (참조 백엔드엔 이런 쿼리 없음 — 우리가 자작)
        //  ── 쿼리: 우리가 이미 쓰는 CPU 사용률 식(ratio_topk 내부)에 임계 비교.
        //     count( (sum by(domain)(rate(libvirt_domain_info_cpu_time_seconds_total[5m])) / on(domain) max by(domain)(libvirt_domain_info_virtual_cpus) * 100) > {crit} )
        //     → 초과 대수(count). 초과 인스턴스명은 임계 필터한 같은 식의 결과 라벨(instance_name)에서 추출.
        //  ── 임계값 {crit} = app.resource.severity.crit-percent 재사용(하드코딩 금지, 불변식 7).
        //  ── level: count>0 → CRIT. detail = "CPU {crit}%↑ : name1, name2".
        ThresholdBannerWidget(
            level = Severity.CRIT,
            title = "임계 초과 노드 2대",
            detail = "CPU 85%↑ : web-prod-07, api-prod-02",
            count = 2,
        )

    fun projectUsageBar(warnPercent: Int, critPercent: Int): ProjectUsageBarWidget =
        // TODO(new-dev): 아래 목업을 실제 집계로 교체.
        //  ── 주의(설계 정정): "프로젝트별 실사용률" 단일 소스는 없음(참조·라이브 모두 확인).
        //     → 프로젝트별 "쿼터 사용률"(used/max)로 재정의. quotaGauge와 같은 openstack_*_limits_* 재사용.
        //  ── 구현: tenant별 (nova_limits_vcpus_used / _max)*100 → ProjectUsageRow(projectName=tenant, value, ...).
        //     무제한(max=-1) 행: value=null, severity=null, display "무제한".
        //  ── severity: severityForPercent(value, "%", warn, crit) 그대로 재사용.
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
