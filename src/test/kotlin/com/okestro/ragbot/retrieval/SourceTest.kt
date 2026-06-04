package com.okestro.ragbot.retrieval

import com.okestro.ragbot.retrieval.domain.Source
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceTest {
    @Test
    fun `title and chunk only (page null) — current docx index`() {
        assertThat(Source(title = "운영가이드", page = null, chunkIndex = 7).display())
            .isEqualTo("운영가이드 #7")
    }

    @Test
    fun `page added when non-null`() {
        assertThat(Source(title = "운영가이드", page = 3, chunkIndex = 7).display())
            .isEqualTo("운영가이드 p.3 #7")
    }

    @Test
    fun `missing title falls back to unknown`() {
        assertThat(Source(title = null, page = null, chunkIndex = 0).display())
            .isEqualTo("unknown #0")
    }
}
