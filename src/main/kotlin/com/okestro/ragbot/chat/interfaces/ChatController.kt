package com.okestro.ragbot.chat.interfaces

import com.okestro.ragbot.chat.application.ChatCommand
import com.okestro.ragbot.chat.application.ChatService
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/chat")
class ChatController(
    private val chatService: ChatService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping
    fun chat(@RequestBody request: ChatRequest): ChatResponse {
        val userId = request.userId ?: "anonymous"
        log.info("POST /api/chat userId={} question.len={}", userId, request.question.length)
        val result = chatService.handle(
            ChatCommand(
                question = request.question,
                userId = userId,
                project = request.project?.takeIf { it.isNotBlank() },
            ),
        )
        return ChatResponse(answer = result.answer, sources = result.sources, widgets = result.widgets, followups = result.followups)
    }
}
