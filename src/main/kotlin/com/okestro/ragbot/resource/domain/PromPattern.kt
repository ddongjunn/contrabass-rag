package com.okestro.ragbot.resource.domain

enum class PromPattern {
    RATIO_TOPK,        // CPU: rate ÷ virtual_cpus × 100 (0~100%)
    GAUGE_TOPK,        // Memory: 이미 % — 계산 불필요
    COUNTER_RATE_TOPK, // Network/Disk: rate sum
}
