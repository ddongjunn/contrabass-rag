package com.okestro.ragbot.guard

import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.guard.domain.ContentPolicy
import com.okestro.ragbot.guard.domain.GuardDecision
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader

class ContentPolicyTest {
    private val policy = ContentPolicy(
        AppProperties(guard = AppProperties.Guard(bannedWordsPath = "classpath:test-banned-words.txt")),
        DefaultResourceLoader(),
    )

    @Test
    fun `question containing a banned word is blocked`() {
        val decision = policy.check("이건 나쁜말 입니다")
        assertThat(decision).isInstanceOf(GuardDecision.Blocked::class.java)
        assertThat((decision as GuardDecision.Blocked).reason).isEqualTo("banned-word")
    }

    @Test
    fun `banned-word match is case-insensitive`() {
        assertThat(policy.check("this is FORBIDDEN")).isInstanceOf(GuardDecision.Blocked::class.java)
    }

    @Test
    fun `clean question is allowed`() {
        assertThat(policy.check("세그먼트 조회 방법 알려줘")).isEqualTo(GuardDecision.Allowed)
    }
}
