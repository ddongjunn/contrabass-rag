package com.okestro.ragbot.retrieval

import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.retrieval.domain.RetrievalPolicy
import com.okestro.ragbot.retrieval.domain.RetrievedChunk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RetrievalPolicyTest {
    private fun chunk(score: Double) = RetrievedChunk("c", "t", null, 0, score)

    private fun policy(minScore: Double?) =
        RetrievalPolicy(AppProperties(retrieval = AppProperties.Retrieval(minScore = minScore)))

    @Test
    fun `min-score unset passes all (0건만 차단)`() {
        val chunks = listOf(chunk(0.9), chunk(0.1))
        assertThat(policy(null).apply(chunks)).isEqualTo(chunks)
    }

    @Test
    fun `min-score set filters low similarity`() {
        val result = policy(0.5).apply(listOf(chunk(0.9), chunk(0.3), chunk(0.5)))
        assertThat(result.map { it.score }).containsExactly(0.9, 0.5)
    }
}
