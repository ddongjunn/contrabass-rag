package com.okestro.ragbot.resource

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.resource.application.LlmMetricQueryExtractor
import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.ResourceExtraction
import com.okestro.ragbot.resource.domain.ResourceQuery
import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.chat.domain.ConversationMessage.Role
import com.okestro.ragbot.routing.infrastructure.OpenAiRouterLlmClient
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.api.OpenAiApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * 실제 OpenAI 추출 품질 검증. OPENAI_API_KEY 있을 때만 실행(기본 off → ./gradlew test 그린 유지).
 * 프롬프트 튜닝 시 회귀 가드로 사용. 로그에서 extraction-raw / extraction-resolved 확인 가능.
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class MetricExtractionAccuracyTest {

    private fun realExtractor(): LlmMetricQueryExtractor {
        val api = OpenAiApi.builder().apiKey(System.getenv("OPENAI_API_KEY")).build()
        val chatModel = OpenAiChatModel.builder().openAiApi(api).build()
        return LlmMetricQueryExtractor(OpenAiRouterLlmClient(chatModel), AppProperties(), jacksonObjectMapper())
    }

    private fun ask(text: String) = listOf(ConversationMessage(Role.USER, text))

    @Test
    fun `CPU 기본 질문 — topN·window 언급 없어도 즉시 추출`() {
        val result = realExtractor().extract(ask("cpu 사용량 가장 높은 VM 알려줘"))

        val resolved = assertIs<ResourceExtraction.Resolved>(result)
        assertEquals(MetricPattern.INSTANCE_CPU, resolved.query.metric)
        assertEquals(ResourceQuery.Sort.DESC, resolved.query.sort)
    }

    @Test
    fun `메모리 + topN 지정`() {
        val result = realExtractor().extract(ask("메모리 많이 쓰는 인스턴스 3개 보여줘"))

        val resolved = assertIs<ResourceExtraction.Resolved>(result)
        assertEquals(MetricPattern.INSTANCE_MEMORY, resolved.query.metric)
        assertEquals(3, resolved.query.topN)
    }

    @Test
    fun `디스크 읽기 지표 추출`() {
        val result = realExtractor().extract(ask("디스크 읽기 가장 많은 VM 알려줘"))

        val resolved = assertIs<ResourceExtraction.Resolved>(result)
        assertEquals(MetricPattern.INSTANCE_DISK_READ, resolved.query.metric)
    }

    @Test
    fun `네트워크 송신량 낮은 순`() {
        val result = realExtractor().extract(ask("네트워크 송신량 낮은 순으로 보여줘"))

        val resolved = assertIs<ResourceExtraction.Resolved>(result)
        assertEquals(MetricPattern.INSTANCE_NETWORK_TX, resolved.query.metric)
        assertEquals(ResourceQuery.Sort.ASC, resolved.query.sort)
    }

    @Test
    fun `프로젝트 필터 추출`() {
        val result = realExtractor().extract(ask("prod 프로젝트 CPU 높은 거 알려줘"))

        val resolved = assertIs<ResourceExtraction.Resolved>(result)
        assertEquals(MetricPattern.INSTANCE_CPU, resolved.query.metric)
        assertNotNull(resolved.query.project)
    }

    @Test
    fun `시간 윈도우 지정`() {
        val result = realExtractor().extract(ask("CPU 지난 1시간 동안 가장 많이 쓴 VM"))

        val resolved = assertIs<ResourceExtraction.Resolved>(result)
        assertEquals(MetricPattern.INSTANCE_CPU, resolved.query.metric)
        assertEquals("1h", resolved.query.window)
    }

    @Test
    fun `모호한 질문 — 되물음 반환`() {
        val result = realExtractor().extract(ask("서버 상태 어때?"))

        assertIs<ResourceExtraction.NeedsClarification>(result)
    }

    @Test
    fun `네트워크 RX TX 구분 불명확 — 되물음 반환`() {
        val result = realExtractor().extract(ask("지금 네트워크 상태 보여줘"))

        assertIs<ResourceExtraction.NeedsClarification>(result)
    }

    // ── 1b target 분류 (STATUS / THRESHOLD) ────────────────────────────────────
    //
    // 위젯이 뜨려면 추출기가 target을 맞춰야 한다. 여기가 B(정식 의도 분류)의 관문 —
    // 서비스 테스트는 target을 고정으로 주므로 분류 품질은 이 테스트에서만 잡힌다.

    @Test
    fun `상태 분포 질문은 STATUS`() {
        assertIs<ResourceExtraction.StatusResolved>(realExtractor().extract(ask("인스턴스 상태 분포 알려줘")))
    }

    @Test
    fun `구어체 변형도 STATUS - 키워드 매칭이 못 잡던 것`() {
        // 키워드 if 시절엔 "상태"가 없어 못 잡고 CPU TopN으로 샜다. B로 가는 이유가 이 케이스다.
        assertIs<ResourceExtraction.StatusResolved>(realExtractor().extract(ask("지금 죽어있는 인스턴스 몇 대야?")))
    }

    @Test
    fun `임계 초과 질문은 THRESHOLD`() {
        assertIs<ResourceExtraction.ThresholdResolved>(realExtractor().extract(ask("임계 넘은 노드 있어?")))
    }

    @Test
    fun `위험한 인스턴스도 THRESHOLD`() {
        assertIs<ResourceExtraction.ThresholdResolved>(realExtractor().extract(ask("CPU 높아서 위험한 인스턴스 있어?")))
    }

    @Test
    fun `특정 상태 개수는 STATUS가 아니라 INVENTORY - 경계 구분`() {
        // "상태별 분포"(STATUS)와 "특정 상태 몇 개"(INVENTORY)가 안 섞이는지. 프롬프트에 규칙으로 명시함.
        assertIs<ResourceExtraction.InventoryResolved>(realExtractor().extract(ask("ACTIVE 인스턴스 몇 개야?")))
    }

    @Test
    fun `CPU TopN은 여전히 METRIC - STATUS 추가로 안 샜는지`() {
        assertIs<ResourceExtraction.Resolved>(realExtractor().extract(ask("cpu 사용량 가장 높은 VM 알려줘")))
    }
}
