package com.okestro.ragbot.resource.application

import com.okestro.ragbot.chat.domain.ConversationMessage

interface ResourceService {
    data class Result(val answer: String, val needsClarification: Boolean = false)
    fun handle(history: List<ConversationMessage>): Result
}
