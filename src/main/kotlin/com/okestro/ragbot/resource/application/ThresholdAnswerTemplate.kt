package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.ThresholdBannerWidget

/**
 * threshold_banner 결과 → 한국어 평문 답변(순수 함수, LLM 무호출).
 *
 * 평문은 위젯 위 캡션으로 붙으므로 **배너 제목을 반복하면 안 된다** — 같은 문장이 두 번 쌓여 버그처럼 보인다.
 * 배너는 라벨("CPU 85% 초과 인스턴스 없음"), 평문은 산문("현재 CPU 85%를 초과하는 인스턴스는 없습니다.")로
 * 역할을 나눈다. 숫자는 위젯이 계산한 count/detail을 그대로 읽어 드리프트를 막는다.
 */
object ThresholdAnswerTemplate {

    fun render(widget: ThresholdBannerWidget, critPercent: Int): String {
        if (widget.count == 0) return "현재 CPU ${critPercent}%를 초과하는 인스턴스는 없습니다."

        val names = widget.detail?.substringAfter("↑ : ", "")?.takeIf { it.isNotBlank() }
        val suffix = names?.let { " — $it" } ?: ""
        return "CPU ${critPercent}%를 초과한 인스턴스가 ${widget.count}대 있습니다$suffix."
    }
}
