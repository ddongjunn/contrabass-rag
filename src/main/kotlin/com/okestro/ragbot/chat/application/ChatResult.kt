package com.okestro.ragbot.chat.application

/** ChatService 출력. sources 는 'title #chunk_index'(있으면 page 추가) 형태 문자열. */
data class ChatResult(
    val answer: String,
    val sources: List<String>,
)
