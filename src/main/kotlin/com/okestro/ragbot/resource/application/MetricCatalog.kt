package com.okestro.ragbot.resource.application

import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.resource.domain.MetricCatalogEntry
import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.PromPattern
import org.springframework.stereotype.Component

@Component
class MetricCatalog(properties: AppProperties) {

    private val entries: Map<MetricPattern, MetricCatalogEntry> =
        properties.resource.catalog.entries.associate { (key, cfg) ->
            MetricPattern.valueOf(key) to MetricCatalogEntry(
                pattern = PromPattern.valueOf(cfg.pattern),
                rawMetric = cfg.rawMetric,
                unit = cfg.unit,
            )
        }

    fun lookup(metric: MetricPattern): MetricCatalogEntry =
        entries[metric] ?: error("카탈로그에 없는 지표: $metric")

    fun supportedMetricNames(): List<String> = MetricPattern.entries.map { it.name }
}
