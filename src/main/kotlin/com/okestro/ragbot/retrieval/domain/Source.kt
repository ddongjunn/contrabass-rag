package com.okestro.ragbot.retrieval.domain

/**
 * 출처 표기 값객체. 형식 `title #chunk_index`(개발자가 근거 청크를 DB에서 찾아 검증).
 * page 는 non-null 일 때만 추가(현 docx 색인은 전부 null). title 이 없으면 `unknown`.
 */
data class Source(
    val title: String?,
    val page: Int?,
    val chunkIndex: Int?,
) {
    fun display(): String = buildString {
        append(title ?: "unknown")
        if (page != null) append(" p.$page")
        if (chunkIndex != null) append(" #$chunkIndex")
    }

    companion object {
        fun of(chunk: RetrievedChunk): Source = Source(chunk.title, chunk.page, chunk.chunkIndex)
    }
}
