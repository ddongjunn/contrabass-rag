package com.okestro.ragbot.resource.application

import com.okestro.ragbot.resource.domain.PromQlBuilder
import com.okestro.ragbot.resource.domain.ResourceExtraction
import com.okestro.ragbot.chat.domain.ConversationMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ResourceService(
    private val extractor: MetricQueryExtractor,
    private val catalog: MetricCatalog,
    private val prometheus: PrometheusClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Result(val answer: String, val needsClarification: Boolean = false)

    fun handle(history: List<ConversationMessage>): Result {
        return when (val extraction = extractor.extract(history)) {
            is ResourceExtraction.NeedsClarification -> Result(extraction.message, needsClarification = true)
            is ResourceExtraction.Resolved -> {
                val query = extraction.query
                val entry = catalog.lookup(query.metric)
                val promql = PromQlBuilder.build(query, entry)
                log.info("resource-pipeline metric={} promql=\"{}\"", query.metric, promql)

                val samples = prometheus.query(promql, entry.unit)
                val answer = ResourceAnswerTemplate.build(query, samples)
                Result(answer)
            }
        }
    }
}
