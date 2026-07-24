package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.InventoryKind
import com.okestro.ragbot.resource.domain.InventoryResult
import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.MetricSample
import com.okestro.ragbot.resource.domain.ResourceQuery

/**
 * 연관질문 칩(`followups`) 규칙 생성(설계 §7.1). LLM 0회 — 이미 추출한 ResourceQuery와
 * top 결과만으로 규칙 조합. 최대 3개, RESOURCE·INVENTORY 경로에만.
 */
object FollowupBuilder {

    /** metric 경로: top 인스턴스 지표 전환 + 프로젝트 필터 + 다른 지표 전환. */
    fun forMetric(query: ResourceQuery, samples: List<MetricSample>): List<String> {
        val top = samples.firstOrNull() ?: return emptyList()
        return buildList {
            add("${top.instanceName} ${alternateMetricLabel(query.metric)}는?")
            if (query.project == null && top.projectName != null) add("${top.projectName}만 보기")
            if (query.metric != MetricPattern.INSTANCE_NETWORK_TX) add("네트워크 송신량 상위 5개")
        }.take(3)
    }

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
