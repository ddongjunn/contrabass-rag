package com.okestro.ragbot.resource.domain

enum class MetricPattern {
    INSTANCE_CPU,
    INSTANCE_MEMORY,
    INSTANCE_NETWORK_RX,
    INSTANCE_NETWORK_TX,
    INSTANCE_DISK_READ,
    INSTANCE_DISK_WRITE,
    // 클러스터 시계열(GAUGE_RAW) — 인스턴스 라벨이 없어 TREND 전용(METRIC topk엔 빈 결과)
    TOTAL_VMS,
    STORAGE_USED,
}
