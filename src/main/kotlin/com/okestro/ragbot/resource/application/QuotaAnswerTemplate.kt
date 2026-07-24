package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.QuotaGaugeWidget

/**
 * quota_gauge 결과 → 한국어 평문 답변(순수 함수, LLM 무호출).
 *
 * 위젯이 이미 포맷한 display("8 / 20", "3 / 무제한")를 그대로 읽어 숫자·단위 드리프트를 막는다.
 * 평문은 위젯 위 캡션이므로 게이지 라벨을 반복하지 않고 한 문장으로 요약한다.
 */
object QuotaAnswerTemplate {

    fun render(widget: QuotaGaugeWidget, project: String): String {
        if (widget.items.isEmpty()) return "$project 프로젝트의 쿼터 정보를 찾지 못했습니다."

        val body = widget.items.joinToString(", ") { "${it.resource} ${it.display}" }
        return "$project 프로젝트 쿼터 사용량입니다 — $body."
    }
}
