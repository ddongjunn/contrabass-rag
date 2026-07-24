package com.okestro.ragbot.resource.domain

/**
 * 라벨을 그대로 실어나르는 Prometheus 샘플.
 *
 * MetricSample은 instance_name/domain 라벨에 고정돼 있어(TopN 전용) status·tenant처럼
 * 다른 라벨의 시계열이나 라벨 없는 스칼라를 담지 못한다. 1b 위젯(status_donut·
 * project_usage_bar·threshold_banner)이 그 라벨들을 쓰므로 별도 타입으로 받는다.
 */
data class LabeledSample(
    val labels: Map<String, String>,
    val value: Double,
)
