package com.okestro.ragbot.resource.domain

data class MetricSample(
    val instanceName: String,
    val value: Double,
    val unit: String,
    val projectName: String? = null,
)
