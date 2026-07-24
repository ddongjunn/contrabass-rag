package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.InventoryKind
import com.okestro.ragbot.resource.domain.InventoryResult
import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.MetricSample
import com.okestro.ragbot.resource.domain.ResourceQuery
import com.okestro.ragbot.resource.domain.TrendQuery

/**
 * 연관질문 칩(`followups`) 규칙 생성(설계 §7.1). LLM 0회 — 이미 추출한 ResourceQuery와
 * top 결과만으로 규칙 조합. 최대 3개, RESOURCE·INVENTORY 경로에만.
 */
object FollowupBuilder {

    /**
     * metric 경로: top 인스턴스 지표 전환 + 프로젝트 필터 + **추이 전환**.
     * "추이로 보여줘"는 문맥 의존이지만 히스토리 배선 후 실측 통과(2026-07-24) — TREND 발견 경로가 된다.
     */
    fun forMetric(query: ResourceQuery, samples: List<MetricSample>): List<String> {
        val top = samples.firstOrNull() ?: return emptyList()
        return buildList {
            add("${top.instanceName} ${alternateMetricLabel(query.metric)}는?")
            if (query.project == null && top.projectName != null) add("${top.projectName}만 보기")
            add("추이로 보여줘")
            if (query.metric != MetricPattern.INSTANCE_NETWORK_TX) add("네트워크 송신량 상위 5개")
        }.take(3)
    }

    /** trend 경로: 다른 지표의 추이로 전환(자립 문장 — 클러스터 시계열 포함). */
    fun forTrend(query: TrendQuery): List<String> = listOf(
        if (query.metric == MetricPattern.INSTANCE_CPU) "메모리 사용률 추이 보여줘" else "CPU 사용률 추이 보여줘",
    )

    fun forCapacity(): List<String> = listOf("스토리지 사용량 추이 어때?")

    fun forAgent(): List<String> = listOf("인스턴스 상태 분포 알려줘")

    fun forIpUsage(): List<String> = listOf("스토리지 용량 얼마나 남았어?")

    /** inventory 경로: 인접 리소스 개수 확장. */
    fun forInventory(result: InventoryResult): List<String> = when (result.kind) {
        InventoryKind.INSTANCE -> listOf("인스턴스 스냅샷은 몇 개야?", "볼륨은 몇 개야?")
        InventoryKind.VOLUME -> listOf("볼륨 스냅샷은 몇 개야?", "인스턴스는 몇 개야?")
        InventoryKind.INSTANCE_SNAPSHOT -> listOf("인스턴스는 몇 개야?")
        InventoryKind.VOLUME_SNAPSHOT -> listOf("볼륨은 몇 개야?")
    }.take(3)

    private fun alternateMetricLabel(metric: MetricPattern): String =
        if (metric == MetricPattern.INSTANCE_MEMORY) "CPU" else "메모리"
}
