package com.okestro.ragbot.resource.domain

sealed class ResourceExtraction {
    /** METRIC(Prometheus) 트랙 결과. */
    data class Resolved(val query: ResourceQuery) : ResourceExtraction()

    /** TREND(Prometheus query_range) 트랙 결과 — 시계열 추이(metric_line). */
    data class TrendResolved(val query: TrendQuery) : ResourceExtraction()

    /** INVENTORY(cb_common DB) 트랙 결과. */
    data class InventoryResolved(val query: InventoryQuery) : ResourceExtraction()

    /**
     * STATUS 트랙 — 인스턴스 상태 분포(status_donut). 조건이 없다: 쿼리가 고정이고
     * (count by(status)(...)) 필터도 안 받으므로 파라미터가 필요 없다.
     */
    data object StatusResolved : ResourceExtraction()

    /**
     * THRESHOLD 트랙 — CPU 임계 초과(threshold_banner). 임계값은 질문이 아니라
     * application.yml(app.resource.severity.crit-percent)에서 오므로 파라미터가 없다.
     */
    data object ThresholdResolved : ResourceExtraction()

    /** IP_USAGE 트랙 — 네트워크별 IP 사용률(usage_bar). 쿼리 고정, 조건 없음. */
    data object IpUsageResolved : ResourceExtraction()

    /** CAPACITY 트랙 — 스토리지(Ceph 클러스터·cinder 백엔드) 용량 사용률(usage_bar). 조건 없음. */
    data object CapacityResolved : ResourceExtraction()

    /** AGENT 트랙 — 다운된 OpenStack 에이전트 검사(threshold_banner 재사용). 조건 없음. */
    data object AgentResolved : ResourceExtraction()

    data class NeedsClarification(val message: String) : ResourceExtraction()
}
