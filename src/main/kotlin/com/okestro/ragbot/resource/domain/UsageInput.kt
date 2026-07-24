package com.okestro.ragbot.resource.domain

/**
 * usage_bar 한 항목의 입력. 서비스가 조회·계산을 끝내서 넘긴다(WidgetBuilder는 순수 변환 유지).
 *
 * @param display 지정하면 그대로 표시(용량 절대값 "1.0 TB / 34.8 TB (2.8%)" 등), null이면 값 포맷.
 */
data class UsageInput(
    val name: String,
    val value: Double,   // %
    val display: String? = null,
)
