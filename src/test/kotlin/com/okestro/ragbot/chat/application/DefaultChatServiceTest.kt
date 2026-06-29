package com.okestro.ragbot.chat.application

import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.embedding.application.EmbeddingService
import com.okestro.ragbot.generation.application.AnswerGenerationService
import com.okestro.ragbot.guard.application.InputGuard
import com.okestro.ragbot.guard.application.RateLimitGuard
import com.okestro.ragbot.guard.domain.GuardDecision
import com.okestro.ragbot.resource.application.ResourceService
import com.okestro.ragbot.retrieval.application.DocumentSearchService
import com.okestro.ragbot.retrieval.domain.RetrievalPolicy
import com.okestro.ragbot.retrieval.domain.RetrievedChunk
import com.okestro.ragbot.routing.application.QuestionRouter
import com.okestro.ragbot.routing.domain.Route
import com.okestro.ragbot.routing.domain.RouteDecision
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DefaultChatServiceTest {

    // ── 페이크 ──────────────────────────────────────────────────────────────

    private class FakeRateLimit(private val allow: Boolean = true) : RateLimitGuard {
        override fun tryAcquire(userId: String): Boolean = allow
    }

    private class FakeGuard(private val decision: GuardDecision) : InputGuard {
        override fun inspect(question: String): GuardDecision = decision
    }

    private class FakeRouter(private val route: Route = Route.DOC) : QuestionRouter {
        var calls = 0
        override fun route(history: List<ConversationMessage>): RouteDecision {
            calls++
            return RouteDecision(route, 0.9, "테스트")
        }
    }

    private class FakeEmbedding(private val vector: FloatArray) : EmbeddingService {
        var calls = 0
        override fun embed(text: String): FloatArray { calls++; return vector }
    }

    private class FakeSearch(private val chunks: List<RetrievedChunk>) : DocumentSearchService {
        var receivedVector: FloatArray? = null
        var receivedTopK: Int? = null
        override fun search(queryVector: FloatArray, topK: Int): List<RetrievedChunk> {
            receivedVector = queryVector; receivedTopK = topK; return chunks
        }
    }

    private class FakeGeneration(private val answer: String) : AnswerGenerationService {
        var calls = 0
        var receivedQuestion: String? = null
        override fun generate(question: String, chunks: List<RetrievedChunk>): String {
            calls++; receivedQuestion = question; return answer
        }
    }

    private class FakeResourceService(private val result: ResourceService.Result) : ResourceService {
        var calls = 0
        override fun handle(history: List<ConversationMessage>): ResourceService.Result {
            calls++; return result
        }
    }

    // ── 공통 헬퍼 ────────────────────────────────────────────────────────────

    private val props = AppProperties(retrieval = AppProperties.Retrieval(topK = 5))
    private val dummyResource = FakeResourceService(ResourceService.Result("리소스 답변"))

    private fun service(
        rateLimit: RateLimitGuard = FakeRateLimit(),
        guard: InputGuard = FakeGuard(GuardDecision.Allowed),
        router: QuestionRouter = FakeRouter(Route.DOC),
        embedding: EmbeddingService = FakeEmbedding(FloatArray(1536)),
        search: DocumentSearchService = FakeSearch(emptyList()),
        generation: AnswerGenerationService = FakeGeneration("답변"),
        resource: ResourceService = dummyResource,
    ) = DefaultChatService(rateLimit, guard, router, embedding, search, RetrievalPolicy(props), generation, resource, props)

    // ── DOC 경로 회귀 테스트 ─────────────────────────────────────────────────

    @Test
    fun `DOC - embeds once, searches, generates and returns answer with sources`() {
        val vector = FloatArray(1536) { 0.1f }
        val embedding = FakeEmbedding(vector)
        val chunks = listOf(
            RetrievedChunk("c1", "가이드", null, 2, 0.91),
            RetrievedChunk("c2", "가이드", 5, 8, 0.80),
        )
        val search = FakeSearch(chunks)
        val generation = FakeGeneration("근거 기반 답변")
        val svc = service(router = FakeRouter(Route.DOC), embedding = embedding, search = search, generation = generation)

        val result = svc.handle(ChatCommand(question = "질문", userId = "u1"))

        assertThat(embedding.calls).isEqualTo(1)
        assertThat(search.receivedVector).isSameAs(vector)
        assertThat(search.receivedTopK).isEqualTo(5)
        assertThat(generation.calls).isEqualTo(1)
        assertThat(result.answer).isEqualTo("근거 기반 답변")
        assertThat(result.sources).containsExactly("가이드 #2", "가이드 p.5 #8")
    }

    @Test
    fun `DOC - empty retrieval skips generation (불변식 3)`() {
        val generation = FakeGeneration("호출되면 안 됨")
        val svc = service(router = FakeRouter(Route.DOC), search = FakeSearch(emptyList()), generation = generation)

        val result = svc.handle(ChatCommand(question = "질문", userId = "u1"))

        assertThat(generation.calls).isEqualTo(0)
        assertThat(result.answer).isEqualTo("관련 문서를 찾지 못했습니다.")
    }

    @Test
    fun `blocked input short-circuits before routing (불변식 4)`() {
        val embedding = FakeEmbedding(FloatArray(1536))
        val router = FakeRouter(Route.DOC)
        val svc = service(
            guard = FakeGuard(GuardDecision.Blocked("banned", "부적절한 표현이 포함되어 답변할 수 없습니다.")),
            router = router,
            embedding = embedding,
        )

        val result = svc.handle(ChatCommand(question = "나쁜말", userId = "u1"))

        assertThat(router.calls).isEqualTo(0)
        assertThat(embedding.calls).isEqualTo(0)
        assertThat(result.answer).isEqualTo("부적절한 표현이 포함되어 답변할 수 없습니다.")
    }

    @Test
    fun `rate limit exceeded short-circuits before guard and routing (불변식 4)`() {
        val router = FakeRouter(Route.DOC)
        val embedding = FakeEmbedding(FloatArray(1536))
        val svc = service(rateLimit = FakeRateLimit(allow = false), router = router, embedding = embedding)

        val result = svc.handle(ChatCommand(question = "질문", userId = "u1"))

        assertThat(router.calls).isEqualTo(0)
        assertThat(embedding.calls).isEqualTo(0)
        assertThat(result.answer).isEqualTo("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.")
    }

    // ── RESOURCE 경로 ────────────────────────────────────────────────────────

    @Test
    fun `RESOURCE - delegates to ResourceService, embedding and generation not called`() {
        val embedding = FakeEmbedding(FloatArray(1536))
        val generation = FakeGeneration("호출되면 안 됨")
        val resource = FakeResourceService(ResourceService.Result("CPU 상위 인스턴스: web-01 82%"))
        val svc = service(router = FakeRouter(Route.RESOURCE), embedding = embedding, generation = generation, resource = resource)

        val result = svc.handle(ChatCommand(question = "CPU 높은 VM 알려줘", userId = "u1"))

        assertThat(embedding.calls).isEqualTo(0)
        assertThat(generation.calls).isEqualTo(0)
        assertThat(resource.calls).isEqualTo(1)
        assertThat(result.answer).isEqualTo("CPU 상위 인스턴스: web-01 82%")
        assertThat(result.sources).isEmpty()
    }

    @Test
    fun `RESOURCE - NeedsClarification from ResourceService passes through as answer`() {
        val resource = FakeResourceService(ResourceService.Result("어떤 지표를 조회할까요?", needsClarification = true))
        val svc = service(router = FakeRouter(Route.RESOURCE), resource = resource)

        val result = svc.handle(ChatCommand(question = "서버 어때?", userId = "u1"))

        assertThat(result.answer).isEqualTo("어떤 지표를 조회할까요?")
    }

    // ── CLARIFY 경로 ─────────────────────────────────────────────────────────

    @Test
    fun `CLARIFY - returns clarification message, no embedding or resource call`() {
        val embedding = FakeEmbedding(FloatArray(1536))
        val resource = FakeResourceService(ResourceService.Result("호출되면 안 됨"))
        val svc = service(router = FakeRouter(Route.CLARIFY), embedding = embedding, resource = resource)

        val result = svc.handle(ChatCommand(question = "뭔가 알려줘", userId = "u1"))

        assertThat(embedding.calls).isEqualTo(0)
        assertThat(resource.calls).isEqualTo(0)
        assertThat(result.answer).contains("문서 기반 질문")
        assertThat(result.sources).isEmpty()
    }
}
