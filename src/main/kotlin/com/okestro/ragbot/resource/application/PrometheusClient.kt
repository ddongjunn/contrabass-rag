package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.MetricSample

interface PrometheusClient {
    fun query(promql: String, unit: String): List<MetricSample>
}
