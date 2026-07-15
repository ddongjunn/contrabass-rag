package com.okestro.ragbot.resource

import com.okestro.ragbot.resource.application.ThresholdAnswerTemplate
import com.okestro.ragbot.resource.domain.Severity
import com.okestro.ragbot.resource.domain.ThresholdBannerWidget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * threshold_banner 평문 answer(순수 함수, LLM 무호출).
 *
 * 평문은 위젯 위에 캡션으로 붙는다 — 배너 제목을 그대로 반복하면 같은 문장이 두 번 쌓여 버그처럼 보인다.
 * (실제 화면에서 확인한 문제) 그래서 배너는 라벨, 평문은 산문으로 역할을 나눈다.
 */
class ThresholdAnswerTemplateTest {

    @Test
    fun `초과 있으면 산문으로 대수와 이름을 알린다`() {
        // offenders를 목록 그대로 받는다 — 예전엔 detail 문자열을 substringAfter로 도로 파싱해서,
        // 배너 포맷만 바꿔도 평문에서 이름이 조용히 사라졌다(리뷰 지적).
        val w = ThresholdBannerWidget(Severity.CRIT, "CPU 85% 초과 인스턴스 2대", "CPU 85%↑ : web-07, api-02", 2)
        assertEquals(
            "CPU 85%를 초과한 인스턴스가 2대 있습니다 — web-07, api-02.",
            ThresholdAnswerTemplate.render(w, critPercent = 85, offenders = listOf("web-07", "api-02")),
        )
    }

    @Test
    fun `초과 있는데 이름이 없으면 대수만`() {
        val w = ThresholdBannerWidget(Severity.CRIT, "CPU 85% 초과 인스턴스 2대", null, 2)
        assertEquals("CPU 85%를 초과한 인스턴스가 2대 있습니다.", ThresholdAnswerTemplate.render(w, 85, emptyList()))
    }

    @Test
    fun `초과 0대면 정상이라고 답한다`() {
        val w = ThresholdBannerWidget(Severity.GOOD, "CPU 85% 초과 인스턴스 없음", null, 0)
        assertEquals("현재 CPU 85%를 초과하는 인스턴스는 없습니다.", ThresholdAnswerTemplate.render(w, 85))
    }

    @Test
    fun `평문은 배너 제목과 달라야 한다 - 캡션 중복 방지`() {
        val w = ThresholdBannerWidget(Severity.GOOD, "CPU 85% 초과 인스턴스 없음", null, 0)
        assertNotEquals(w.title, ThresholdAnswerTemplate.render(w, 85))
    }
}
