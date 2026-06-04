package com.okestro.ragbot.chat.application

/** 단일 진입점 ChatService 입력. 채널(REST/Slack) 무관 공통 커맨드. */
data class ChatCommand(
    val question: String,
    val userId: String, // 레이트리밋·질의 로그 키(Slack user_id / REST는 anonymous)
)
