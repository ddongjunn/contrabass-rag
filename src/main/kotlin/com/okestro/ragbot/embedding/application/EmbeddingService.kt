package com.okestro.ragbot.embedding.application

/**
 * 텍스트 임베딩 단일 진입점. 색인과 동일 모델(text-embedding-3-small/1536, 불변식)로
 * OpenAI API를 호출한다. 한 요청의 질문은 1회만 임베딩하고 결과를 재사용한다(재계산 금지, 불변식 2).
 */
interface EmbeddingService {
    fun embed(text: String): FloatArray
}
