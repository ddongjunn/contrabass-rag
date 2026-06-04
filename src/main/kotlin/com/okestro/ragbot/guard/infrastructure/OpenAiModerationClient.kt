package com.okestro.ragbot.guard.infrastructure

import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.guard.application.ModerationService
import com.okestro.ragbot.guard.domain.GuardDecision
import org.springframework.ai.moderation.ModerationModel
import org.springframework.ai.moderation.ModerationPrompt
import org.springframework.ai.openai.OpenAiModerationOptions
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * OpenAI Moderation 2차 필터. 모델명 = app.guard.moderation.model(요청 옵션으로 주입, 하드코딩 금지).
 * 결과가 flagged 면 차단. app.guard.moderation.enabled=false 면 이 빈이 생성되지 않아 가드에서 건너뜀.
 */
@Component
@ConditionalOnProperty(prefix = "app.guard.moderation", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class OpenAiModerationClient(
    private val moderationModel: ModerationModel,
    props: AppProperties,
) : ModerationService {
    private val options = OpenAiModerationOptions.builder()
        .model(props.guard.moderation.model)
        .build()

    override fun inspect(question: String): GuardDecision {
        val moderation = moderationModel.call(ModerationPrompt(question, options)).result.output
        val flagged = moderation.results.any { it.isFlagged }
        return if (flagged) {
            GuardDecision.Blocked("moderation", "부적절한 표현이 포함되어 답변할 수 없습니다.")
        } else {
            GuardDecision.Allowed
        }
    }
}
