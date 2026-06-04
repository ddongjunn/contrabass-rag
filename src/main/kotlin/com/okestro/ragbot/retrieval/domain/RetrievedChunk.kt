package com.okestro.ragbot.retrieval.domain

/**
 * documents 한 행의 검색 결과. score = 코사인 유사도(1 - 코사인거리, 1에 가까울수록 유사).
 * title/page/chunkIndex 는 metadata(json)에서 추출 — 색인에 따라 없을 수 있어 nullable.
 */
data class RetrievedChunk(
    val content: String,
    val title: String?,
    val page: Int?,
    val chunkIndex: Int?,
    val score: Double,
)
