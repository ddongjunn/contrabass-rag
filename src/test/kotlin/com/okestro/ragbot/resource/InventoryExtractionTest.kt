package com.okestro.ragbot.resource

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.resource.application.LlmMetricQueryExtractor
import com.okestro.ragbot.resource.domain.InventoryFilters
import com.okestro.ragbot.resource.domain.InventoryKind
import com.okestro.ragbot.resource.domain.InventoryQuery
import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.chat.domain.ConversationMessage.Role
import com.okestro.ragbot.resource.domain.ResourceExtraction
import com.okestro.ragbot.routing.StubLlmClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DB-1: target=INVENTORY 분기·필터 매핑·폴백 검증(스텁 — 키·DB 불필요). 기존 METRIC 케이스는 LlmMetricQueryExtractorTest. */
class InventoryExtractionTest {

    private val props = AppProperties(resource = AppProperties.Resource(minConfidence = 0.5))

    private fun extractorWith(response: String) =
        LlmMetricQueryExtractor(StubLlmClient(response), props, jacksonObjectMapper())

    private fun ask(text: String) = listOf(ConversationMessage(Role.USER, text))

    @Test
    fun `ACTIVE 아닌 인스턴스 목록 — INSTANCE LIST NEQ ACTIVE`() {
        val result = extractorWith(
            """{"target":"INVENTORY","clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","kind":"INSTANCE","mode":"LIST","status":"ACTIVE","statusOp":"NEQ","hypervisorHostName":null,"instanceCreateEnable":null,"project":null,"confidence":0.93}"""
        ).extract(ask("ACTIVE 아닌 인스턴스 목록 보여줘"))

        val inv = assertIs<ResourceExtraction.InventoryResolved>(result)
        assertEquals(InventoryKind.INSTANCE, inv.query.kind)
        assertEquals(InventoryQuery.Mode.LIST, inv.query.mode)
        assertEquals("ACTIVE", inv.query.filters.status)
        assertEquals(InventoryFilters.Op.NEQ, inv.query.filters.statusOp)
    }

    @Test
    fun `프로젝트 볼륨 개수 — VOLUME COUNT projectId`() {
        val result = extractorWith(
            """{"target":"INVENTORY","clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","kind":"VOLUME","mode":"COUNT","status":null,"statusOp":"EQ","hypervisorHostName":null,"instanceCreateEnable":null,"project":"prod","confidence":0.9}"""
        ).extract(ask("prod 프로젝트 볼륨 개수"))

        val inv = assertIs<ResourceExtraction.InventoryResolved>(result)
        assertEquals(InventoryKind.VOLUME, inv.query.kind)
        assertEquals(InventoryQuery.Mode.COUNT, inv.query.mode)
        assertEquals("prod", inv.query.filters.projectId)
    }

    @Test
    fun `하이퍼바이저 호스트 필터 — 기본 mode LIST, statusOp EQ`() {
        val result = extractorWith(
            """{"target":"INVENTORY","clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","kind":"INSTANCE","mode":"LIST","status":null,"statusOp":"EQ","hypervisorHostName":"host-01","instanceCreateEnable":null,"project":null,"confidence":0.91}"""
        ).extract(ask("host-01에 올라간 인스턴스"))

        val inv = assertIs<ResourceExtraction.InventoryResolved>(result)
        assertEquals("host-01", inv.query.filters.hypervisorHostName)
        assertEquals(InventoryQuery.Mode.LIST, inv.query.mode)
        assertNull(inv.query.filters.status)
    }

    @Test
    fun `볼륨 스냅샷 — instanceCreateEnable true 반영`() {
        val result = extractorWith(
            """{"target":"INVENTORY","clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","kind":"VOLUME_SNAPSHOT","mode":"LIST","status":"available","statusOp":"EQ","hypervisorHostName":null,"instanceCreateEnable":true,"project":null,"confidence":0.9}"""
        ).extract(ask("available 볼륨 스냅샷 중 인스턴스 생성 가능한 것"))

        val inv = assertIs<ResourceExtraction.InventoryResolved>(result)
        assertEquals(InventoryKind.VOLUME_SNAPSHOT, inv.query.kind)
        assertEquals("available", inv.query.filters.status)
        assertEquals(true, inv.query.filters.instanceCreateEnable)
    }

    @Test
    fun `알 수 없는 kind면 NeedsClarification`() {
        val result = extractorWith(
            """{"target":"INVENTORY","clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","kind":"NETWORK","mode":"LIST","status":null,"statusOp":"EQ","hypervisorHostName":null,"instanceCreateEnable":null,"project":null,"confidence":0.9}"""
        ).extract(ask("네트워크 목록"))

        assertIs<ResourceExtraction.NeedsClarification>(result)
    }

    @Test
    fun `INVENTORY 저신뢰면 NeedsClarification`() {
        val result = extractorWith(
            """{"target":"INVENTORY","clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","kind":"INSTANCE","mode":"LIST","status":null,"statusOp":"EQ","hypervisorHostName":null,"instanceCreateEnable":null,"project":null,"confidence":0.2}"""
        ).extract(ask("뭔가 보여줘"))

        assertIs<ResourceExtraction.NeedsClarification>(result)
    }

    @Test
    fun `target 누락 시 METRIC으로 폴백 — 기존 동작 보존`() {
        val result = extractorWith(
            """{"clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","project":null,"confidence":0.9}"""
        ).extract(ask("cpu 높은 VM"))

        assertIs<ResourceExtraction.Resolved>(result)
    }

    @Test
    fun `스키마에 target과 kind enum이 포함된다`() {
        val stub = StubLlmClient(
            """{"target":"INVENTORY","clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","kind":"INSTANCE","mode":"LIST","status":null,"statusOp":"EQ","hypervisorHostName":null,"instanceCreateEnable":null,"project":null,"confidence":0.9}"""
        )
        LlmMetricQueryExtractor(stub, props, jacksonObjectMapper()).extract(ask("인스턴스 목록"))

        val schema = stub.lastRequest!!.jsonSchema
        assertTrue(schema.contains("\"target\""), "스키마에 target 포함")
        assertTrue(schema.contains("INVENTORY"), "스키마에 INVENTORY enum 포함")
        assertTrue(schema.contains("VOLUME_SNAPSHOT"), "스키마에 InventoryKind enum 포함")
    }
}
