package com.okestro.ragbot.chat.domain

/** 대화 한 턴. 라우터·RESOURCE 추출기가 공통으로 사용하는 입력 타입. */
data class ConversationMessage(
    val role: Role,
    val content: String,
) {
    enum class Role { USER, ASSISTANT }
}
