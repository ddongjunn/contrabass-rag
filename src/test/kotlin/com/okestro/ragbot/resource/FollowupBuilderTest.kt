package com.okestro.ragbot.resource

import com.okestro.ragbot.resource.application.FollowupBuilder
import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.MetricSample
import com.okestro.ragbot.resource.domain.ResourceQuery
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 연관질문 칩의 **자립성** 가드.
 *
 * 칩은 클릭하면 그 문자열이 **그대로** POST /api/chat의 question이 된다. 그런데 ChatRequest에는
 * history 필드가 없다(REST는 빈 목록) — 즉 라우터는 칩 문장 **한 줄만** 보고 판단한다.
 * 맥락에 기대는 문구를 만들면 라우터가 CLARIFY로 보내고 칩은 100% 죽는다.
 *
 * 실측(2026-07-16): "admin만 보기" 클릭 → CLARIFY("질문의 유형을 파악하기 어렵습니다").
 * 라우터는 정상이었다. 우리가 못 읽히는 문장을 만든 것이다.
 */
class FollowupBuilderTest {

    private val samples = listOf(
        MetricSample(instanceName = "web-prod-07", value = 91.2, unit = "%", projectName = "admin"),
        MetricSample(instanceName = "batch-11", value = 73.4, unit = "%", projectName = "data"),
    )

    private fun query(metric: MetricPattern, project: String? = null) =
        ResourceQuery(metric = metric, project = project)

    @Test
    fun `프로젝트 필터 칩은 프로젝트와 지표를 함께 명시한다`() {
        val chip = FollowupBuilder.forMetric(query(MetricPattern.INSTANCE_CPU), samples)
            .single { it.contains("admin") }

        // 프로젝트만 있고 지표가 없으면("admin만 보기") 라우터가 맥락 없이 못 읽는다.
        assertTrue(chip.contains("CPU"), "칩이 지표를 명시해야 한다: \"$chip\"")
    }

    @Test
    fun `이미 프로젝트로 필터된 질문엔 프로젝트 칩을 안 만든다`() {
        val chips = FollowupBuilder.forMetric(query(MetricPattern.INSTANCE_CPU, project = "admin"), samples)

        assertTrue(chips.none { it.contains("admin") }, "중복 필터 칩: $chips")
    }

    @Test
    fun `모든 칩은 인프라 리소스를 지목한다`() {
        // 라우터 규칙: "인프라 리소스를 지목했으면 짧아도 RESOURCE". 지목이 없으면 CLARIFY로 샌다.
        val nouns = listOf("인스턴스", "프로젝트", "볼륨", "스냅샷", "CPU", "메모리", "네트워크", "디스크")

        MetricPattern.entries.forEach { metric ->
            FollowupBuilder.forMetric(query(metric), samples).forEach { chip ->
                assertTrue(nouns.any { chip.contains(it) }, "지목 대상 없는 칩: \"$chip\" (metric=$metric)")
            }
        }
    }

    @Test
    fun `결과가 없으면 칩도 없다`() {
        assertTrue(FollowupBuilder.forMetric(query(MetricPattern.INSTANCE_CPU), emptyList()).isEmpty())
    }
}
