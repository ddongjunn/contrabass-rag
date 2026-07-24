package com.okestro.ragbot.resource.domain

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * 위젯 severity(색상 인코딩). 백엔드가 임계치(application.yml) 기준으로 계산해 채운다.
 * 프론트는 서버가 준 값만 신뢰(설계 §5.1).
 */
enum class Severity { GOOD, WARN, CRIT }

/**
 * 웹 위젯 계약(설계 §13). sealed + Jackson 폴리모픽으로 `type` 판별자를 낸다.
 * EXISTING_PROPERTY: 각 구현체가 `type` 상수를 보유 → 직렬화/역직렬화 대칭.
 * 전역 default typing 미사용(어노테이션 기반) — Spring AI ObjectMapper와 충돌 회피(§4.1).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = MetricRankWidget::class, name = "metric_rank"),
    JsonSubTypes.Type(value = InventoryCountWidget::class, name = "inventory_count"),
    JsonSubTypes.Type(value = ProjectUsageBarWidget::class, name = "project_usage_bar"),
    JsonSubTypes.Type(value = StatusDonutWidget::class, name = "status_donut"),
    JsonSubTypes.Type(value = ThresholdBannerWidget::class, name = "threshold_banner"),
    JsonSubTypes.Type(value = MetricLineWidget::class, name = "metric_line"),
    // Phase 2: JsonSubTypes.Type(ResourceDashboardWidget::class, name = "resource_dashboard")
)
sealed interface Widget { val type: String }

/** Prometheus TopN 랭킹. ResourceQuery + List<MetricSample> → 변환. */
data class MetricRankWidget(
    val title: String,          // "CPU 사용률이 높은 인스턴스"
    val unit: String,           // "%", "B/s", "req/s" (원단위)
    val window: String,         // "5m"
    val promql: String,         // 근거 표기(환각 방지)
    val rows: List<MetricRankRow>,
    val empty: Boolean = false, // 결과 0건
) : Widget { override val type = "metric_rank" }

data class MetricRankRow(
    val instanceName: String,
    val projectName: String?,   // 없을 수 있음
    val value: Double,          // 정렬·바 길이 계산용 원단위 값
    val display: String,        // 포맷 완료 문자열 "91.2%", "410 MB/s" (MetricValueFormatter)
    val severity: Severity?,    // % 지표만 채움; B/s·req/s는 null(색상=액센트)
    val spark: List<Double>? = null,  // 1b: range 쿼리 시계열(옵션). 없으면 스파크라인 미표시
)

/** cb_common COUNT 결과. InventoryResult → 변환. */
data class InventoryCountWidget(
    val label: String,          // "볼륨", "인스턴스", "스냅샷"
    val total: Int,
    val condition: String?,     // "상태=available · 프로젝트 전체" (없으면 null)
) : Widget { override val type = "inventory_count" }

// ---- Phase 1b (신규 집계 필요, 설계 §5.4) ----

/** PromQL by(project) 집계. */
data class ProjectUsageBarWidget(
    val metric: String,         // "CPU", "메모리" ...
    val unit: String,
    val rows: List<ProjectUsageRow>,
    val empty: Boolean = false, // 결과 0건 → 빈 상태 카드(없으면 제목만 있는 유령 카드가 뜬다)
) : Widget { override val type = "project_usage_bar" }

data class ProjectUsageRow(
    val projectName: String,
    val value: Double?,     // 무제한(쿼터 max=-1) → null. 프론트가 muted 100% 바로 그린다(계약 d.ts).
    val display: String,    // "82.0%" 또는 "무제한"
    val severity: Severity?, // 무제한이면 색을 못 매긴다 → null
)

/** Prometheus count by(status) 집계. */
data class StatusDonutWidget(
    val label: String,          // "인스턴스"
    val total: Int,
    val segments: List<StatusSegment>,
    val empty: Boolean = false, // 결과 0건 → 빈 상태 카드(프론트 dispatch가 widget.empty로 분기)
) : Widget { override val type = "status_donut" }

data class StatusSegment(val status: String, val count: Int, val level: String)  // level: good|warn|crit|muted

/** Prometheus query_range 시계열 → 라인그래프. TrendQuery + List<RangeSeries> → 변환. */
data class MetricLineWidget(
    val title: String,          // "CPU 사용률 추이"
    val unit: String,           // "%", "B/s", "req/s"
    val range: String,          // "1h" — 조회 구간
    val promql: String,         // 근거 표기(환각 방지)
    val series: List<MetricLineSeries>,
    val empty: Boolean = false,
) : Widget { override val type = "metric_line" }

data class MetricLineSeries(
    val name: String,           // instance_name ?: domain
    val projectName: String?,
    val points: List<TimePoint>, // (ts, value) — RangeSeries와 동일 좌표 타입
)

/** 임계 초과 콜아웃 배너. */
data class ThresholdBannerWidget(
    val level: Severity,        // 보통 CRIT/WARN
    val title: String,          // "임계 초과 노드 2대"
    val detail: String?,        // "CPU 85%↑ : web-prod-07, api-prod-02"
    val count: Int,
) : Widget { override val type = "threshold_banner" }
