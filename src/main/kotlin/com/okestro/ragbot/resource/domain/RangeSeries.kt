package com.okestro.ragbot.resource.domain

/** `/api/v1/query_range` 결과 한 시리즈 — 라벨 통째 + (ts, value) 포인트 목록. */
data class RangeSeries(
    val labels: Map<String, String>,
    val points: List<TimePoint>,
)

data class TimePoint(val ts: Long, val value: Double)
