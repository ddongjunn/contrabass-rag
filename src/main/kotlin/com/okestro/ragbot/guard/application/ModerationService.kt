package com.okestro.ragbot.guard.application

import com.okestro.ragbot.guard.domain.GuardDecision

/**
 * OpenAI Moderation 2차 콘텐츠 판별. 로컬 금칙어(ContentPolicy)를 통과한 입력의 유해성을
 * 분류기로 검사한다(금칙어 사전 아님 — 학습된 분류기). flagged 시 차단(임베딩·생성 0회).
 */
interface ModerationService {
    fun inspect(question: String): GuardDecision
}
