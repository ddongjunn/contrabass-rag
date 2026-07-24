package com.okestro.ragbot.resource

import java.time.Instant
import java.time.Duration
import com.okestro.ragbot.resource.domain.RangeSeries
import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.chat.domain.ConversationMessage.Role
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.resource.application.DefaultResourceService
import com.okestro.ragbot.resource.application.InventoryRepository
import com.okestro.ragbot.resource.application.MetricCatalog
import com.okestro.ragbot.resource.application.MetricQueryExtractor
import com.okestro.ragbot.resource.application.PrometheusClient
import com.okestro.ragbot.resource.domain.InventoryFilters
import com.okestro.ragbot.resource.domain.InventoryKind
import com.okestro.ragbot.resource.domain.InventoryQuery
import com.okestro.ragbot.resource.domain.InventoryResult
import com.okestro.ragbot.resource.domain.LabeledSample
import com.okestro.ragbot.resource.domain.MetricSample
import com.okestro.ragbot.resource.domain.ResourceExtraction
import org.springframework.beans.factory.ObjectProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DB-3(머지 후): DefaultResourceService의 INVENTORY 분기 검증 — 페이크 추출기·리포지토리.
 * METRIC 분기는 main의 카탈로그/Prometheus 의존이라 여기선 다루지 않는다.
 */
class DefaultResourceServiceInventoryTest {

    private val props = AppProperties()
    private val history = listOf(ConversationMessage(Role.USER, "질문"))
    private val emptyPrometheus = object : PrometheusClient {
        override fun query(promql: String, unit: String): List<MetricSample> = emptyList()
        override fun queryLabeled(promql: String): List<LabeledSample> = emptyList()
        override fun queryRange(promql: String, start: Instant, end: Instant, step: Duration): List<RangeSeries> = emptyList()
    }

    private fun service(extraction: ResourceExtraction, repo: InventoryRepository?): DefaultResourceService {
        val extractor = object : MetricQueryExtractor {
            override fun extract(history: List<ConversationMessage>): ResourceExtraction = extraction
        }
        return DefaultResourceService(extractor, MetricCatalog(props), emptyPrometheus, providerOf(repo), props)
    }

    @Test
    fun `InventoryResolved — 리포지토리 조회 후 템플릿 답변`() {
        val query = InventoryQuery(InventoryKind.INSTANCE, InventoryQuery.Mode.COUNT, InventoryFilters(status = "ACTIVE", statusOp = InventoryFilters.Op.NEQ))
        val repo = object : InventoryRepository {
            override fun find(query: InventoryQuery, providerUuid: String, limit: Int): InventoryResult =
                InventoryResult(InventoryKind.INSTANCE, emptyList(), 7, query.filters)
        }

        val out = service(ResourceExtraction.InventoryResolved(query), repo).handle(history)

        assertTrue(out.answer.contains("인스턴스 개수는 7건"), out.answer)
        assertFalse(out.needsClarification)
    }

    @Test
    fun `INVENTORY 비활성(리포지토리 없음) — 안내 + needsClarification`() {
        val query = InventoryQuery(InventoryKind.VOLUME, InventoryQuery.Mode.LIST)
        val out = service(ResourceExtraction.InventoryResolved(query), repo = null).handle(history)

        assertTrue(out.answer.contains("비활성화"), out.answer)
        assertTrue(out.needsClarification)
    }

    @Test
    fun `NeedsClarification — 되물음 그대로`() {
        val out = service(ResourceExtraction.NeedsClarification("무엇을 조회할까요?"), repo = null).handle(history)

        assertEquals("무엇을 조회할까요?", out.answer)
        assertTrue(out.needsClarification)
    }

    private fun <T : Any> providerOf(value: T?): ObjectProvider<T> = object : ObjectProvider<T> {
        override fun getObject(vararg args: Any?): T = value ?: error("no bean")
        override fun getObject(): T = value ?: error("no bean")
        override fun getIfAvailable(): T? = value
        override fun getIfUnique(): T? = value
    }
}
