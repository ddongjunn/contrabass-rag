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
    /** contextProject: 호출부(포털) 컨텍스트 — QUOTA가 질문에서 project를 못 찾았을 때만 폴백으로 쓴다. */
    fun handle(history: List<ConversationMessage>, contextProject: String? = null): Result
}
