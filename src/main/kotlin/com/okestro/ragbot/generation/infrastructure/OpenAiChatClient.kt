package com.okestro.ragbot.generation.infrastructure

import com.okestro.ragbot.generation.application.AnswerGenerationService
import com.okestro.ragbot.generation.application.PromptTemplates
import com.okestro.ragbot.retrieval.domain.RetrievedChunk
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ChatModel
import org.springframework.stereotype.Component

/**
 * 자동구성된 [ChatModel](OpenAiChatModel, yml `app.openai.chat-model`)로 수동 [ChatClient] 구성.
 * ChatClient 자동구성은 끔(`spring.ai.chat.client.enabled=false`) — provider 교체 여지.
 */
@Component
class OpenAiChatClient(chatModel: ChatModel) : AnswerGenerationService {
    private val chatClient = ChatClient.builder(chatModel)
        .defaultSystem(PromptTemplates.SYSTEM)
        .build()

    override fun generate(question: String, chunks: List<RetrievedChunk>): String =
        chatClient.prompt()
            .user(PromptTemplates.user(question, chunks))
            .call()
            .content() ?: ""
}
