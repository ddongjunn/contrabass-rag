package com.okestro.ragbot.chat.interfaces

/**
 * POST /api/chat 요청. userId 미지정 시 'anonymous'로 처리(REST 직접 호출 등).
 * project는 호출부(포털)가 이미 아는 프로젝트/테넌트 컨텍스트 — 현재 이 값을 소비하는 RESOURCE
 * target은 없다(예약 필드, 설계 §3.3b).
 */
data class ChatRequest(
    val question: String,
    val userId: String? = null,
    val project: String? = null,
)
