package com.okestro.ragbot.guard.infrastructure

import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.guard.application.RateLimitGuard
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Resilience4j `RateLimiterRegistry`를 userId로 키잉(전역 단일 인스턴스 아님 — 사용자별 버킷).
 * 한도 = app.guard.rate-per-min / 1분, 대기 없음(timeoutDuration=0)으로 초과 시 즉시 거절.
 */
@Component
class Resilience4jRateLimitGuard(
    private val registry: RateLimiterRegistry,
    props: AppProperties,
) : RateLimitGuard {
    private val config: RateLimiterConfig = RateLimiterConfig.custom()
        .limitForPeriod(props.guard.ratePerMin)
        .limitRefreshPeriod(Duration.ofMinutes(1))
        .timeoutDuration(Duration.ZERO)
        .build()

    override fun tryAcquire(userId: String): Boolean =
        registry.rateLimiter(userId, config).acquirePermission()
}
