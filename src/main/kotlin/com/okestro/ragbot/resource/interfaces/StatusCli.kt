package com.okestro.ragbot.resource.interfaces

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.resource.application.WidgetBuilder
import com.okestro.ragbot.resource.infrastructure.HttpPrometheusClient

/**
 * status_donut 수동 확인용 독립 실행기(RoutingCli 패턴). Spring 컨텍스트 없이 실 Prometheus만 조립한다.
 * 실행: PROMETHEUS_URL=... ./gradlew statusCli -q --console=plain
 *
 * Resilience4j 애노테이션은 Spring 프록시 경유가 아니라 여기선 적용되지 않는다(조회 자체만 확인).
 */
private const val STATUS_PROMQL = "count by(status)(openstack_nova_server_status)"

fun main() {
    val baseUrl = System.getenv("PROMETHEUS_URL")
        ?: error("PROMETHEUS_URL 환경변수가 필요합니다")

    val props = AppProperties(
        resource = AppProperties.Resource(
            prometheus = AppProperties.Resource.Prometheus(baseUrl = baseUrl, sslVerify = false),
        ),
    )
    val mapper = jacksonObjectMapper()
    val client = HttpPrometheusClient(props, mapper)

    println("promql: $STATUS_PROMQL")

    val samples = client.queryLabeled(STATUS_PROMQL)
    println("\n[1] queryLabeled 원본 (라벨 보존 확인)")
    samples.forEach { println("    labels=${it.labels}  value=${it.value}") }

    // 대조: 기존 query()는 instance_name/domain 라벨이 없어 전부 버린다.
    val viaQuery = client.query(STATUS_PROMQL, "count")
    println("\n[2] 대조 — 기존 query(): ${viaQuery.size}건 (0이면 1b가 query()를 못 쓰는 이유)")

    val widget = WidgetBuilder.statusDonut(samples)
    println("\n[3] statusDonut 위젯 JSON (= FE가 받는 형태)")
    println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(widget))
}
