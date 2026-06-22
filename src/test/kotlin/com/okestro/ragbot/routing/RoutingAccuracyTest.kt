package com.okestro.ragbot.routing

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.routing.application.LlmQuestionRouter
import com.okestro.ragbot.routing.domain.ConversationMessage
import com.okestro.ragbot.routing.domain.ConversationMessage.Role
import com.okestro.ragbot.routing.domain.Route
import com.okestro.ragbot.routing.infrastructure.OpenAiRouterLlmClient
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.api.OpenAiApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 실제 OpenAI 분류 정확도 검증. OPENAI_API_KEY 있을 때만 실행(기본 off → ./gradlew test 그린 유지).
 * stub 테스트가 못 보는 "분류 품질"을 실측한다. few-shot 튜닝 시 회귀 가드로 사용.
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class RoutingAccuracyTest {

    private fun realRouter(): LlmQuestionRouter {
        val api = OpenAiApi.builder().apiKey(System.getenv("OPENAI_API_KEY")).build()
        val chatModel = OpenAiChatModel.builder().openAiApi(api).build()
        return LlmQuestionRouter(OpenAiRouterLlmClient(chatModel), AppProperties(), jacksonObjectMapper())
    }

    private fun user(text: String) = listOf(ConversationMessage(Role.USER, text))

    @Test
    fun `명확한 DOC 질문`() {
        assertEquals(Route.DOC, realRouter().route(user("RAG에서 임베딩 모델은 어떻게 설정하나요?")).route)
    }

    @Test
    fun `명확한 RESOURCE 질문`() {
        assertEquals(Route.RESOURCE, realRouter().route(user("지금 prod 클러스터 CPU 사용률 보여줘")).route)
    }

    @Test
    fun `맥락 의존 후속 질문은 RESOURCE`() {
        val history = listOf(
            ConversationMessage(Role.ASSISTANT, "인스턴스 목록입니다 — 1) web-01 2) web-02 3) db-01"),
            ConversationMessage(Role.USER, "1번 인스턴스 상세 알려줘"),
        )
        assertEquals(Route.RESOURCE, realRouter().route(history).route)
    }

    @Test
    fun `혼합 의도는 BOTH`() {
        assertEquals(
            Route.BOTH,
            realRouter().route(user("Prometheus 알람 설정법 알려주고, 지금 떠 있는 알람도 보여줘")).route,
        )
    }
}
