package com.okestro.ragbot.guard.domain

/**
 * 입력 가드 판정. 차단 시 message 를 사용자에게 그대로 안내(임베딩·생성 0회 — 불변식 4).
 * reason 은 로그·검증용 짧은 식별자.
 */
sealed interface GuardDecision {
    data object Allowed : GuardDecision
    data class Blocked(val reason: String, val message: String) : GuardDecision
}
