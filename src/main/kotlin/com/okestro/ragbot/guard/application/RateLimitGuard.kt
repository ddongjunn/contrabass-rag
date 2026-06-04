package com.okestro.ragbot.guard.application

/**
 * 사용자별 요청 빈도 제한(호출 전 단락). 초과 시 false → 임베딩·생성 0회(불변식 4).
 * 키 = userId(Slack user_id / REST anonymous). 한도 = app.guard.rate-per-min.
 */
interface RateLimitGuard {
    fun tryAcquire(userId: String): Boolean
}
