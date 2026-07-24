package com.okestro.ragbot.resource.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.resource.domain.InventoryFilters
import com.okestro.ragbot.resource.domain.InventoryKind
import com.okestro.ragbot.resource.domain.InventoryQuery
import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.ResourceExtraction
import com.okestro.ragbot.resource.domain.ResourceQuery
import com.okestro.ragbot.resource.domain.TrendQuery
import com.okestro.ragbot.routing.application.LlmClient
import com.okestro.ragbot.routing.application.LlmRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class LlmMetricQueryExtractor(
    private val llmClient: LlmClient,
    private val properties: AppProperties,
    private val objectMapper: ObjectMapper,
) : MetricQueryExtractor {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cfg get() = properties.resource
    private val metricKeys = MetricPattern.entries.map { it.name }

    override fun extract(history: List<ConversationMessage>): ResourceExtraction {
        val question = history.lastOrNull()?.content ?: ""
        val request = LlmRequest(
            system = ResourcePrompts.SYSTEM,
            messages = history,
            model = cfg.extractionModel,
            temperature = cfg.temperature,
            jsonSchema = ResourcePrompts.schema(metricKeys),
        )

        val start = System.nanoTime()
        return try {
            val rawJson = llmClient.complete(request)
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            log.info("extraction-raw question=\"{}\" model={} latencyMs={} json={}", question, cfg.extractionModel, latencyMs, rawJson)

            val parsed = objectMapper.readValue(rawJson, RawExtraction::class.java)
            val result = toExtraction(parsed)
            logResult(question, parsed.confidence, result)
            result
        } catch (e: Exception) {
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            log.warn("extraction-failed question=\"{}\" latencyMs={} error={}", question, latencyMs, e.message)
            ResourceExtraction.NeedsClarification("요청을 이해하지 못했습니다. 어떤 지표를 조회할까요?")
        }
    }

    private fun logResult(question: String, confidence: Double, result: ResourceExtraction) {
        when (result) {
            is ResourceExtraction.Resolved -> {
                val q = result.query
                log.info(
                    "extraction-resolved target=METRIC question=\"{}\" metric={} sort={} topN={} window={} project={} instanceName={} confidence={}",
                    question, q.metric, q.sort, q.topN, q.window, q.project ?: "(전체)", q.instanceName ?: "(전체)", confidence,
                )
            }
            is ResourceExtraction.InventoryResolved -> {
                val q = result.query
                val f = q.filters
                log.info(
                    "extraction-resolved target=INVENTORY question=\"{}\" kind={} mode={} status={}{} host={} project={} createEnable={} confidence={}",
                    question, q.kind, q.mode, f.statusOp, f.status ?: "(없음)", f.hypervisorHostName ?: "(전체)",
                    f.projectId ?: "(전체)", f.instanceCreateEnable, confidence,
                )
            }
            is ResourceExtraction.StatusResolved ->
                log.info("extraction-resolved target=STATUS question=\"{}\" confidence={}", question, confidence)
            is ResourceExtraction.ThresholdResolved ->
                log.info("extraction-resolved target=THRESHOLD question=\"{}\" confidence={}", question, confidence)
            is ResourceExtraction.QuotaResolved ->
                log.info("extraction-resolved target=QUOTA question=\"{}\" project={} confidence={}", question, result.project, confidence)
            is ResourceExtraction.ProjectUsageResolved ->
                log.info("extraction-resolved target=PROJECT_USAGE question=\"{}\" confidence={}", question, confidence)
            is ResourceExtraction.TrendResolved -> {
                val q = result.query
                log.info(
                    "extraction-resolved target=TREND question=\"{}\" metric={} range={} project={} instanceName={} confidence={}",
                    question, q.metric, q.range, q.project ?: "(전체)", q.instanceName ?: "(전체)", confidence,
                )
            }
            is ResourceExtraction.NeedsClarification ->
                log.info("extraction-clarify question=\"{}\" confidence={} message=\"{}\"", question, confidence, result.message)
        }
    }

    private fun toExtraction(raw: RawExtraction): ResourceExtraction {
        if (raw.clarificationNeeded || raw.confidence < cfg.minConfidence) {
            val msg = raw.clarificationMessage.ifBlank {
                "무엇을 조회할까요? 지표(CPU/메모리/네트워크/디스크) 또는 리소스 목록(인스턴스/볼륨/스냅샷)을 알려주세요."
            }
            return ResourceExtraction.NeedsClarification(msg)
        }
        return when (raw.target.uppercase()) {
            "INVENTORY" -> toInventory(raw)
            "STATUS" -> ResourceExtraction.StatusResolved       // 조건 없음 — 쿼리 고정
            "THRESHOLD" -> ResourceExtraction.ThresholdResolved // 임계값은 application.yml에서
            "QUOTA" -> toQuota(raw)
            "PROJECT_USAGE" -> ResourceExtraction.ProjectUsageResolved  // 조건 없음 — 전체 tenant 비교
            "TREND" -> toTrend(raw)
            else -> toMetric(raw)
        }
    }

    private fun toMetric(raw: RawExtraction): ResourceExtraction {
        val metric = runCatching { MetricPattern.valueOf(raw.metric) }.getOrElse {
            return ResourceExtraction.NeedsClarification(
                "지원하지 않는 지표입니다. 조회 가능한 지표: CPU 사용률, 메모리, 네트워크(RX/TX), 디스크(읽기/쓰기)"
            )
        }
        return ResourceExtraction.Resolved(
            ResourceQuery(
                metric = metric,
                sort = runCatching { ResourceQuery.Sort.valueOf(raw.sort) }.getOrDefault(ResourceQuery.Sort.DESC),
                topN = raw.topN.coerceIn(1, 20),
                window = raw.window.ifBlank { cfg.defaultWindow },
                project = raw.project?.takeIf { it.isNotBlank() },
                instanceName = raw.instanceName?.takeIf { it.isNotBlank() },
            )
        )
    }

    private fun toTrend(raw: RawExtraction): ResourceExtraction {
        val metric = runCatching { MetricPattern.valueOf(raw.metric) }.getOrElse {
            return ResourceExtraction.NeedsClarification(
                "지원하지 않는 지표입니다. 조회 가능한 지표: CPU 사용률, 메모리, 네트워크(RX/TX), 디스크(읽기/쓰기)"
            )
        }
        return ResourceExtraction.TrendResolved(
            TrendQuery(
                metric = metric,
                range = raw.range.ifBlank { cfg.trend.defaultRange },
                window = raw.window.ifBlank { cfg.defaultWindow },
                project = raw.project?.takeIf { it.isNotBlank() },
                instanceName = raw.instanceName?.takeIf { it.isNotBlank() },
            )
        )
    }

    /**
     * QUOTA는 대상 테넌트가 있어야 게이지를 채울 수 있지만, "없으면 되묻는다"는 결정은
     * 서비스 계층으로 옮겼다(호출부 컨텍스트로 폴백할 여지를 주기 위해). 여기서는 그대로 싣기만 한다.
     */
    private fun toQuota(raw: RawExtraction): ResourceExtraction =
        ResourceExtraction.QuotaResolved(raw.project?.takeIf { it.isNotBlank() })

    private fun toInventory(raw: RawExtraction): ResourceExtraction {
        val kind = runCatching { InventoryKind.valueOf(raw.kind) }.getOrElse {
            return ResourceExtraction.NeedsClarification(
                "어떤 리소스를 조회할까요? (인스턴스, 인스턴스 스냅샷, 볼륨, 볼륨 스냅샷 중)"
            )
        }
        return ResourceExtraction.InventoryResolved(
            InventoryQuery(
                kind = kind,
                mode = runCatching { InventoryQuery.Mode.valueOf(raw.mode) }.getOrDefault(InventoryQuery.Mode.LIST),
                filters = InventoryFilters(
                    status = raw.status?.takeIf { it.isNotBlank() },
                    statusOp = runCatching { InventoryFilters.Op.valueOf(raw.statusOp) }.getOrDefault(InventoryFilters.Op.EQ),
                    projectId = raw.project?.takeIf { it.isNotBlank() },
                    hypervisorHostName = raw.hypervisorHostName?.takeIf { it.isNotBlank() },
                    instanceCreateEnable = raw.instanceCreateEnable,
                ),
            )
        )
    }

    private data class RawExtraction(
        val target: String = "METRIC",
        val clarificationNeeded: Boolean = false,
        val clarificationMessage: String = "",
        // METRIC
        val metric: String = "",
        val sort: String = "DESC",
        val topN: Int = 5,
        val window: String = "5m",
        val instanceName: String? = null,
        // TREND
        val range: String = "",

        // INVENTORY
        val kind: String = "",
        val mode: String = "LIST",
        val status: String? = null,
        val statusOp: String = "EQ",
        val hypervisorHostName: String? = null,
        val instanceCreateEnable: Boolean? = null,
        // 공통
        val project: String? = null,
        val confidence: Double = 0.0,
    )
}
