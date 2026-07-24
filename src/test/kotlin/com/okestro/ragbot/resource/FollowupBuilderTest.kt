package com.okestro.ragbot.resource

import com.okestro.ragbot.resource.application.FollowupBuilder
import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.MetricSample
import com.okestro.ragbot.resource.domain.ResourceQuery
import com.okestro.ragbot.resource.domain.TrendQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 연관질문 칩 규칙. 칩 문장은 전부 실측으로 라우터/추출기를 통과한 것만 쓴다
 * (칩 전수 실측 2026-07-24 — "TopN" 같은 문구는 되물음으로 샜다).
 */
class FollowupBuilderTest {

    private fun sample(name: String = "web-01", project: String? = "prod") =
        MetricSample(name, 50.0, "%", project)

    @Test
    fun `metric 칩 - 지표 전환·프로젝트 필터·추이 전환 순으로 최대 3개`() {
        val chips = FollowupBuilder.forMetric(ResourceQuery(MetricPattern.INSTANCE_CPU), listOf(sample()))

        assertEquals(listOf("web-01 메모리는?", "prod만 보기", "추이로 보여줘"), chips)
    }

    @Test
    fun `metric 칩 - 결과 없으면 칩 없음`() {
        assertTrue(FollowupBuilder.forMetric(ResourceQuery(MetricPattern.INSTANCE_CPU), emptyList()).isEmpty())
    }

    @Test
    fun `trend 칩 - 다른 지표의 추이로 안내(자립 문장)`() {
        assertEquals(
            listOf("메모리 사용률 추이 보여줘"),
            FollowupBuilder.forTrend(TrendQuery(MetricPattern.INSTANCE_CPU)),
        )
        assertEquals(
            listOf("CPU 사용률 추이 보여줘"),
            FollowupBuilder.forTrend(TrendQuery(MetricPattern.INSTANCE_MEMORY)),
        )
        // 클러스터 시계열도 CPU 추이로 안내
        assertEquals(
            listOf("CPU 사용률 추이 보여줘"),
            FollowupBuilder.forTrend(TrendQuery(MetricPattern.TOTAL_VMS)),
        )
    }

    @Test
    fun `capacity·agent·ipUsage 칩 - 인접 조회로 안내`() {
        assertEquals(listOf("스토리지 사용량 추이 어때?"), FollowupBuilder.forCapacity())
        assertEquals(listOf("인스턴스 상태 분포 알려줘"), FollowupBuilder.forAgent())
        assertEquals(listOf("스토리지 용량 얼마나 남았어?"), FollowupBuilder.forIpUsage())
    }
}
