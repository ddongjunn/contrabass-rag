package com.okestro.ragbot.retrieval.domain

import com.okestro.ragbot.common.config.AppProperties
import org.springframework.stereotype.Component

/**
 * 검색 결과 채택 정책. `app.retrieval.min-score` 미설정 시 0건만 차단(=전건 통과),
 * 설정 시 저유사(score < min-score) 청크 제거. min-score 튜닝은 2차.
 */
@Component
class RetrievalPolicy(props: AppProperties) {
    private val minScore: Double? = props.retrieval.minScore

    fun apply(chunks: List<RetrievedChunk>): List<RetrievedChunk> =
        if (minScore == null) chunks else chunks.filter { it.score >= minScore }
}
