package com.okestro.ragbot.routing.infrastructure

import com.okestro.ragbot.routing.application.LlmClient
import com.okestro.ragbot.routing.application.LlmRequest
import com.okestro.ragbot.chat.domain.ConversationMessage.Role
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.ResponseFormat
import org.springframework.stereotype.Component

/**
 * Spring AI ChatClient로 OpenAI 라우팅 호출. native Structured Outputs(strict json_schema)로
 * 유효 JSON과 enum 값을 API 레벨에서 강제한다. model/temperature는 호출당 옵션으로 적용(채팅 모델과 독립).
 * OpenAI 호출은 Resilience4j 'openai' 인스턴스로 Retry+CircuitBreaker 보호.
 * 단일 ChatModel 빈으로 ChatClient를 수동 구성(generation/OpenAiChatClient와 동일 관례 — 생성자 모호성 없음).
 */
@Component
class OpenAiRouterLlmClient(chatModel: ChatModel) : LlmClient {

    private val chatClient = ChatClient.builder(chatModel).build()

    @Retry(name = "openai")
    @CircuitBreaker(name = "openai")
    override fun complete(request: LlmRequest): String {
        val responseFormat = ResponseFormat.builder()
            .type(ResponseFormat.Type.JSON_SCHEMA)
            .jsonSchema(
                ResponseFormat.JsonSchema.builder()
                    .name("route_decision")
                    .schema(request.jsonSchema)
                    .strict(true)
                    .build(),
            )
            .build()

        val options = OpenAiChatOptions.builder()
            .model(request.model)
            .temperature(request.temperature)
            .responseFormat(responseFormat)
            .build()

        val messages: List<Message> = request.messages.map { m ->
            when (m.role) {
                Role.USER -> UserMessage(m.content)
                Role.ASSISTANT -> AssistantMessage(m.content)
            }
        }

        return chatClient.prompt()
            .system(request.system)
            .messages(messages)
            .options(options)
            .call()
            .content() ?: ""
    }
}
