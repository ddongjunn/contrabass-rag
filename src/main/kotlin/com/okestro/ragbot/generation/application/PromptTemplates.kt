package com.okestro.ragbot.generation.application

import com.okestro.ragbot.retrieval.domain.RetrievedChunk
import com.okestro.ragbot.retrieval.domain.Source

/**
 * RAG 프롬프트 조립. 시스템 규칙(근거 기반·환각 금지) + top-k 근거 + 질문.
 * 출처 라벨은 Source.display() 재사용(응답 sources 와 동일 포맷).
 */
object PromptTemplates {
    val SYSTEM = """
        당신은 사내 문서 기반 QA 어시스턴트입니다.
        - 아래 '참고 문서'의 내용만 근거로 한국어로 답하세요.
        - 문서에 없는 내용은 추측하거나 지어내지 말고, 모르면 모른다고 답하세요.
        - 일반 상식이나 외부 지식으로 보충하지 마세요.
    """.trimIndent()

    fun user(question: String, chunks: List<RetrievedChunk>): String = buildString {
        appendLine("참고 문서:")
        chunks.forEachIndexed { i, c ->
            appendLine("[${i + 1}] (출처: ${Source.of(c).display()})")
            appendLine(c.content)
            appendLine()
        }
        append("질문: ").append(question)
    }
}
