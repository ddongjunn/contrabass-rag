package com.okestro.ragbot.guard

import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.guard.domain.GuardDecision
import com.okestro.ragbot.guard.domain.InputValidation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InputValidationTest {
    private val validation = InputValidation(AppProperties(guard = AppProperties.Guard(maxQuestionLen = 10)))

    @Test
    fun `blank input is blocked`() {
        assertThat(validation.check("   ")).isInstanceOf(GuardDecision.Blocked::class.java)
        assertThat((validation.check("") as GuardDecision.Blocked).reason).isEqualTo("blank")
    }

    @Test
    fun `over-length input is blocked`() {
        val decision = validation.check("01234567890") // 11 > 10
        assertThat(decision).isInstanceOf(GuardDecision.Blocked::class.java)
        assertThat((decision as GuardDecision.Blocked).reason).isEqualTo("too-long")
    }

    @Test
    fun `normal input is allowed`() {
        assertThat(validation.check("정상 질문")).isEqualTo(GuardDecision.Allowed)
    }
}
