package com.okestro.ragbot.chat.application

import com.okestro.ragbot.resource.domain.Widget

/** ChatService 출력. sources 는 'title #chunk_index'(있으면 page 추가) 형태 문자열. */
data class ChatResult(
    val answer: String,
    val sources: List<String>,
    val widgets: List<Widget> = emptyList(),
    val followups: List<String> = emptyList(),
)
