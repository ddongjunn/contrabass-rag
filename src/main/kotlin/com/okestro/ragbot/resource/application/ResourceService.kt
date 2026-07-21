package com.okestro.ragbot.resource.application

import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.resource.domain.Widget

interface ResourceService {
    data class Result(
        val answer: String,
        val needsClarification: Boolean = false,
        val widgets: List<Widget> = emptyList(),
        val followups: List<String> = emptyList(),
    )
    /** contextProject: 호출부(포털) 컨텍스트. 현재 이 값을 쓰는 target은 없다 — 향후 프로젝트
     * 컨텍스트가 필요한 target이 생기면 쓰는 예약 필드. */
    fun handle(history: List<ConversationMessage>, contextProject: String? = null): Result
}
