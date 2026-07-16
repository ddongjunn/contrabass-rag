package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.InventoryKind
import com.okestro.ragbot.resource.domain.InventoryResult
import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.MetricSample
import com.okestro.ragbot.resource.domain.ResourceQuery

/**
 * 연관질문 칩(`followups`) 규칙 생성(설계 §7.1). LLM 0회 — 이미 추출한 ResourceQuery와
 * top 결과만으로 규칙 조합. 최대 3개, RESOURCE·INVENTORY 경로에만.
 *
 * ⚠️ 칩 문구는 **그 자체로 완결된 질문**이어야 한다. 클릭하면 이 문자열이 그대로 다음 질문이 되는데,
 * ChatRequest에는 history가 없어서(REST는 빈 목록) 라우터는 이 한 줄만 보고 판단한다.
 * 맥락에 기대면 CLARIFY로 새고 칩은 죽는다 — 실측(2026-07-16) "admin만 보기" → CLARIFY.
 */
object FollowupBuilder {

    /** metric 경로: top 인스턴스 지표 전환 + 프로젝트 필터 + 다른 지표 전환. */
    fun forMetric(query: ResourceQuery, samples: List<MetricSample>): List<String> {
        val top = samples.firstOrNull() ?: return emptyList()
        return buildList {
            add("${top.instanceName} 인스턴스 ${alternateMetricLabel(query.metric)} 사용률은?")
            if (query.project == null && top.projectName != null) {
                add("${top.projectName} 프로젝트 ${metricLabel(query.metric)} TopN")
            }
            if (query.metric != MetricPattern.INSTANCE_NETWORK_TX) add("네트워크 송신량 TopN")
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

    /** 위젯 제목과 같은 라벨을 쓴다 — 카드엔 "CPU 사용률", 칩엔 다른 말이면 같은 걸 가리키는지 알 수 없다. */
    private fun metricLabel(metric: MetricPattern): String =
        WidgetBuilder.METRIC_LABEL[metric] ?: metric.name
}
