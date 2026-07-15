package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.ProjectUsageBarWidget

/**
 * project_usage_bar 결과 → 한국어 평문 답변(순수 함수, LLM 무호출).
 *
 * 프로젝트가 수십 개라(실측 27개) 전부 나열하면 평문이 위젯보다 길어진다. 캡션 역할에 맞게 상위 몇 개만 요약한다.
 *
 * ⚠️ "외 N개" 같은 총계는 **주장하지 않는다.** 위젯은 이미 상위 N개로 잘린 뒤라 여기서 세면 틀린 수가 나온다
 * (실측 27개인데 "외 7개"라고 답했다). 총계를 정확히 말하려면 계약에 total을 추가하거나 필터 로직을
 * 중복해야 해서, 지금은 확실한 것만 말한다.
 */
object ProjectUsageAnswerTemplate {

    private const val TOP_N = 3

    fun render(widget: ProjectUsageBarWidget): String {
        if (widget.rows.isEmpty()) return "쿼터가 설정된 프로젝트를 찾지 못했습니다."

        val top = widget.rows.take(TOP_N).joinToString(", ") { "${it.projectName} ${it.display}" }
        return "프로젝트별 ${widget.metric} 쿼터 사용률입니다 — 사용률 상위: $top."
    }
}
