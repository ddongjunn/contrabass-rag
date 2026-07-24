package com.okestro.ragbot.chat.interfaces

/**
 * POST /api/chat 요청. userId 미지정 시 'anonymous'로 처리(REST 직접 호출 등).
 * project는 호출부(포털)가 이미 아는 프로젝트/테넌트 컨텍스트 — RESOURCE의 QUOTA 추출이
 * 질문에서 프로젝트를 못 찾았을 때만 폴백으로 쓴다(설계 §3.3b).
 */
data class ChatRequest(
    val question: String,
    val userId: String? = null,
    val project: String? = null,
    /** 웹 위젯이 보내는 직전 대화(오래된 것부터). 없으면 빈 목록 — Slack 스레드 히스토리와 같은 seam. */
    val history: List<HistoryMessage> = emptyList(),
) {
    data class HistoryMessage(val role: String = "user", val content: String = "")
}
