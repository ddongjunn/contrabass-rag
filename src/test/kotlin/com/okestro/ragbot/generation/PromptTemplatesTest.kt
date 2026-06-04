package com.okestro.ragbot.generation

import com.okestro.ragbot.generation.application.PromptTemplates
import com.okestro.ragbot.retrieval.domain.RetrievedChunk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PromptTemplatesTest {
    @Test
    fun `system prompt enforces evidence-only answering`() {
        assertThat(PromptTemplates.SYSTEM)
            .contains("참고 문서")
            .contains("지어내지")
    }

    @Test
    fun `user prompt includes each chunk content with source label and the question`() {
        val chunks = listOf(
            RetrievedChunk("세그먼트는 A를 보여준다", "운영가이드", null, 3, 0.9),
            RetrievedChunk("조회 절차는 B", "운영가이드", 5, 9, 0.8),
        )

        val prompt = PromptTemplates.user("세그먼트 조회시 뭘 보지?", chunks)

        assertThat(prompt)
            .contains("운영가이드 #3")
            .contains("세그먼트는 A를 보여준다")
            .contains("운영가이드 p.5 #9")
            .contains("조회 절차는 B")
            .contains("질문: 세그먼트 조회시 뭘 보지?")
    }
}
