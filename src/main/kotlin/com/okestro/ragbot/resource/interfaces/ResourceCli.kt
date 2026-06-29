package com.okestro.ragbot.resource.interfaces

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.resource.application.LlmMetricQueryExtractor
import com.okestro.ragbot.resource.application.MetricCatalog
import com.okestro.ragbot.resource.application.ResourceAnswerTemplate
import com.okestro.ragbot.resource.domain.MetricSample
import com.okestro.ragbot.resource.domain.PromQlBuilder
import com.okestro.ragbot.resource.domain.ResourceExtraction
import com.okestro.ragbot.resource.infrastructure.HttpPrometheusClient
import com.okestro.ragbot.routing.domain.ConversationMessage
import com.okestro.ragbot.routing.domain.ConversationMessage.Role
import com.okestro.ragbot.routing.infrastructure.OpenAiRouterLlmClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.web.client.RestClient

/**
 * RESOURCE 경로 E2E 수동 확인용 독립 실행기.
 * 실행: OPENAI_API_KEY=... [PROMETHEUS_URL=...] ./gradlew resourceCli -q --console=plain
 * PROMETHEUS_URL 없으면 추출 + PromQL 출력만, 있으면 Prometheus 조회 + 답변까지 출력.
 */
fun main() {
    val apiKey = System.getenv("OPENAI_API_KEY") ?: error("OPENAI_API_KEY 환경변수가 필요합니다")
    val prometheusUrl = System.getenv("PROMETHEUS_URL")

    val props = buildAppProperties(prometheusUrl)
    val objectMapper = jacksonObjectMapper()
    val api = OpenAiApi.builder().apiKey(apiKey).build()
    val chatModel = OpenAiChatModel.builder().openAiApi(api).build()
    val extractor = LlmMetricQueryExtractor(OpenAiRouterLlmClient(chatModel), props, objectMapper)
    val catalog = MetricCatalog(props)
    val restClient = prometheusUrl?.let {
        RestClient.builder().baseUrl(it).requestFactory(HttpPrometheusClient.insecureRequestFactory()).build()
    }

    println("인프라 지표 조회 질문을 입력하세요 (빈 줄/Ctrl-D 종료):")
    if (prometheusUrl == null) println("  ※ PROMETHEUS_URL 미설정 — 추출·PromQL 출력만")

    generateSequence(::readLine).forEach { line ->
        if (line.isBlank()) return@forEach

        when (val result = extractor.extract(listOf(ConversationMessage(Role.USER, line)))) {
            is ResourceExtraction.NeedsClarification -> println("→ [되물음] ${result.message}")
            is ResourceExtraction.Resolved -> {
                val q = result.query
                println("→ [추출] metric=${q.metric}  sort=${q.sort}  topN=${q.topN}  window=${q.window}  project=${q.project ?: "(전체)"}  instance=${q.instanceName ?: "(전체)"}")

                val entry = catalog.lookup(q.metric)
                val promql = PromQlBuilder.build(q, entry)
                println("→ [PromQL] $promql")

                if (restClient != null) {
                    val samples = queryPrometheus(restClient, objectMapper, promql, entry.unit)
                    println("→ [답변]\n${ResourceAnswerTemplate.build(q, samples)}")
                }
            }
        }
    }
}

private fun queryPrometheus(
    client: RestClient,
    objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
    promql: String,
    unit: String,
): List<MetricSample> {
    return try {
        val body = client.get()
            .uri { it.path("/api/v1/query").queryParam("query", promql).build() }
            .retrieve()
            .body(String::class.java) ?: return emptyList()

        val response = objectMapper.readValue(body, PrometheusResponse::class.java)
        if (response.status != "success") { println("  ※ Prometheus 응답 오류: status=${response.status}"); return emptyList() }

        response.data.result.mapNotNull { r ->
            val instanceName = r.metric["instance_name"] ?: r.metric["domain"] ?: return@mapNotNull null
            val value = r.value.getOrNull(1)?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
            MetricSample(instanceName, value, unit, r.metric["project_name"])
        }
    } catch (e: Exception) {
        println("  ※ Prometheus 호출 실패: ${e.message}")
        emptyList()
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class PrometheusResponse(
    val status: String = "",
    val data: Data = Data(),
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Data(val result: List<Result> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Result(
        val metric: Map<String, String> = emptyMap(),
        val value: List<Any> = emptyList(),
    )
}

private fun buildAppProperties(prometheusUrl: String?): AppProperties {
    fun entry(pattern: String, rawMetric: String, unit: String) =
        AppProperties.Resource.CatalogEntryConfig(pattern = pattern, rawMetric = rawMetric, unit = unit)
    return AppProperties(
        resource = AppProperties.Resource(
            catalog = mapOf(
                "INSTANCE_CPU"        to entry("RATIO_TOPK",        "libvirt_domain_info_cpu_time_seconds_total",          "%"),
                "INSTANCE_MEMORY"     to entry("GAUGE_TOPK",        "libvirt_domain_memory_stats_used_percent",            "%"),
                "INSTANCE_NETWORK_RX" to entry("COUNTER_RATE_TOPK", "libvirt_domain_interface_stats_receive_bytes_total",  "B/s"),
                "INSTANCE_NETWORK_TX" to entry("COUNTER_RATE_TOPK", "libvirt_domain_interface_stats_transmit_bytes_total", "B/s"),
                "INSTANCE_DISK_READ"  to entry("COUNTER_RATE_TOPK", "libvirt_domain_block_stats_read_requests_total",     "req/s"),
                "INSTANCE_DISK_WRITE" to entry("COUNTER_RATE_TOPK", "libvirt_domain_block_stats_write_requests_total",    "req/s"),
            ),
            prometheus = AppProperties.Resource.Prometheus(baseUrl = prometheusUrl ?: ""),
        )
    )
}
