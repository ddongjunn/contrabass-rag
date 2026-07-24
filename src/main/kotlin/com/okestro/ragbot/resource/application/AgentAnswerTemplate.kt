package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.ThresholdBannerWidget

/** AGENT 배너 → 한국어 평문 답변(순수 함수, LLM 무호출). 배너 제목을 그대로 반복하지 않는다. */
object AgentAnswerTemplate {

    fun render(widget: ThresholdBannerWidget): String =
        if (widget.count == 0) {
            "다운된 에이전트가 없습니다. OpenStack 서비스 에이전트는 모두 정상입니다."
        } else {
            "다운된 에이전트가 ${widget.count}개 있습니다 — ${widget.detail ?: ""}"
        }
}
