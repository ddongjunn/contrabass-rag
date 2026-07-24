package com.okestro.ragbot.resource.domain

sealed class ResourceExtraction {
    /** METRIC(Prometheus) 트랙 결과. */
    data class Resolved(val query: ResourceQuery) : ResourceExtraction()

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

    /**
     * QUOTA 트랙 — 특정 프로젝트의 쿼터 사용량(quota_gauge).
     *
     * project가 null이면(질문에 명시 안 됨) 서비스 계층이 호출부 컨텍스트(포털이 아는 현재
     * 프로젝트)로 폴백을 시도한다 — 그래도 없으면 그때 되묻는다(DefaultResourceService).
     */
    data class QuotaResolved(val project: String?) : ResourceExtraction()

    /**
     * PROJECT_USAGE 트랙 — tenant별 vCPU 쿼터 사용률 바(project_usage_bar).
     * quota_gauge가 "한 프로젝트의 여러 자원"이라면 이쪽은 "여러 프로젝트의 한 자원"이라 조건이 없다.
     */
    data object ProjectUsageResolved : ResourceExtraction()

    data class NeedsClarification(val message: String) : ResourceExtraction()
}
