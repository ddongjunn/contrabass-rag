package com.okestro.ragbot.resource

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.resource.application.LlmMetricQueryExtractor
import com.okestro.ragbot.resource.application.ResourcePrompts
import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.ResourceExtraction
import com.okestro.ragbot.routing.StubLlmClient
import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.chat.domain.ConversationMessage.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 프롬프트 ↔ 스키마 ↔ 디스패치 배선 가드. **실 API 호출 0회**(StubLlmClient).
 *
 * 왜 필요한가: 분류 품질 테스트(MetricExtractionAccuracyTest)는 OPENAI_API_KEY가 있을 때만 돌아
 * CI에선 전부 스킵된다. 그래서 아래 고장들이 전부 "테스트 초록"으로 통과했다(리뷰에서 변이로 실증):
 *   - `"QUOTA" -> toQuota`를 `"QOUTA"`로 오타 → 쿼터 질문이 CPU 차트로 떨어짐, 실패 0건
 *   - 스키마 enum에서 QUOTA/PROJECT_USAGE 삭제 → OpenAI가 그 값을 물리적으로 못 냄, 실패 0건
 *
 * 이 테스트는 프롬프트의 few-shot 예시(리터럴 JSON)를 실제 추출기에 그대로 태워, 예시가 선언한
 * target에 실제로 도달하는지 본다. 프롬프트·스키마·디스패치가 어긋나는 순간 깨진다.
 */
class ResourcePromptsWiringTest {

    private val props = AppProperties(resource = AppProperties.Resource(minConfidence = 0.5))
    private val metricKeys = MetricPattern.entries.map { it.name }

    /** 디스패치가 실제로 처리하는 target들(= 사용자가 도달 가능한 트랙). */
    private val dispatchedTargets = listOf("METRIC", "INVENTORY", "STATUS", "THRESHOLD", "TREND", "IP_USAGE", "CAPACITY", "AGENT")

    /** SYSTEM 프롬프트의 `=> {...}` few-shot 응답 JSON을 그대로 뽑는다. */
    private fun fewShotResponses(): List<String> =
        Regex("""=>\s*(\{.*})""").findAll(ResourcePrompts.SYSTEM).map { it.groupValues[1] }.toList()

    private fun extractWith(json: String) =
        LlmMetricQueryExtractor(StubLlmClient(json), props, jacksonObjectMapper())
            .extract(listOf(ConversationMessage(Role.USER, "q")))

    private fun targetOf(result: ResourceExtraction): String = when (result) {
        is ResourceExtraction.Resolved -> "METRIC"
        is ResourceExtraction.InventoryResolved -> "INVENTORY"
        is ResourceExtraction.StatusResolved -> "STATUS"
        is ResourceExtraction.ThresholdResolved -> "THRESHOLD"
        is ResourceExtraction.IpUsageResolved -> "IP_USAGE"
        is ResourceExtraction.CapacityResolved -> "CAPACITY"
        is ResourceExtraction.AgentResolved -> "AGENT"
        is ResourceExtraction.TrendResolved -> "TREND"
        is ResourceExtraction.NeedsClarification -> "CLARIFY"
    }

    @Test
    fun `few-shot 예시가 선언한 target에 실제로 도달한다`() {
        val examples = fewShotResponses()
        assertTrue(examples.size >= 6, "few-shot 예시를 못 뽑았다(정규식 확인): ${examples.size}")

        val mapper = jacksonObjectMapper()
        examples.forEach { json ->
            val node = mapper.readTree(json)
            val declared = node["target"].asText()
            val clarify = node["clarificationNeeded"].asBoolean()
            val confident = node["confidence"].asDouble() >= props.resource.minConfidence
            val expected = if (clarify || !confident) "CLARIFY" else declared

            assertEquals(expected, targetOf(extractWith(json)), "예시가 target=$declared 라고 선언했는데 다른 곳으로 갔다: $json")
        }
    }

    @Test
    fun `디스패치가 아는 target은 전부 few-shot 예시를 갖는다`() {
        // 예시 없는 target은 LLM이 낼 확률이 급격히 떨어진다 — 실질적으로 죽은 트랙이 된다.
        val declared = fewShotResponses().map { jacksonObjectMapper().readTree(it)["target"].asText() }.toSet()
        val missing = dispatchedTargets - declared
        assertTrue(missing.isEmpty(), "이 target들은 디스패치엔 있는데 프롬프트 예시가 없다: $missing")
    }

    @Test
    fun `스키마 enum이 디스패치 target을 전부 포함한다`() {
        // enum에 없으면 OpenAI structured output이 그 값을 물리적으로 못 낸다 → 위젯이 영원히 안 뜬다.
        val schema = ResourcePrompts.schema(metricKeys)
        val enumValues = Regex(""""target":\s*\{[^}]*"enum":\s*\[([^\]]*)]""").find(schema)
            ?.groupValues?.get(1)
            ?.split(",")?.map { it.trim().trim('"') }
            ?: error("스키마에서 target enum을 못 찾았다")

        val missing = dispatchedTargets - enumValues.toSet()
        assertTrue(missing.isEmpty(), "디스패치는 처리하는데 스키마 enum에 없다(LLM이 못 냄): $missing")
    }
}
