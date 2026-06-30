package com.okestro.ragbot.resource.domain

object PromQlBuilder {

    private const val VCPUS_METRIC = "libvirt_domain_info_virtual_cpus"
    private const val INFO_METRIC = "libvirt_domain_openstack_info"

    fun build(query: ResourceQuery, entry: MetricCatalogEntry): String {
        val rankFn = if (query.sort == ResourceQuery.Sort.DESC) "topk" else "bottomk"
        val filters = listOfNotNull(
            query.project?.let { """project_name="$it"""" },
            query.instanceName?.let { """instance_name="$it"""" },
        )
        val infoSelector = if (filters.isEmpty()) INFO_METRIC
                           else "$INFO_METRIC{${filters.joinToString(",")}}"
        val enrich = "* on(domain) group_left(instance_name, project_name) $infoSelector"

        return when (entry.pattern) {
            PromPattern.RATIO_TOPK -> buildRatioTopk(query, entry, rankFn, enrich)
            PromPattern.GAUGE_TOPK -> buildGaugeTopk(query, entry, rankFn, enrich)
            PromPattern.COUNTER_RATE_TOPK -> buildCounterRateTopk(query, entry, rankFn, enrich)
        }
    }

    // rate ÷ vCPUs × 100 — CPU 사용률(%)
    // max by(domain): 동일 domain이 여러 hypervisor에 중복 집계될 때 many-to-many 방지
    private fun buildRatioTopk(q: ResourceQuery, e: MetricCatalogEntry, fn: String, enrich: String) =
        "$fn(${q.topN}, (sum by(domain)(rate(${e.rawMetric}[${q.window}])) / on(domain) max by(domain)($VCPUS_METRIC) * 100) $enrich)"

    // 이미 % 값인 게이지 — Memory
    private fun buildGaugeTopk(q: ResourceQuery, e: MetricCatalogEntry, fn: String, enrich: String) =
        "$fn(${q.topN}, ${e.rawMetric} $enrich)"

    // counter rate sum — Network / Disk
    private fun buildCounterRateTopk(q: ResourceQuery, e: MetricCatalogEntry, fn: String, enrich: String) =
        "$fn(${q.topN}, sum by(domain)(rate(${e.rawMetric}[${q.window}])) $enrich)"
}
