package com.okestro.ragbot.chat.interfaces

/** POST /api/chat 응답. sources 는 출처 문자열 목록('title #chunk'). */
data class ChatResponse(
    val answer: String,
    val sources: List<String>,
)
