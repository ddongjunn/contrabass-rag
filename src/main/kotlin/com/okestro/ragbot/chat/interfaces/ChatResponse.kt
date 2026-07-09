package com.okestro.ragbot.chat.interfaces

import com.okestro.ragbot.resource.domain.Widget

/** POST /api/chat 응답. sources 는 출처 문자열 목록('title #chunk'). widgets/followups 기본 빈 배열(하위호환). */
data class ChatResponse(
    val answer: String,
    val sources: List<String>,
    val widgets: List<Widget> = emptyList(),
    val followups: List<String> = emptyList(),
)
