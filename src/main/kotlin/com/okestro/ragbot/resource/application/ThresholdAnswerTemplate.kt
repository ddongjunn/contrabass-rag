package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.ThresholdBannerWidget

/**
 * threshold_banner 결과 → 한국어 평문 답변(순수 함수, LLM 무호출).
 *
 * 평문은 위젯 위 캡션이므로 **배너 제목을 반복하면 안 된다** — 같은 문장이 두 번 쌓여 버그처럼 보인다.
 * 배너는 라벨("CPU 85% 초과 인스턴스 없음"), 평문은 산문("현재 CPU 85%를 초과하는 인스턴스는 없습니다.")로
 * 역할을 나눈다.
 *
 * offenders는 **목록 그대로 받는다.** 예전엔 위젯의 display 문자열을 `substringAfter("↑ : ")`로 도로
 * 파싱했는데, 구분자가 두 파일에 중복돼 배너 포맷만 바꿔도 이름이 조용히 사라졌다.
 */
object ThresholdAnswerTemplate {

    /** 이름을 몇 개까지 문장에 넣을지. 초과 폭주 시(실측 121대 가능) 평문이 벽이 된다. */
    private const val NAME_LIMIT = 5

    fun render(widget: ThresholdBannerWidget, critPercent: Int, offenders: List<String> = emptyList()): String {
        if (widget.count == 0) return "현재 CPU ${critPercent}%를 초과하는 인스턴스는 없습니다."

        val shown = offenders.take(NAME_LIMIT)
        val suffix = when {
            shown.isEmpty() -> ""
            offenders.size > shown.size -> " — ${shown.joinToString(", ")} 외 ${offenders.size - shown.size}대"
            else -> " — ${shown.joinToString(", ")}"
        }
        return "CPU ${critPercent}%를 초과한 인스턴스가 ${widget.count}대 있습니다$suffix."
    }
}
