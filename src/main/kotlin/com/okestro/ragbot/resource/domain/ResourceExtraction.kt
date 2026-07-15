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
     * STATUS/THRESHOLD와 달리 **조건이 하나 있다**: 어느 테넌트인가. QuotaGaugeWidget은 한 테넌트의
     * vCPU/메모리/디스크를 보여주는 위젯이라 대상이 특정돼야 한다(실측 테넌트 43개). 프로젝트가
     * 없으면 추출기가 NeedsClarification으로 되묻는다.
     */
    data class QuotaResolved(val project: String) : ResourceExtraction()

    data class NeedsClarification(val message: String) : ResourceExtraction()
}
