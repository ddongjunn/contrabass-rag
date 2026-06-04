package com.okestro.ragbot.guard

import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.guard.application.DefaultInputGuard
import com.okestro.ragbot.guard.application.ModerationService
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
    private val validation = InputValidation(props)
    private val contentPolicy = ContentPolicy(props, DefaultResourceLoader())

    /** moderation 단계가 호출됐는지 추적하는 페이크. */
    private class FakeModeration(private val decision: GuardDecision) : ModerationService {
        var calls = 0
        override fun inspect(question: String): GuardDecision { calls++; return decision }
    }

    private fun guard(moderation: ModerationService? = null) =
        DefaultInputGuard(validation, contentPolicy, moderation)

    @Test
    fun `validation runs before content policy and moderation`() {
        val moderation = FakeModeration(GuardDecision.Allowed)
        // 빈 입력은 금칙어·moderation 이전에 차단 — moderation 미호출
        assertThat((guard(moderation).inspect("") as GuardDecision.Blocked).reason).isEqualTo("blank")
        assertThat(moderation.calls).isEqualTo(0)
    }

    @Test
    fun `banned word blocks before moderation`() {
        val moderation = FakeModeration(GuardDecision.Allowed)
        assertThat((guard(moderation).inspect("나쁜말") as GuardDecision.Blocked).reason).isEqualTo("banned-word")
        assertThat(moderation.calls).isEqualTo(0)              // 금칙어에서 단락 → moderation 미호출
    }

    @Test
    fun `moderation blocks clean-but-flagged input`() {
        val moderation = FakeModeration(GuardDecision.Blocked("moderation", "부적절한 표현이 포함되어 답변할 수 없습니다."))
        val decision = guard(moderation).inspect("정상질문")
        assertThat((decision as GuardDecision.Blocked).reason).isEqualTo("moderation")
        assertThat(moderation.calls).isEqualTo(1)
    }

    @Test
    fun `clean input passes when moderation allows`() {
        val moderation = FakeModeration(GuardDecision.Allowed)
        assertThat(guard(moderation).inspect("정상질문")).isEqualTo(GuardDecision.Allowed)
        assertThat(moderation.calls).isEqualTo(1)
    }

    @Test
    fun `moderation disabled (null) allows clean input without moderation`() {
        assertThat(guard(moderation = null).inspect("정상질문")).isEqualTo(GuardDecision.Allowed)
    }
}
