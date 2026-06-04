package com.okestro.ragbot.chat.interfaces

/** POST /api/chat 요청. userId 미지정 시 'anonymous'로 처리(REST 직접 호출 등). */
data class ChatRequest(
    val question: String,
    val userId: String? = null,
)
