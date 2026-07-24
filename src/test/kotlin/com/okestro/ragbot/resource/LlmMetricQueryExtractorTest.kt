package com.okestro.ragbot.resource

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.resource.application.LlmMetricQueryExtractor
import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.ResourceExtraction
import com.okestro.ragbot.resource.domain.ResourceQuery
import com.okestro.ragbot.routing.StubLlmClient
import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.chat.domain.ConversationMessage.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmMetricQueryExtractorTest {

    private val props = AppProperties(resource = AppProperties.Resource(minConfidence = 0.5, defaultWindow = "5m"))

    private fun extractorWith(response: String) =
        LlmMetricQueryExtractor(StubLlmClient(response), props, jacksonObjectMapper())

    private fun ask(text: String) = listOf(ConversationMessage(Role.USER, text))

    @Test
    fun `정상 JSON이면 ResourceQuery로 매핑한다`() {
        val result = extractorWith(
            """{"clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","project":null,"confidence":0.95}"""
        ).extract(ask("cpu 높은 VM 알려줘"))

        val resolved = assertIs<ResourceExtraction.Resolved>(result)
        assertEquals(MetricPattern.INSTANCE_CPU, resolved.query.metric)
        assertEquals(ResourceQuery.Sort.DESC, resolved.query.sort)
        assertEquals(5, resolved.query.topN)
        assertEquals("5m", resolved.query.window)
        assertNull(resolved.query.project)
    }

    @Test
    fun `project와 window가 지정되면 ResourceQuery에 반영된다`() {
        val result = extractorWith(
            """{"clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_MEMORY","sort":"ASC","topN":3,"window":"1h","project":"prod","confidence":0.92}"""
        ).extract(ask("prod 메모리 낮은 순 3개"))

        val resolved = assertIs<ResourceExtraction.Resolved>(result)
        assertEquals(MetricPattern.INSTANCE_MEMORY, resolved.query.metric)
        assertEquals(ResourceQuery.Sort.ASC, resolved.query.sort)
        assertEquals(3, resolved.query.topN)
        assertEquals("1h", resolved.query.window)
        assertEquals("prod", resolved.query.project)
    }

    @Test
    fun `clarificationNeeded=true이면 NeedsClarification을 반환한다`() {
        val result = extractorWith(
            """{"clarificationNeeded":true,"clarificationMessage":"어떤 지표를 조회할까요?","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","project":null,"confidence":0.3}"""
        ).extract(ask("서버 상태 어때?"))

        val nc = assertIs<ResourceExtraction.NeedsClarification>(result)
        assertEquals("어떤 지표를 조회할까요?", nc.message)
    }

    @Test
    fun `confidence가 임계값 미만이면 NeedsClarification을 반환한다`() {
        val result = extractorWith(
            """{"clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","project":null,"confidence":0.2}"""
        ).extract(ask("그거 보여줘"))

        assertIs<ResourceExtraction.NeedsClarification>(result)
    }

    @Test
    fun `깨진 JSON이면 NeedsClarification으로 폴백한다`() {
        val result = extractorWith("이건 JSON이 아니다").extract(ask("아무거나"))
        assertIs<ResourceExtraction.NeedsClarification>(result)
    }

    @Test
    fun `lastRequest에 시스템 프롬프트와 JSON 스키마가 담긴다`() {
        val stub = StubLlmClient(
            """{"clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","project":null,"confidence":0.9}"""
        )
        LlmMetricQueryExtractor(stub, props, jacksonObjectMapper()).extract(ask("cpu 사용량"))

        val req = stub.lastRequest!!
        assertTrue(req.system.contains("INSTANCE_CPU"), "시스템 프롬프트에 지표 목록 포함")
        assertTrue(req.jsonSchema.contains("clarificationNeeded"), "스키마에 clarificationNeeded 필드 포함")
        assertTrue(req.jsonSchema.contains("INSTANCE_CPU"), "스키마에 메트릭 enum 포함")
        assertEquals("cpu 사용량", req.messages.last().content)
    }

    @Test
    fun `topN이 20 초과이면 20으로 clamp된다`() {
        val result = extractorWith(
            """{"clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":50,"window":"5m","project":null,"confidence":0.9}"""
        ).extract(ask("cpu 높은 거 50개"))

        val resolved = assertIs<ResourceExtraction.Resolved>(result)
        assertEquals(20, resolved.query.topN)
    }

    @Test
    fun `topN이 1 미만이면 1로 clamp된다`() {
        val result = extractorWith(
            """{"clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":0,"window":"5m","project":null,"confidence":0.9}"""
        ).extract(ask("cpu"))

        val resolved = assertIs<ResourceExtraction.Resolved>(result)
        assertEquals(1, resolved.query.topN)
    }

    @Test
    fun `window가 빈 문자열이면 defaultWindow(5m)로 대체된다`() {
        val result = extractorWith(
            """{"clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_MEMORY","sort":"DESC","topN":5,"window":"","project":null,"confidence":0.85}"""
        ).extract(ask("메모리 많이 쓰는 VM"))

        val resolved = assertIs<ResourceExtraction.Resolved>(result)
        assertEquals("5m", resolved.query.window)
    }

    @Test
    fun `project가 빈 문자열이면 null로 처리된다`() {
        val result = extractorWith(
            """{"clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","project":"","confidence":0.9}"""
        ).extract(ask("cpu 높은 VM"))

        val resolved = assertIs<ResourceExtraction.Resolved>(result)
        assertNull(resolved.query.project)
    }

    @Test
    fun `INSTANCE_DISK_READ 지표를 올바르게 매핑한다`() {
        val result = extractorWith(
            """{"clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_DISK_READ","sort":"DESC","topN":5,"window":"5m","project":null,"confidence":0.91}"""
        ).extract(ask("디스크 읽기 많은 VM"))

        val resolved = assertIs<ResourceExtraction.Resolved>(result)
        assertEquals(MetricPattern.INSTANCE_DISK_READ, resolved.query.metric)
    }

    @Test
    fun `INSTANCE_NETWORK_TX 지표를 ASC 정렬로 매핑한다`() {
        val result = extractorWith(
            """{"clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_NETWORK_TX","sort":"ASC","topN":5,"window":"5m","project":null,"confidence":0.88}"""
        ).extract(ask("네트워크 송신량 낮은 순"))

        val resolved = assertIs<ResourceExtraction.Resolved>(result)
        assertEquals(MetricPattern.INSTANCE_NETWORK_TX, resolved.query.metric)
        assertEquals(ResourceQuery.Sort.ASC, resolved.query.sort)
    }

    @Test
    fun `알 수 없는 metric 값이면 NeedsClarification을 반환한다`() {
        val result = extractorWith(
            """{"clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_UNKNOWN_XYZ","sort":"DESC","topN":5,"window":"5m","project":null,"confidence":0.9}"""
        ).extract(ask("알 수 없는 지표"))

        assertIs<ResourceExtraction.NeedsClarification>(result)
    }

    // ── 1b 위젯 target (STATUS / THRESHOLD) ─────────────────────────────────────

    @Test
    fun `target STATUS면 StatusResolved`() {
        val result = extractorWith(
            """{"target":"STATUS","clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","project":null,"confidence":0.93}"""
        ).extract(ask("인스턴스 상태 분포 알려줘"))

        assertIs<ResourceExtraction.StatusResolved>(result)
    }

    @Test
    fun `target THRESHOLD면 ThresholdResolved`() {
        val result = extractorWith(
            """{"target":"THRESHOLD","clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","project":null,"confidence":0.91}"""
        ).extract(ask("임계 넘은 노드 있어?"))

        assertIs<ResourceExtraction.ThresholdResolved>(result)
    }

    @Test
    fun `target QUOTA면 QuotaResolved - project까지 실린다`() {
        val result = extractorWith(
            """{"target":"QUOTA","clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","project":"AUTOTEST","confidence":0.94}"""
        ).extract(ask("AUTOTEST 쿼터 얼마나 썼어?"))

        assertEquals("AUTOTEST", assertIs<ResourceExtraction.QuotaResolved>(result).project)
    }

    @Test
    fun `QUOTA인데 프로젝트가 없으면 project null인 QuotaResolved - 되물을지는 서비스가 결정한다`() {
        val result = extractorWith(
            """{"target":"QUOTA","clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","project":null,"confidence":0.9}"""
        ).extract(ask("쿼터 얼마나 썼어?"))

        assertNull(assertIs<ResourceExtraction.QuotaResolved>(result).project)
    }

    @Test
    fun `target TREND면 TrendResolved - metric·range·project가 실린다`() {
        val result = extractorWith(
            """{"target":"TREND","clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","range":"3h","project":"prod","confidence":0.9}"""
        ).extract(ask("prod 프로젝트 CPU 사용률 추이 보여줘"))

        val resolved = assertIs<ResourceExtraction.TrendResolved>(result)
        assertEquals(MetricPattern.INSTANCE_CPU, resolved.query.metric)
        assertEquals("3h", resolved.query.range)
        assertEquals("prod", resolved.query.project)
    }

    @Test
    fun `TREND인데 range가 비면 기본 range로 대체된다`() {
        val result = extractorWith(
            """{"target":"TREND","clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_MEMORY","sort":"DESC","topN":5,"window":"5m","range":"","project":null,"confidence":0.88}"""
        ).extract(ask("메모리 추이 어때?"))

        val resolved = assertIs<ResourceExtraction.TrendResolved>(result)
        assertEquals("1h", resolved.query.range)
    }

    @Test
    fun `target PROJECT_USAGE면 ProjectUsageResolved`() {
        val result = extractorWith(
            """{"target":"PROJECT_USAGE","clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","project":null,"confidence":0.92}"""
        ).extract(ask("프로젝트별 사용률 보여줘"))

        assertIs<ResourceExtraction.ProjectUsageResolved>(result)
    }

    @Test
    fun `STATUS여도 confidence 낮으면 되물음 - 기존 정책 그대로`() {
        val result = extractorWith(
            """{"target":"STATUS","clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","project":null,"confidence":0.3}"""
        ).extract(ask("상태?"))

        assertIs<ResourceExtraction.NeedsClarification>(result)
    }
}
