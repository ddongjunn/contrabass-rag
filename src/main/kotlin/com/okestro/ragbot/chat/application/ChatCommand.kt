package com.okestro.ragbot.chat.application

import com.okestro.ragbot.chat.domain.ConversationMessage

/** 단일 진입점 ChatService 입력. 채널(REST/Slack) 무관 공통 커맨드. */
data class ChatCommand(
    val question: String,
    val userId: String,                                          // 레이트리밋·질의 로그 키(Slack user_id / REST는 anonymous)
    val history: List<ConversationMessage> = emptyList(),        // Slack 스레드 히스토리(REST는 빈 목록)
    val project: String? = null,                                  // 호출부(포털) 컨텍스트 — QUOTA 추출 폴백용(Phase 2)
)
