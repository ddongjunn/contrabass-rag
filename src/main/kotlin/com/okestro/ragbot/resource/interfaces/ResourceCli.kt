package com.okestro.ragbot.resource.interfaces

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.resource.application.LlmMetricQueryExtractor
import com.okestro.ragbot.resource.domain.ResourceExtraction
import com.okestro.ragbot.routing.domain.ConversationMessage
import com.okestro.ragbot.routing.domain.ConversationMessage.Role
import com.okestro.ragbot.routing.infrastructure.OpenAiRouterLlmClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.api.OpenAiApi

/**
 * RESOURCE 경로 수동 확인용 독립 실행기 (R1: 추출 결과만 출력).
 * 실행: OPENAI_API_KEY=... ./gradlew resourceCli -q --console=plain
 */
fun main() {
    val apiKey = System.getenv("OPENAI_API_KEY")
        ?: error("OPENAI_API_KEY 환경변수가 필요합니다")

    val api = OpenAiApi.builder().apiKey(apiKey).build()
    val chatModel = OpenAiChatModel.builder().openAiApi(api).build()
    val extractor = LlmMetricQueryExtractor(
        OpenAiRouterLlmClient(chatModel),
        AppProperties(),
        jacksonObjectMapper(),
    )

    println("인프라 지표 조회 질문을 입력하세요 (빈 줄/Ctrl-D 종료):")
    generateSequence(::readLine).forEach { line ->
        if (line.isBlank()) return@forEach
        when (val result = extractor.extract(listOf(ConversationMessage(Role.USER, line)))) {
            is ResourceExtraction.Resolved -> {
                val q = result.query
                println("→ [추출] metric=${q.metric}  sort=${q.sort}  topN=${q.topN}  window=${q.window}  project=${q.project ?: "(전체)"}")
            }
            is ResourceExtraction.NeedsClarification ->
                println("→ [되물음] ${result.message}")
        }
    }
}
