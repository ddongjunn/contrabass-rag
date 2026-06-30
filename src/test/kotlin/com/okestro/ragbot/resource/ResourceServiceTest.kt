package com.okestro.ragbot.resource

import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.resource.application.InventoryRepository
import com.okestro.ragbot.resource.application.MetricQueryExtractor
import com.okestro.ragbot.resource.application.ResourceService
import com.okestro.ragbot.resource.domain.InventoryFilters
import com.okestro.ragbot.resource.domain.InventoryKind
import com.okestro.ragbot.resource.domain.InventoryQuery
import com.okestro.ragbot.resource.domain.InventoryResult
import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.ResourceExtraction
import com.okestro.ragbot.resource.domain.ResourceQuery
import com.okestro.ragbot.routing.domain.ConversationMessage
import com.okestro.ragbot.routing.domain.ConversationMessage.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DB-3: ResourceService 트랙 분기(INVENTORY 조회 / CLARIFY / METRIC seam) 검증 — 페이크 추출기·리포지토리. */
class ResourceServiceTest {

    private val props = AppProperties()
    private val history = listOf(ConversationMessage(Role.USER, "질문"))

    private fun service(extraction: ResourceExtraction, result: InventoryResult? = null): ResourceService {
        val extractor = object : MetricQueryExtractor {
            override fun extract(history: List<ConversationMessage>): ResourceExtraction = extraction
        }
        val repo = object : InventoryRepository {
            override fun find(query: InventoryQuery, providerUuid: String, limit: Int): InventoryResult =
                result ?: error("repository는 호출되지 않아야 함")
        }
        return ResourceService(extractor, repo, props)
    }

    @Test
    fun `InventoryResolved — 리포지토리 조회 후 템플릿 답변`() {
        val query = InventoryQuery(InventoryKind.INSTANCE, InventoryQuery.Mode.COUNT, InventoryFilters(status = "ACTIVE", statusOp = InventoryFilters.Op.NEQ))
        val result = InventoryResult(InventoryKind.INSTANCE, emptyList(), 7, query.filters)

        val out = service(ResourceExtraction.InventoryResolved(query), result).handle(history)

        assertTrue(out.answer.contains("인스턴스 개수는 7건"), out.answer)
        assertTrue(out.sources.any { it.contains("건수: 7건") })
    }

    @Test
    fun `NeedsClarification — 되물음 메시지 그대로`() {
        val out = service(ResourceExtraction.NeedsClarification("무엇을 조회할까요?")).handle(history)

        assertEquals("무엇을 조회할까요?", out.answer)
        assertTrue(out.sources.isEmpty())
    }

    @Test
    fun `Resolved(METRIC) — METRIC 트랙 seam 안내, 리포지토리 미호출`() {
        val metric = ResourceExtraction.Resolved(ResourceQuery(metric = MetricPattern.INSTANCE_CPU))
        val out = service(metric).handle(history)

        assertTrue(out.answer.contains("METRIC 트랙"), out.answer)
        assertTrue(out.sources.isEmpty())
    }
}
