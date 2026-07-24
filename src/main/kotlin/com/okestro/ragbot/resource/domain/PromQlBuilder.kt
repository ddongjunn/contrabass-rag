package com.okestro.ragbot.resource.domain

object PromQlBuilder {

    /** CPU 사용률 식의 분모. DefaultResourceService의 임계 비교 식도 같은 상수를 써야 갈라지지 않는다. */
    const val VCPUS_METRIC = "libvirt_domain_info_virtual_cpus"
    private const val INFO_METRIC = "libvirt_domain_openstack_info"

    fun build(query: ResourceQuery, entry: MetricCatalogEntry): String {
        val rankFn = if (query.sort == ResourceQuery.Sort.DESC) "topk" else "bottomk"
        val enrich = enrich(query.project, query.instanceName)

        return when (entry.pattern) {
            PromPattern.RATIO_TOPK -> buildRatioTopk(query, entry, rankFn, enrich)
            PromPattern.GAUGE_TOPK -> buildGaugeTopk(query, entry, rankFn, enrich)
            PromPattern.COUNTER_RATE_TOPK -> buildCounterRateTopk(query, entry, rankFn, enrich)
            PromPattern.GAUGE_RAW -> "$rankFn(${query.topN}, ${entry.rawMetric})"
        }
    }

    /**
     * TREND(query_range)용 — topk 래핑 없이 표현식만. 순위는 시점마다 달라져 topk를 넣으면
     * 시리즈 구성원이 스텝마다 바뀐다(구멍 난 라인). 시리즈 상한은 WidgetBuilder가 마지막 값 기준으로 자른다.
     *
     * 조인 우측(info 메트릭)은 max by(...)로 dedupe한다 — query_range에선 domain당 중복 시리즈
     * (하이퍼바이저 라벨 차이 등)가 구간 안에 실존해 many-to-many 422가 난다(실측 2026-07-24).
     * instant 경로(build)는 현재 운영에서 문제가 없어 그대로 둔다.
     */
    fun buildTrend(query: TrendQuery, entry: MetricCatalogEntry): String {
        val enrich = trendEnrich(query.project, query.instanceName)
        return when (entry.pattern) {
            PromPattern.RATIO_TOPK ->
                "(sum by(domain)(rate(${entry.rawMetric}[${query.window}])) " +
                    "/ on(domain) max by(domain)($VCPUS_METRIC) * 100) $enrich"
            PromPattern.GAUGE_TOPK -> "${entry.rawMetric} $enrich"
            PromPattern.COUNTER_RATE_TOPK -> "sum by(domain)(rate(${entry.rawMetric}[${query.window}])) $enrich"
            PromPattern.GAUGE_RAW -> entry.rawMetric
        }
    }

    private fun enrich(project: String?, instanceName: String?): String =
        "* on(domain) group_left(instance_name, project_name) ${infoSelector(project, instanceName)}"

    private fun trendEnrich(project: String?, instanceName: String?): String =
        "* on(domain) group_left(instance_name, project_name) " +
            "max by(domain, instance_name, project_name)(${infoSelector(project, instanceName)})"

    private fun infoSelector(project: String?, instanceName: String?): String {
        val filters = listOfNotNull(
            project?.let { """project_name="${escapeLabel(it)}"""" },
            instanceName?.let { """instance_name="${escapeLabel(it)}"""" },
        )
        return if (filters.isEmpty()) INFO_METRIC else "$INFO_METRIC{${filters.joinToString(",")}}"
    }

    /**
     * PromQL 라벨값 이스케이프. project·instanceName은 **LLM이 사용자 질문에서 뽑은 자유 문자열**이라
     * 그대로 넣으면 셀렉터를 닫고 다른 필터를 붙일 수 있다(쿼터 경로 리뷰에서 실증된 패턴 —
     * 그 경로가 삭제되면서 이스케이프도 함께 사라져 여기로 되살린다). 정확 일치 매처라 위험한 건
     * `"`와 `\` 둘뿐이고, 이스케이프하면 그런 이름을 찾다 0건으로 안전하게 떨어진다.
     */
    private fun escapeLabel(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

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
