package com.okestro.ragbot.guard.application

import com.okestro.ragbot.guard.domain.ContentPolicy
import com.okestro.ragbot.guard.domain.GuardDecision
import com.okestro.ragbot.guard.domain.InputValidation
import org.springframework.stereotype.Service

/** 입력검증 → 로컬 금칙어 순 1차 차단. (Moderation 2차 판별은 5-b에서 이 뒤에 추가.) */
@Service
class DefaultInputGuard(
    private val inputValidation: InputValidation,
    private val contentPolicy: ContentPolicy,
) : InputGuard {
    override fun inspect(question: String): GuardDecision {
        val validation = inputValidation.check(question)
        if (validation is GuardDecision.Blocked) return validation
        return contentPolicy.check(question)
    }
}
