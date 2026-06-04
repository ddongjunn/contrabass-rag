package com.okestro.ragbot.guard.application

import com.okestro.ragbot.guard.domain.GuardDecision

/** 호출 전 입력 가드. 단일 진입점 ChatService가 임베딩 전에 호출 — 차단 시 임베딩·생성 0회(불변식 4). */
interface InputGuard {
    fun inspect(question: String): GuardDecision
}
