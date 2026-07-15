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

    /** @param inputs 대상 tenant의 resource별 (used, max). max<0=무제한. 서비스가 6개 메트릭을 짝지어 넘긴다. */
    fun quotaGauge(inputs: List<QuotaInput>, warnPercent: Int, critPercent: Int): QuotaGaugeWidget =
        // TODO(new-dev): 아래 목업을 실제 쿼터로 교체. (quotaItem 변환 규칙은 이미 완성 — 그대로 재사용)
        //  ── 시그니처 확정: inputs.map { quotaItem(it.resource, it.used, it.max, warnPercent, critPercent) } 면 끝.
        //     서비스가 queryLabeled로 6개 메트릭 조회 → labels["tenant"]로 max/used 짝짓기 → QuotaInput 생성.
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
     * @param count 임계 초과 대수. 쿼리 결과가 라벨 없는 스칼라라 서비스가 Int로 넘긴다.
     * @param offenders 초과 인스턴스명(detail 표기용). 비우면 detail=null — 프론트가 있을 때만 그리므로 안전.
     */
    fun thresholdBanner(count: Int, critPercent: Int, offenders: List<String> = emptyList()): ThresholdBannerWidget =
        // TODO(new-dev): 아래 목업을 실제 임계 집계로 교체. (참조 백엔드엔 이런 쿼리 없음 — 우리가 자작)
        //  ── 쿼리: 우리가 이미 쓰는 CPU 사용률 식(ratio_topk 내부)에 임계 비교.
        //     count( (sum by(domain)(rate(libvirt_domain_info_cpu_time_seconds_total[5m])) / on(domain) max by(domain)(libvirt_domain_info_virtual_cpus) * 100) > {crit} )
        //     → 라벨 없는 스칼라 1건. queryLabeled(...).firstOrNull()?.value?.toInt() ?: 0.
        //     offenders는 count() 없는 같은 식을 queryLabeled로 한 번 더 → labels["instance_name"]. 안 채워도 됨(옵션).
        //  ── 임계값 {crit} = app.resource.severity.crit-percent 재사용(하드코딩 금지, 불변식 7).
        //  ── level: count>0 → CRIT. detail = "CPU {crit}%↑ : name1, name2".
        ThresholdBannerWidget(
            level = Severity.CRIT,
            title = "임계 초과 노드 2대",
            detail = "CPU 85%↑ : web-prod-07, api-prod-02",
            count = 2,
        )

    /** @param samples tenant별 사용률(%) 결과. labels["tenant"] = 프로젝트 이름, value = 퍼센트. */
    fun projectUsageBar(
        samples: List<LabeledSample>,
        metric: String,
        unit: String,
        warnPercent: Int,
        critPercent: Int,
    ): ProjectUsageBarWidget =
        // TODO(new-dev): 아래 목업을 실제 집계로 교체.
        //  ── 주의(설계 정정): "프로젝트별 실사용률" 단일 소스는 없음(참조·라이브 모두 확인).
        //     → 프로젝트별 "쿼터 사용률"(used/max)로 재정의. quotaGauge와 같은 openstack_*_limits_* 재사용.
        //  ── 구현: PromQL이 나눗셈까지 처리 가능 →
        //     (openstack_nova_limits_vcpus_used / openstack_nova_limits_vcpus_max) * 100  (tenant 라벨 보존, 쿼리 1방)
        //     → samples.map { ProjectUsageRow(it.labels["tenant"], it.value, format, severityForPercent(...)) }
        //  ── ⚠️ 무제한(max=-1)이면 비율이 음수로 나온다. ProjectUsageRow.value가 non-null Double이라 null을 못 넣으니
        //     해당 행은 걸러내는 게 맞다(`> 0` 필터 또는 PromQL에서 제외). 설계 주석의 "value=null"은 타입과 안 맞음.
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
