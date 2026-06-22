package com.okestro.ragbot.routing.application

import com.okestro.ragbot.routing.domain.ConversationMessage

/**
 * LLM 호출 seam. 구현(OpenAI)을 갈아끼우거나 테스트에서 스텁으로 대체할 수 있다.
 * 파싱된 빈이 아니라 **원시 응답 본문(JSON 문자열)** 을 반환한다 — "깨진 응답 → CLARIFY" 경로를 정직하게 테스트하기 위함.
 */
interface LlmClient {
    fun complete(request: LlmRequest): String
}

/** 라우팅 1회 호출 요청. model/temperature/jsonSchema는 호출당 옵션으로 적용된다. */
data class LlmRequest(
    val system: String,
    val messages: List<ConversationMessage>,
    val model: String,
    val temperature: Double,
    val jsonSchema: String,
)
