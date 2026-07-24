package com.okestro.ragbot.resource.domain

import java.time.Duration

/**
 * TREND(시계열 추이) 트랙 조건. METRIC과 같은 카탈로그 지표를 쓰되 topk 순위 대신
 * `/api/v1/query_range`로 구간 시계열을 받는다.
 *
 * @param range 조회 구간(예: "1h", "30m", "2d"). LLM 추출값이라 파싱 실패 시 1h로 폴백.
 * @param window rate() 집계 윈도우 — METRIC과 동일 의미.
 */
data class TrendQuery(
    val metric: MetricPattern,
    val range: String = "1h",
    val window: String = "5m",
    val project: String? = null,
    val instanceName: String? = null,
) {
    fun rangeDuration(): Duration {
        val m = Regex("""^(\d+)([smhd])$""").find(range.trim()) ?: return Duration.ofHours(1)
        val n = m.groupValues[1].toLong()
        return when (m.groupValues[2]) {
            "s" -> Duration.ofSeconds(n)
            "m" -> Duration.ofMinutes(n)
            "h" -> Duration.ofHours(n)
            else -> Duration.ofDays(n)
        }
    }
}
