package com.okestro.ragbot.guard.domain

import com.okestro.ragbot.common.config.AppProperties
import org.springframework.stereotype.Component

/** 빈 입력·길이 초과 1차 차단(임베딩·생성 전). 임계값 = app.guard.max-question-len(하드코딩 금지). */
@Component
class InputValidation(props: AppProperties) {
    private val maxLen = props.guard.maxQuestionLen

    fun check(question: String): GuardDecision = when {
        question.isBlank() ->
            GuardDecision.Blocked("blank", "질문을 입력해 주세요.")
        question.length > maxLen ->
            GuardDecision.Blocked("too-long", "질문이 너무 깁니다. ${maxLen}자 이내로 입력해 주세요.")
        else -> GuardDecision.Allowed
    }
}
