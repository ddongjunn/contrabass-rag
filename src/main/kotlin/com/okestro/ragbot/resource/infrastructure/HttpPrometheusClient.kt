package com.okestro.ragbot.resource.infrastructure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.resource.application.PrometheusClient
import com.okestro.ragbot.resource.domain.MetricSample
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Component
class HttpPrometheusClient(
    properties: AppProperties,
    private val objectMapper: ObjectMapper,
) : PrometheusClient {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cfg = properties.resource.prometheus

    private val restClient: RestClient = run {
        val builder = RestClient.builder().baseUrl(cfg.baseUrl)
        if (!cfg.sslVerify) builder.requestFactory(insecureRequestFactory())
        builder.build()
    }

    @Retry(name = "prometheus")
    @CircuitBreaker(name = "prometheus")
    override fun query(promql: String, unit: String): List<MetricSample> {
        log.info("prometheus-query promql=\"{}\"", promql)
        val start = System.nanoTime()

        val body = restClient.get()
            .uri { it.path("/api/v1/query").queryParam("query", promql).build() }
            .retrieve()
            .body(String::class.java)
            ?: return emptyList()

        val latencyMs = (System.nanoTime() - start) / 1_000_000
        val response = objectMapper.readValue(body, PrometheusResponse::class.java)

        if (response.status != "success") {
            log.warn("prometheus-error status={} latencyMs={}", response.status, latencyMs)
            return emptyList()
        }

        val samples = response.data.result.mapNotNull { it.toSample(unit) }
        log.info("prometheus-result latencyMs={} count={}", latencyMs, samples.size)
        return samples
    }

    companion object {
        fun insecureRequestFactory(): JdkClientHttpRequestFactory {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<out X509Certificate>?, t: String?) {}
                override fun checkServerTrusted(c: Array<out X509Certificate>?, t: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            val sslCtx = SSLContext.getInstance("TLS")
            sslCtx.init(null, trustAll, SecureRandom())
            val httpClient = HttpClient.newBuilder()
                .sslContext(sslCtx)
                .sslParameters(SSLParameters().apply { endpointIdentificationAlgorithm = "" })
                .build()
            return JdkClientHttpRequestFactory(httpClient)
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
        ) {
            fun toSample(unit: String): MetricSample? {
                val instanceName = metric["instance_name"] ?: metric["domain"] ?: return null
                val rawValue = value.getOrNull(1)?.toString()?.toDoubleOrNull() ?: return null
                return MetricSample(
                    instanceName = instanceName,
                    value = rawValue,
                    unit = unit,
                    projectName = metric["project_name"],
                )
            }
        }
    }
}
