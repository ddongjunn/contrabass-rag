package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.MetricLineWidget

/**
 * metric_line 결과 → 한국어 평문 답변(순수 함수, LLM 무호출).
 * 숫자 드리프트를 막으려고 위젯이 이미 계산한 series를 그대로 읽는다(StatusAnswerTemplate과 동일 규칙).
 */
object TrendAnswerTemplate {

    fun render(widget: MetricLineWidget): String {
        if (widget.empty) return "해당 구간의 시계열 데이터가 없습니다."

        val names = widget.series.joinToString(", ") { it.name }
        return "최근 ${widget.range} ${widget.title}입니다 — $names."
    }
}
