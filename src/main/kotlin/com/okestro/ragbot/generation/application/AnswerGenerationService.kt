package com.okestro.ragbot.generation.application

import com.okestro.ragbot.retrieval.domain.RetrievedChunk

/**
 * 검색 근거(top-k 청크)에 기반해 답변을 생성한다(불변식 5). 호출은 검색 성공 경로에서만
 * 이뤄진다(불변식 3) — 0건/차단 시 호출부가 막으므로 여기서 chunks 는 항상 비어있지 않다.
 */
interface AnswerGenerationService {
    fun generate(question: String, chunks: List<RetrievedChunk>): String
}
