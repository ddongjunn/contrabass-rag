package com.okestro.ragbot.resource.application

import com.okestro.ragbot.chat.application.ChatResult
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.resource.domain.ResourceExtraction
import com.okestro.ragbot.routing.domain.ConversationMessage
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/**
 * RESOURCE 경로 오케스트레이터. 추출(1회 LLM) 결과를 트랙별로 분기한다:
 * - InventoryResolved → cb_common 조회 + 답변 템플릿(LLM 무호출)
 * - NeedsClarification → 되물음
 * - Resolved(METRIC) → 지표 응답 생성은 METRIC 트랙(R2~, Prometheus)에서 연결 — 이 트랙 범위 밖(seam)
 *
 * INVENTORY가 활성(`app.resource.inventory.enabled=true`)일 때만 빈 생성(2차 DataSource·리포지토리와 수명 동일).
 * R4(DefaultChatService 배선)는 METRIC 트랙과 공유라 별도 단계에서 연결한다.
 */
@Service
@ConditionalOnProperty(prefix = "app.resource.inventory", name = ["enabled"], havingValue = "true")
class ResourceService(
    private val extractor: MetricQueryExtractor,
    private val inventoryRepository: InventoryRepository,
    private val properties: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun handle(history: List<ConversationMessage>): ChatResult =
        when (val extraction = extractor.extract(history)) {
            is ResourceExtraction.InventoryResolved -> {
                val inv = properties.resource.inventory
                val result = inventoryRepository.find(extraction.query, inv.providerUuid, inv.maxRows)
                log.info(
                    "resource-inventory kind={} mode={} total={}",
                    result.kind, extraction.query.mode, result.total,
                )
                InventoryAnswerTemplate.render(result)
            }

            is ResourceExtraction.NeedsClarification ->
                ChatResult(answer = extraction.message, sources = emptyList())

            is ResourceExtraction.Resolved ->
                ChatResult(
                    answer = "지표(${extraction.query.metric}) 조회 응답 생성은 METRIC 트랙에서 연결됩니다.",
                    sources = emptyList(),
                )
        }
}
