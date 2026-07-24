package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.UsageBarWidget

/**
 * usage_bar 결과 → 한국어 평문 답변(순수 함수, LLM 무호출). IP_USAGE·CAPACITY 공용.
 * 위젯이 이미 계산·정렬한 rows를 그대로 읽는다(숫자 드리프트 방지 — 다른 템플릿과 동일 규칙).
 * 총계("외 N개")는 주장하지 않는다 — rows는 이미 상한으로 잘린 뒤라 여기서 세면 틀린다.
 */
object UsageAnswerTemplate {

    private const val TOP_N = 3

    fun render(widget: UsageBarWidget): String {
        if (widget.empty) return "조회된 항목이 없습니다."

        val top = widget.rows.take(TOP_N).joinToString(", ") { "${it.name} ${it.display}" }
        return "${widget.title}입니다 — 상위: $top."
    }
}
