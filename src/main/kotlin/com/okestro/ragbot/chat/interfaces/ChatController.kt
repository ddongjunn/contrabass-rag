package com.okestro.ragbot.chat.interfaces

import com.okestro.ragbot.chat.application.ChatCommand
import com.okestro.ragbot.chat.application.ChatService
import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.common.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/chat")
class ChatController(
    private val chatService: ChatService,
    private val properties: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping
    fun chat(@RequestBody request: ChatRequest): ChatResponse {
        val userId = request.userId ?: "anonymous"
        val history = toHistory(request.history)
        log.info("POST /api/chat userId={} question.len={} history={}", userId, request.question.length, history.size)
        val result = chatService.handle(
            ChatCommand(
                question = request.question,
                userId = userId,
                history = history,
                project = request.project?.takeIf { it.isNotBlank() },
            ),
        )
        return ChatResponse(answer = result.answer, sources = result.sources, widgets = result.widgets, followups = result.followups)
    }

    /**
     * 웹 히스토리 → ConversationMessage. 상한은 Slack 스레드 fetch와 동일하게
     * historyTurns - 1 (현재 질문까지 합치면 라우터가 보는 historyTurns가 된다).
     */
    private fun toHistory(history: List<ChatRequest.HistoryMessage>): List<ConversationMessage> =
        history
            .filter { it.content.isNotBlank() }
            .map {
                val role = if (it.role == "assistant") ConversationMessage.Role.ASSISTANT else ConversationMessage.Role.USER
                ConversationMessage(role, it.content)
            }
            .takeLast((properties.router.historyTurns - 1).coerceAtLeast(0))
}
