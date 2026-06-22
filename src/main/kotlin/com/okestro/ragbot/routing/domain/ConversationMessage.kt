package com.okestro.ragbot.routing.domain

/** 라우터 입력의 한 턴. 라우터는 마지막 질문 1개가 아니라 최근 N턴을 함께 받는다. */
data class ConversationMessage(
    val role: Role,
    val content: String,
) {
    enum class Role { USER, ASSISTANT }
}
