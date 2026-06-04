package com.okestro.ragbot.guard.application

import com.okestro.ragbot.guard.domain.ContentPolicy
import com.okestro.ragbot.guard.domain.GuardDecision
import com.okestro.ragbot.guard.domain.InputValidation
import org.springframework.stereotype.Service

/**
 * 입력검증 → 로컬 금칙어 → (있으면) OpenAI Moderation 순 차단. 앞 단계에서 차단되면 뒤 단계는
 * 호출하지 않는다. moderationService 는 app.guard.moderation.enabled=false 시 빈이 없어 null → 건너뜀.
 */
@Service
class DefaultInputGuard(
    private val inputValidation: InputValidation,
    private val contentPolicy: ContentPolicy,
    private val moderationService: ModerationService?,
) : InputGuard {
    override fun inspect(question: String): GuardDecision {
        val validation = inputValidation.check(question)
        if (validation is GuardDecision.Blocked) return validation
        val content = contentPolicy.check(question)
        if (content is GuardDecision.Blocked) return content
        return moderationService?.inspect(question) ?: GuardDecision.Allowed
    }
}
