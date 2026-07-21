package com.okestro.ragbot.routing

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.routing.application.LlmQuestionRouter
import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.chat.domain.ConversationMessage.Role
import com.okestro.ragbot.resource.application.FollowupBuilder
import com.okestro.ragbot.resource.domain.InventoryFilters
import com.okestro.ragbot.resource.domain.InventoryKind
import com.okestro.ragbot.resource.domain.InventoryResult
import com.okestro.ragbot.resource.domain.MetricPattern
import com.okestro.ragbot.resource.domain.MetricSample
import com.okestro.ragbot.resource.domain.ResourceQuery
import com.okestro.ragbot.routing.domain.Route
import com.okestro.ragbot.routing.infrastructure.OpenAiRouterLlmClient
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.api.OpenAiApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 실제 OpenAI 분류 정확도 검증. OPENAI_API_KEY 있을 때만 실행(기본 off → ./gradlew test 그린 유지).
 * stub 테스트가 못 보는 "분류 품질"을 실측한다. few-shot 튜닝 시 회귀 가드로 사용.
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class RoutingAccuracyTest {

    private fun realRouter(): LlmQuestionRouter {
        val api = OpenAiApi.builder().apiKey(System.getenv("OPENAI_API_KEY")).build()
        val chatModel = OpenAiChatModel.builder().openAiApi(api).build()
        return LlmQuestionRouter(OpenAiRouterLlmClient(chatModel), AppProperties(), jacksonObjectMapper())
    }

    private fun user(text: String) = listOf(ConversationMessage(Role.USER, text))

    @Test
    fun `명확한 DOC 질문`() {
        assertEquals(Route.DOC, realRouter().route(user("RAG에서 임베딩 모델은 어떻게 설정하나요?")).route)
    }

    @Test
    fun `명확한 RESOURCE 질문`() {
        assertEquals(Route.RESOURCE, realRouter().route(user("지금 prod 클러스터 CPU 사용률 보여줘")).route)
    }

    @Test
    fun `맥락 의존 후속 질문은 RESOURCE`() {
        val history = listOf(
            ConversationMessage(Role.ASSISTANT, "인스턴스 목록입니다 — 1) web-01 2) web-02 3) db-01"),
            ConversationMessage(Role.USER, "1번 인스턴스 상세 알려줘"),
        )
        assertEquals(Route.RESOURCE, realRouter().route(history).route)
    }

    // ── 1b 위젯 질문 유형 (status_donut·threshold_banner) ───────────────────────
    //
    // 위젯이 뜨려면 라우터가 먼저 RESOURCE로 보내줘야 한다. DOC/CLARIFY로 새면 위젯 배선까지
    // 도달을 못 해 영영 안 뜬다 — 키워드 if는 라우터 **뒤에** 있기 때문이다.
    // 위젯 배선 테스트(DefaultResourceService*Test)는 라우터를 안 거치므로 이 구간은 여기서만 잡힌다.

    @Test
    fun `상태 분포 질문은 RESOURCE`() {
        assertEquals(Route.RESOURCE, realRouter().route(user("인스턴스 상태 분포 알려줘")).route)
    }

    @Test
    fun `임계 초과 질문은 RESOURCE`() {
        assertEquals(Route.RESOURCE, realRouter().route(user("임계 넘은 노드 있어?")).route)
    }

    @Test
    fun `상태 분포 구어체 변형도 RESOURCE`() {
        assertEquals(Route.RESOURCE, realRouter().route(user("지금 죽어있는 인스턴스 몇 대야?")).route)
    }

    // ── INVENTORY 질문 (cb_common 트랙) ────────────────────────────────────────
    //
    // 실측(2026-07-16, Postman): "볼륨 몇 개야?" → CLARIFY confidence=0.8
    //   reason="볼륨의 종류나 맥락이 불명확하여 추가 정보가 필요함"
    // 원인은 RESOURCE 정의가 "(Prometheus)"로 한정돼 있어서다. INVENTORY는 cb_common(MySQL)
    // 트랙이라 정의에 안 걸리고, few-shot에도 인벤토리 예시가 없었다.
    // 라우터가 CLARIFY로 보내면 추출기까지 도달을 못 해 inventory_count가 영영 안 뜬다.

    @Test
    fun `볼륨 개수 질문은 RESOURCE`() {
        assertEquals(Route.RESOURCE, realRouter().route(user("볼륨 몇 개야?")).route)
    }

    @Test
    fun `상태 필터 목록 질문은 RESOURCE`() {
        assertEquals(Route.RESOURCE, realRouter().route(user("ACTIVE가 아닌 인스턴스 목록 보여줘")).route)
    }

    @Test
    fun `스냅샷 개수 질문은 RESOURCE`() {
        assertEquals(Route.RESOURCE, realRouter().route(user("prod 프로젝트 볼륨 스냅샷 몇 개 있어?")).route)
    }

    // ── CLARIFY 회귀 가드 ──────────────────────────────────────────────────────
    //
    // RESOURCE 정의를 "리소스 개수·목록"까지 넓히고 "짧아도 RESOURCE" 규칙을 넣었다.
    // 넓히다 되물음이 죽으면 더 나쁘다 — 모호한 질문에 아무 조회나 갈긴다(불변식 4: 토큰 낭비).
    // 이 스위트엔 CLARIFY 케이스가 없었다.

    @Test
    fun `지시 대상 불명확하면 여전히 CLARIFY`() {
        assertEquals(Route.CLARIFY, realRouter().route(user("그거 어떻게 해?")).route)
    }

    @Test
    fun `인프라와 무관한 질문은 RESOURCE가 아니다`() {
        // "몇 개"가 들어갔다고 무조건 RESOURCE로 끌려오면 안 된다.
        assertNotEquals(Route.RESOURCE, realRouter().route(user("오늘 서울 날씨 어때?")).route)
    }

    @Test
    fun `문서 질문이 리소스 단어를 품어도 DOC`() {
        // "볼륨"이 들어갔다고 조회로 새면 안 된다 — 사용법을 묻고 있다.
        assertEquals(Route.DOC, realRouter().route(user("볼륨 스냅샷은 어떻게 만드나요?")).route)
    }

    // ── 연관질문 칩 왕복 가드 ───────────────────────────────────────────────────
    //
    // 칩은 우리가 만들어 던지고, 클릭하면 그 문자열이 그대로 다음 질문으로 되돌아온다.
    // 그런데 ChatRequest엔 history가 없어서(REST는 빈 목록) 라우터는 칩 한 줄만 보고 판단한다.
    // 즉 **우리가 만든 문장이 우리 라우터를 통과해야** 한다 — 아니면 칩은 100% 죽는다.
    //
    // 실측(2026-07-16, 화면): "admin만 보기" 클릭 → CLARIFY. 라우터는 정상이었고 문구가 틀렸다.
    // FollowupBuilderTest는 순수 문자열만 봐서 이걸 못 잡는다. 라우터를 건너야만 잡히는 결함이다.
    // 하드코딩이 아니라 FollowupBuilder가 **실제로 뱉는** 문구를 그대로 태운다(문구 바뀌면 같이 따라옴).

    private fun metricChips(metric: MetricPattern): List<String> =
        FollowupBuilder.forMetric(
            ResourceQuery(metric = metric),
            listOf(MetricSample(instanceName = "web-prod-07", value = 91.2, unit = "%", projectName = "admin")),
        )

    @Test
    fun `metric 칩은 전부 RESOURCE로 라우팅된다`() {
        val router = realRouter()
        metricChips(MetricPattern.INSTANCE_CPU).forEach { chip ->
            assertEquals(Route.RESOURCE, router.route(user(chip)).route, "칩이 라우터를 못 넘음: \"$chip\"")
        }
    }

    @Test
    fun `inventory 칩은 전부 RESOURCE로 라우팅된다`() {
        val router = realRouter()
        InventoryKind.entries.forEach { kind ->
            val result = InventoryResult(kind = kind, rows = emptyList(), total = 3, appliedFilters = InventoryFilters())
            FollowupBuilder.forInventory(result).forEach { chip ->
                assertEquals(Route.RESOURCE, router.route(user(chip)).route, "칩이 라우터를 못 넘음: \"$chip\"")
            }
        }
    }
}
