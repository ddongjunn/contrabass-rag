package com.okestro.ragbot.guard

import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.guard.application.DefaultInputGuard
import com.okestro.ragbot.guard.domain.ContentPolicy
import com.okestro.ragbot.guard.domain.GuardDecision
import com.okestro.ragbot.guard.domain.InputValidation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader

class DefaultInputGuardTest {
    private val props = AppProperties(
        guard = AppProperties.Guard(maxQuestionLen = 10, bannedWordsPath = "classpath:test-banned-words.txt"),
    )
    private val guard = DefaultInputGuard(
        InputValidation(props),
        ContentPolicy(props, DefaultResourceLoader()),
    )

    @Test
    fun `validation runs before content policy`() {
        // 빈 입력은 금칙어 검사 이전에 차단
        assertThat((guard.inspect("") as GuardDecision.Blocked).reason).isEqualTo("blank")
    }

    @Test
    fun `banned word blocks when input is valid`() {
        assertThat((guard.inspect("나쁜말") as GuardDecision.Blocked).reason).isEqualTo("banned-word")
    }

    @Test
    fun `clean valid input is allowed`() {
        assertThat(guard.inspect("정상질문")).isEqualTo(GuardDecision.Allowed)
    }
}
