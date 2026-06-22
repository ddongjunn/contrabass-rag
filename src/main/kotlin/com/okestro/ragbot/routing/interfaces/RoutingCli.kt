package com.okestro.ragbot.routing.interfaces

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.routing.application.LlmQuestionRouter
import com.okestro.ragbot.routing.domain.ConversationMessage
import com.okestro.ragbot.routing.domain.ConversationMessage.Role
import com.okestro.ragbot.routing.infrastructure.OpenAiRouterLlmClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.api.OpenAiApi

/**
 * 라우터 수동 확인용 독립 실행기. Spring 컨텍스트(DB·Slack) 없이 OpenAI 호출만 조립한다.
 * 실행: OPENAI_API_KEY=... ./gradlew routingCli -q --console=plain
 * (단일 질문 단위로 받는다 — 맥락 의존 케이스는 정확도 테스트에서 다룬다.)
 */
fun main() {
    val apiKey = System.getenv("OPENAI_API_KEY")
        ?: error("OPENAI_API_KEY 환경변수가 필요합니다")

    val api = OpenAiApi.builder().apiKey(apiKey).build()
    val chatModel = OpenAiChatModel.builder().openAiApi(api).build()
    val router = LlmQuestionRouter(
        OpenAiRouterLlmClient(chatModel),
        AppProperties(),                 // 기본값(model=gpt-4o-mini, minConfidence=0.5 …)
        jacksonObjectMapper(),
    )

    println("질문을 입력하세요(빈 줄/Ctrl-D 종료):")
    generateSequence(::readLine).forEach { line ->
        if (line.isBlank()) return@forEach
        val d = router.route(listOf(ConversationMessage(Role.USER, line)))
        println("→ ${d.route} (confidence=${d.confidence}, reason=${d.reason})")
    }
}
