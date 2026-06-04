package com.okestro.ragbot.retrieval.application

import com.okestro.ragbot.retrieval.domain.RetrievedChunk

/**
 * documents 벡터 검색 단일 진입점. 질문 벡터는 호출부(ChatService)가 1회 임베딩해 넘긴다(불변식 2).
 * 여기서 재임베딩하지 않는다.
 */
interface DocumentSearchService {
    fun search(queryVector: FloatArray, topK: Int): List<RetrievedChunk>
}
