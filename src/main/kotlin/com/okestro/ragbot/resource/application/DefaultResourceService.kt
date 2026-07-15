package com.okestro.ragbot.resource.application

import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.resource.domain.PromQlBuilder
import com.okestro.ragbot.resource.domain.ResourceExtraction
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service

@Service
class DefaultResourceService(
    private val extractor: MetricQueryExtractor,
    private val catalog: MetricCatalog,
    private val prometheus: PrometheusClient,
    private val inventoryRepository: ObjectProvider<InventoryRepository>,
    private val properties: AppProperties,
) : ResourceService {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(history: List<ConversationMessage>): ResourceService.Result {
        tempStatusDonut(history)?.let { return it }

        return when (val extraction = extractor.extract(history)) {
            is ResourceExtraction.NeedsClarification -> ResourceService.Result(extraction.message, needsClarification = true)
            is ResourceExtraction.Resolved -> {
                val query = extraction.query
                val entry = catalog.lookup(query.metric)
                val promql = PromQlBuilder.build(query, entry)
                log.info("resource-pipeline metric={} promql=\"{}\"", query.metric, promql)

                val samples = prometheus.query(promql, entry.unit)
                val answer = ResourceAnswerTemplate.build(query, samples)
                val sev = properties.resource.severity
                val widget = WidgetBuilder.metricRank(query, samples, promql, entry.unit, sev.warnPercent, sev.critPercent)
                val followups = FollowupBuilder.forMetric(query, samples)
                ResourceService.Result(answer, widgets = listOf(widget), followups = followups)
            }
            is ResourceExtraction.InventoryResolved -> {
                // INVENTORY(cb_common) 트랙. enabled=false면 리포지토리 빈이 없으므로 안내 후 종료.
                val repository = inventoryRepository.ifAvailable
                    ?: return ResourceService.Result(
                        "리소스 인벤토리 조회가 비활성화되어 있습니다.",
                        needsClarification = true,
                    )
                val inv = properties.resource.inventory
                val result = repository.find(extraction.query, inv.providerUuid, inv.maxRows)
                log.info("resource-inventory kind={} mode={} total={}", result.kind, extraction.query.mode, result.total)
                val widget = WidgetBuilder.inventoryCount(result)
                val followups = FollowupBuilder.forInventory(result)
                ResourceService.Result(
                    InventoryAnswerTemplate.render(result),
                    widgets = listOf(widget),
                    followups = followups,
                )
            }
        }
    }

    // ── TEMP(#21): status_donut 임시 키워드 배선 ────────────────────────────────
    //
    // 삭제 조건: #21에서 추출기/라우터에 "상태 분포" 의도가 들어오면 이 블록(+ 짝 테스트
    //   DefaultResourceServiceStatusTest)을 통째로 지우고 ResourceExtraction 갈래로 옮긴다.
    //
    // 왜 LLM이 아니라 임의 if인가:
    //   1) 불변식 2 — 의도 하나 붙이자고 추출기 프롬프트·스키마를 키우면 요청당 토큰이 는다.
    //   2) 충돌 회피 — ResourcePrompts/LlmMetricQueryExtractor는 #21 소유다. 지금 손대면 겹친다.
    //   3) 되돌리기 — if 한 덩어리는 지우면 끝이지만 프롬프트는 되돌리기가 지저분하다.
    //
    // 한계: 키워드 매칭이라 "지금 몇 대나 죽어있어?" 같은 변형은 못 잡는다. 그건 #21의 몫.
    private val statusKeywords = listOf("상태 분포", "상태분포", "상태별", "인스턴스 상태")

    private val statusPromql = "count by(status)(openstack_nova_server_status)"

    private fun tempStatusDonut(history: List<ConversationMessage>): ResourceService.Result? {
        val question = history.lastOrNull { it.role == ConversationMessage.Role.USER }?.content ?: return null
        if (statusKeywords.none { question.contains(it) }) return null

        log.info("resource-status-temp promql=\"{}\"", statusPromql)
        val widget = WidgetBuilder.statusDonut(prometheus.queryLabeled(statusPromql))
        return ResourceService.Result(StatusAnswerTemplate.render(widget), widgets = listOf(widget))
    }
}
