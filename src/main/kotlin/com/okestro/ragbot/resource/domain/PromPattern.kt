package com.okestro.ragbot.resource.domain

enum class PromPattern {
    RATIO_TOPK,        // CPU: rate ÷ virtual_cpus × 100 (0~100%)
    GAUGE_TOPK,        // Memory: 이미 % — 계산 불필요
    COUNTER_RATE_TOPK,
    /** 조인·rate 없이 raw-metric(표현식 허용)을 그대로 — 클러스터 단위 게이지. */
    GAUGE_RAW, // Network/Disk: rate sum
}
