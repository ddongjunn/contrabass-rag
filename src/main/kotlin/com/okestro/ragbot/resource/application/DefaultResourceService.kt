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
}
