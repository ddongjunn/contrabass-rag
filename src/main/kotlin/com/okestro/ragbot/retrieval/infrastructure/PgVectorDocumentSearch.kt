package com.okestro.ragbot.retrieval.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.retrieval.application.DocumentSearchService
import com.okestro.ragbot.retrieval.domain.RetrievedChunk
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/**
 * documents(읽기 전용)에 pgvector 코사인 검색. 질문 벡터를 pgvector 리터럴로 바인딩해
 * `embedding <=> :vec`(코사인 거리) 오름차순 top-k 조회, score = 1 - 거리.
 * 출처 키(title/page/chunk_index)·테이블명은 `app.retrieval.*` 단일 소스.
 */
@Repository
class PgVectorDocumentSearch(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    props: AppProperties,
) : DocumentSearchService {
    private val table = props.retrieval.table
    private val keys = props.retrieval.sourceKeys

    override fun search(queryVector: FloatArray, topK: Int): List<RetrievedChunk> {
        val vec = queryVector.joinToString(",", "[", "]")
        val sql = """
            SELECT content, metadata, 1 - (embedding <=> ?::vector) AS score
            FROM $table
            ORDER BY embedding <=> ?::vector
            LIMIT ?
        """.trimIndent()
        return jdbc.query(sql, { rs, _ ->
            val meta = rs.getString("metadata")?.let { objectMapper.readTree(it) }
            RetrievedChunk(
                content = rs.getString("content") ?: "",
                title = meta?.text(keys.title),
                page = meta?.int(keys.page),
                chunkIndex = meta?.int(keys.chunk),
                score = rs.getDouble("score"),
            )
        }, vec, vec, topK)
    }

    private fun JsonNode.text(key: String): String? = get(key)?.takeIf { !it.isNull }?.asText()
    private fun JsonNode.int(key: String): Int? = get(key)?.takeIf { it.isInt || it.canConvertToInt() }?.asInt()
}
