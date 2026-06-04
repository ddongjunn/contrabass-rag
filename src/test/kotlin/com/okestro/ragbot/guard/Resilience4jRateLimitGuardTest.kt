package com.okestro.ragbot.guard

import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.guard.infrastructure.Resilience4jRateLimitGuard
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** rate-per-min 한도까지 통과·초과 거절, 사용자별 버킷 분리 검증(refresh 1분이라 테스트 창 내 결정적). */
class Resilience4jRateLimitGuardTest {
    private fun guard(perMin: Int) = Resilience4jRateLimitGuard(
        RateLimiterRegistry.ofDefaults(),
        AppProperties(guard = AppProperties.Guard(ratePerMin = perMin)),
    )

    @Test
    fun `allows up to the limit then rejects`() {
        val guard = guard(perMin = 2)
        assertThat(guard.tryAcquire("u1")).isTrue()
        assertThat(guard.tryAcquire("u1")).isTrue()
        assertThat(guard.tryAcquire("u1")).isFalse()   // 3번째 초과 → 거절
    }

    @Test
    fun `limits are per-user (separate buckets)`() {
        val guard = guard(perMin = 1)
        assertThat(guard.tryAcquire("u1")).isTrue()
        assertThat(guard.tryAcquire("u1")).isFalse()   // u1 소진
        assertThat(guard.tryAcquire("u2")).isTrue()    // u2는 별도 버킷
    }
}
