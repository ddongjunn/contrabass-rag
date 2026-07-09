package com.okestro.ragbot.resource.application

import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.resource.domain.Widget

interface ResourceService {
    data class Result(
        val answer: String,
        val needsClarification: Boolean = false,
        val widgets: List<Widget> = emptyList(),
        val followups: List<String> = emptyList(),
    )
    fun handle(history: List<ConversationMessage>): Result
}
