package com.okestro.ragbot.resource.domain

data class MetricCatalogEntry(
    val pattern: PromPattern,
    val rawMetric: String,
    val unit: String,
)
