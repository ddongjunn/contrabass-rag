package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.StatusDonutWidget

/**
 * status_donut 결과 → 한국어 평문 답변(순수 함수, LLM 무호출).
 *
 * 위젯이 있어도 평문은 항상 함께 나간다(불변식: Slack·스크린리더 폴백). 숫자 드리프트를 막으려고
 * 조회 결과를 다시 세지 않고 **위젯이 이미 계산한 total/segments를 그대로 읽는다** — 정렬 순서까지 공유.
 */
object StatusAnswerTemplate {

    fun render(widget: StatusDonutWidget): String {
        if (widget.empty) return "조회된 ${widget.label}이(가) 없습니다."

        val breakdown = widget.segments.joinToString(", ") { "${it.status} ${it.count}대" }
        return "${widget.label} ${widget.total}대의 상태 분포입니다 — $breakdown."
    }
}
