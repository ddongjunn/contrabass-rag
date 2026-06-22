package com.okestro.ragbot.routing.domain

/** 라우터 결정. confidence 0~1, reason은 분류 근거(메트릭·디버깅용). */
data class RouteDecision(
    val route: Route,
    val confidence: Double,
    val reason: String,
)
