package com.okestro.ragbot.guard

import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.guard.domain.GuardDecision
import com.okestro.ragbot.guard.infrastructure.OpenAiModerationClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.moderation.Generation
import org.springframework.ai.moderation.Moderation
import org.springframework.ai.moderation.ModerationModel
import org.springframework.ai.moderation.ModerationPrompt
import org.springframework.ai.moderation.ModerationResponse
import org.springframework.ai.moderation.ModerationResult

/** flagged→Blocked(reason=moderation), 아니면 Allowed 매핑만 검증(외부 호출 없음). */
class OpenAiModerationClientTest {
    private class FakeModerationModel(private val flagged: Boolean) : ModerationModel {
        var calls = 0
        override fun call(request: ModerationPrompt): ModerationResponse {
            calls++
            val result = ModerationResult.Builder().flagged(flagged).build()
            val moderation = Moderation.Builder().results(listOf(result)).build()
            return ModerationResponse(Generation(moderation))
        }
    }

    private val props = AppProperties()

    @Test
    fun `flagged content is blocked`() {
        val model = FakeModerationModel(flagged = true)
        val decision = OpenAiModerationClient(model, props).inspect("유해 입력")
        assertThat((decision as GuardDecision.Blocked).reason).isEqualTo("moderation")
        assertThat(model.calls).isEqualTo(1)
    }

    @Test
    fun `non-flagged content is allowed`() {
        val model = FakeModerationModel(flagged = false)
        assertThat(OpenAiModerationClient(model, props).inspect("정상 입력")).isEqualTo(GuardDecision.Allowed)
    }
}
