package com.okestro.ragbot.resource.domain

data class ResourceQuery(
    val metric: MetricPattern,
    val sort: Sort = Sort.DESC,
    val topN: Int = 5,
    val window: String = "5m",
    val project: String? = null,
) {
    enum class Sort { DESC, ASC }
}
