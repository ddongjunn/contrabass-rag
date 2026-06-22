# 질문 라우터 (Question Router) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사용자 질문(최근 N턴 포함)을 `DOC / RESOURCE / BOTH / CLARIFY`로 분류하는 독립 라우터 모듈과 그 검증 수단(스텁 기반 로직 테스트 + 선택 정확도 테스트 + 독립 CLI)을 만든다.

**Architecture:** 새 도메인 모듈 `com.okestro.ragbot.routing`을 레포 관례(4계층)대로 추가한다. 라우터(`QuestionRouter`)는 LLM 호출 seam(`LlmClient`, 원시 JSON 반환)에만 의존해 파싱·신뢰도 폴백 로직을 API 호출 없이 단위 테스트한다. 실제 호출은 Spring AI `ChatClient` + `ResponseFormat(JSON_SCHEMA, strict)`로 OpenAI를 부른다. 이 모듈은 이번에 `ChatService`/가드 파이프라인에 **연결하지 않는다**.

**Tech Stack:** Kotlin 1.9.25, JDK 21, Spring Boot 3.5.14, Spring AI 1.1.7 (`spring-ai-openai`), Jackson(kotlin-module), Resilience4j 2.3.0, JUnit5 + kotlin-test.

**검증된 API 사실(1.1.7 바이트코드 확인):**
- `ResponseFormat.JsonSchema.builder().name(String).schema(String).strict(true).build()`
- `ResponseFormat.builder().type(ResponseFormat.Type.JSON_SCHEMA).jsonSchema(jsonSchema).build()`
- `OpenAiChatOptions.builder().model(String).temperature(Double).responseFormat(ResponseFormat).build()`
- CLI 수동 조립: `OpenAiApi.builder().apiKey(String).build()`, `OpenAiChatModel.builder().openAiApi(api).build()`

---

## File Structure

생성:
- `src/main/kotlin/com/okestro/ragbot/routing/domain/Route.kt`
- `src/main/kotlin/com/okestro/ragbot/routing/domain/RouteDecision.kt`
- `src/main/kotlin/com/okestro/ragbot/routing/domain/ConversationMessage.kt`
- `src/main/kotlin/com/okestro/ragbot/routing/domain/RoutingPolicy.kt`
- `src/main/kotlin/com/okestro/ragbot/routing/application/QuestionRouter.kt`
- `src/main/kotlin/com/okestro/ragbot/routing/application/LlmClient.kt`
- `src/main/kotlin/com/okestro/ragbot/routing/application/RoutingPrompts.kt`
- `src/main/kotlin/com/okestro/ragbot/routing/application/LlmQuestionRouter.kt`
- `src/main/kotlin/com/okestro/ragbot/routing/infrastructure/OpenAiRouterLlmClient.kt`
- `src/main/kotlin/com/okestro/ragbot/routing/interfaces/RoutingCli.kt`
- `src/test/kotlin/com/okestro/ragbot/routing/StubLlmClient.kt`
- `src/test/kotlin/com/okestro/ragbot/routing/RoutingPolicyTest.kt`
- `src/test/kotlin/com/okestro/ragbot/routing/LlmQuestionRouterTest.kt`
- `src/test/kotlin/com/okestro/ragbot/routing/RoutingAccuracyTest.kt`

수정:
- `src/main/kotlin/com/okestro/ragbot/common/config/AppProperties.kt` — `Router` 추가
- `src/main/resources/application.yml` — `app.router.*` 추가
- `build.gradle.kts` — `routingCli` JavaExec 태스크
- `README.md` — 라우터 실행/테스트 섹션

---

## Task 1: 도메인 모델 + 신뢰도 폴백 정책

순수 모델과 정책. 프레임워크 의존 없음. 정책은 단위 테스트로 못박는다.

**Files:**
- Create: `src/main/kotlin/com/okestro/ragbot/routing/domain/Route.kt`
- Create: `src/main/kotlin/com/okestro/ragbot/routing/domain/RouteDecision.kt`
- Create: `src/main/kotlin/com/okestro/ragbot/routing/domain/ConversationMessage.kt`
- Create: `src/main/kotlin/com/okestro/ragbot/routing/domain/RoutingPolicy.kt`
- Test: `src/test/kotlin/com/okestro/ragbot/routing/RoutingPolicyTest.kt`

- [ ] **Step 1: 모델 4개 작성**

`Route.kt`:
```kotlin
package com.okestro.ragbot.routing.domain

/** 질문 라우트. DOC=문서(RAG), RESOURCE=인프라 조회, BOTH=혼합, CLARIFY=되물음. */
enum class Route { DOC, RESOURCE, BOTH, CLARIFY }
```

`RouteDecision.kt`:
```kotlin
package com.okestro.ragbot.routing.domain

/** 라우터 결정. confidence 0~1, reason은 분류 근거(메트릭·디버깅용). */
data class RouteDecision(
    val route: Route,
    val confidence: Double,
    val reason: String,
)
```

`ConversationMessage.kt`:
```kotlin
package com.okestro.ragbot.routing.domain

/** 라우터 입력의 한 턴. 라우터는 마지막 질문 1개가 아니라 최근 N턴을 함께 받는다. */
data class ConversationMessage(
    val role: Role,
    val content: String,
) {
    enum class Role { USER, ASSISTANT }
}
```

`RoutingPolicy.kt`:
```kotlin
package com.okestro.ragbot.routing.domain

/**
 * 결정 후처리 정책(순수 함수). LLM이 낮은 confidence를 줬으면 안전하게 CLARIFY로 내린다.
 * 이미 CLARIFY면 그대로 둔다.
 */
object RoutingPolicy {
    fun applyConfidenceFloor(decision: RouteDecision, minConfidence: Double): RouteDecision =
        if (decision.route != Route.CLARIFY && decision.confidence < minConfidence) {
            RouteDecision(
                route = Route.CLARIFY,
                confidence = decision.confidence,
                reason = "low_confidence(${decision.confidence}) < $minConfidence; orig=${decision.route}",
            )
        } else {
            decision
        }
}
```

- [ ] **Step 2: 정책 테스트 작성(실패 확인용)**

`RoutingPolicyTest.kt`:
```kotlin
package com.okestro.ragbot.routing

import com.okestro.ragbot.routing.domain.Route
import com.okestro.ragbot.routing.domain.RouteDecision
import com.okestro.ragbot.routing.domain.RoutingPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutingPolicyTest {

    @Test
    fun `confidence가 임계값 미만이면 CLARIFY로 내린다`() {
        val d = RouteDecision(Route.DOC, 0.3, "doc-ish")
        val result = RoutingPolicy.applyConfidenceFloor(d, minConfidence = 0.5)
        assertEquals(Route.CLARIFY, result.route)
        assertEquals(0.3, result.confidence)
    }

    @Test
    fun `confidence가 임계값 이상이면 그대로 둔다`() {
        val d = RouteDecision(Route.RESOURCE, 0.9, "metric")
        val result = RoutingPolicy.applyConfidenceFloor(d, minConfidence = 0.5)
        assertEquals(Route.RESOURCE, result.route)
    }

    @Test
    fun `이미 CLARIFY면 임계값 미만이어도 그대로 둔다`() {
        val d = RouteDecision(Route.CLARIFY, 0.1, "ambiguous")
        val result = RoutingPolicy.applyConfidenceFloor(d, minConfidence = 0.5)
        assertEquals(Route.CLARIFY, result.route)
        assertEquals("ambiguous", result.reason)
    }
}
```

- [ ] **Step 3: 테스트 실행 — 컴파일/통과 확인**

Run: `./gradlew test --tests "com.okestro.ragbot.routing.RoutingPolicyTest"`
Expected: PASS (3 tests). 모델/정책이 컴파일되고 정책이 의도대로 동작.

- [ ] **Step 4: 커밋**

```bash
git add src/main/kotlin/com/okestro/ragbot/routing/domain src/test/kotlin/com/okestro/ragbot/routing/RoutingPolicyTest.kt
git commit -m "feat(routing): 라우터 도메인 모델 + 신뢰도 폴백 정책"
```

---

## Task 2: 포트 인터페이스 + 프롬프트/스키마 + 설정

라우터가 의존할 포트(`QuestionRouter`, `LlmClient`)와 프롬프트(system+few-shot)·strict JSON 스키마, 그리고 튜닝값(`app.router.*`)을 추가한다. 로직 없는 선언/상수라 별도 테스트 없이 컴파일로 검증.

**Files:**
- Create: `src/main/kotlin/com/okestro/ragbot/routing/application/QuestionRouter.kt`
- Create: `src/main/kotlin/com/okestro/ragbot/routing/application/LlmClient.kt`
- Create: `src/main/kotlin/com/okestro/ragbot/routing/application/RoutingPrompts.kt`
- Modify: `src/main/kotlin/com/okestro/ragbot/common/config/AppProperties.kt`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 포트 인터페이스 작성**

`QuestionRouter.kt`:
```kotlin
package com.okestro.ragbot.routing.application

import com.okestro.ragbot.routing.domain.ConversationMessage
import com.okestro.ragbot.routing.domain.RouteDecision

/** 교체 가능한 라우터 포트. history의 마지막 원소가 현재 질문(USER)이다. */
interface QuestionRouter {
    fun route(history: List<ConversationMessage>): RouteDecision
}
```

`LlmClient.kt`:
```kotlin
package com.okestro.ragbot.routing.application

import com.okestro.ragbot.routing.domain.ConversationMessage

/**
 * LLM 호출 seam. 구현(OpenAI)을 갈아끼우거나 테스트에서 스텁으로 대체할 수 있다.
 * 파싱된 빈이 아니라 **원시 응답 본문(JSON 문자열)** 을 반환한다 — "깨진 응답 → CLARIFY" 경로를 정직하게 테스트하기 위함.
 */
interface LlmClient {
    fun complete(request: LlmRequest): String
}

/** 라우팅 1회 호출 요청. model/temperature/jsonSchema는 호출당 옵션으로 적용된다. */
data class LlmRequest(
    val system: String,
    val messages: List<ConversationMessage>,
    val model: String,
    val temperature: Double,
    val jsonSchema: String,
)
```

- [ ] **Step 2: 프롬프트 + strict 스키마 작성**

`RoutingPrompts.kt`:
```kotlin
package com.okestro.ragbot.routing.application

/**
 * 라우팅 분류 프롬프트(system 지시문 + few-shot 6개)와 strict JSON 스키마.
 * generation/PromptTemplates 패턴을 따른다. few-shot은 4개 라우트 + 맥락 의존 + DOC↔RESOURCE 대비를 커버.
 */
object RoutingPrompts {

    val SYSTEM: String = """
        당신은 사내 LLM 챗봇의 "질문 라우터"다. 사용자 질문을 아래 4개 중 하나로 분류한다.
        - DOC: 개념·사용법·가이드 등 문서(RAG)로 답할 질문.
        - RESOURCE: 실제 인프라 상태·지표·리소스를 조회해야 하는 질문 (DB / Prometheus).
        - BOTH: DOC와 RESOURCE 의도가 함께 섞인 질문.
        - CLARIFY: 맥락만으로는 분류가 모호해 되물어야 하는 경우.

        규칙:
        - 마지막 메시지가 현재 질문이다. 직전 대화(턴)가 있으면 그 맥락을 반드시 활용한다.
          예: 직전 턴에 인스턴스 "목록"을 보여줬다면 "1번 상세 알려줘"는 RESOURCE다.
        - 지시 대상이 불명확하고 맥락도 없으면 CLARIFY.
        - confidence는 0~1 사이 확신도. reason은 분류 근거를 한국어로 짧게.

        예시:
        [대화] (없음)
        [질문] RAG에서 임베딩 모델은 어떻게 설정하나요?
        => {"route":"DOC","confidence":0.95,"reason":"개념·설정 가이드 질문"}

        [대화] (없음)
        [질문] 지금 prod 클러스터 CPU 사용률 보여줘
        => {"route":"RESOURCE","confidence":0.96,"reason":"실시간 지표 조회"}

        [대화] assistant: 인스턴스 목록입니다 — 1) web-01 2) web-02 3) db-01
        [질문] 1번 인스턴스 상세 알려줘
        => {"route":"RESOURCE","confidence":0.9,"reason":"직전 턴 인스턴스 목록 맥락에 의존한 후속 조회"}

        [대화] (없음)
        [질문] Prometheus 알람 설정법 알려주고, 지금 떠 있는 알람도 보여줘
        => {"route":"BOTH","confidence":0.88,"reason":"설정 방법(문서)+현재 발생 알람(리소스) 혼합"}

        [대화] (없음)
        [질문] 그거 어떻게 해?
        => {"route":"CLARIFY","confidence":0.85,"reason":"지시 대상 불명확, 맥락 없음"}

        [대화] (없음)
        [질문] 메모리 지금 얼마나 쓰고 있어?
        => {"route":"RESOURCE","confidence":0.93,"reason":"현재 사용량 조회('늘리는 법'이면 DOC와 대비)"}
    """.trimIndent()

    /** OpenAI strict structured outputs용 스키마. strict 모드는 additionalProperties:false + 전 필드 required 필요. */
    val SCHEMA: String = """
        {
          "type": "object",
          "properties": {
            "route": { "type": "string", "enum": ["DOC", "RESOURCE", "BOTH", "CLARIFY"] },
            "confidence": { "type": "number" },
            "reason": { "type": "string" }
          },
          "required": ["route", "confidence", "reason"],
          "additionalProperties": false
        }
    """.trimIndent()
}
```

- [ ] **Step 3: `AppProperties`에 `Router` 추가**

`AppProperties.kt`의 생성자 파라미터 목록에 `router` 추가(`slack` 줄 아래):
```kotlin
    val slack: Slack = Slack(),
    val router: Router = Router(),
) {
```
그리고 `Slack` data class 아래에 추가:
```kotlin
    data class Router(
        val model: String = "gpt-4o-mini",   // 라우팅용 작고 빠른 모델 (교체 가능)
        val temperature: Double = 0.0,
        val minConfidence: Double = 0.5,      // 미만 → CLARIFY 폴백
        val historyTurns: Int = 2,            // 라우터가 LLM에 넘기는 최근 메시지 수(현재 질문 포함)
    )
```

- [ ] **Step 4: `application.yml`에 `app.router.*` 추가**

`app:` 블록의 `slack:` 항목 아래에 추가:
```yaml
  router:
    model: gpt-4o-mini                        # 라우팅용 작고 빠른 모델
    temperature: 0.0
    min-confidence: 0.5                        # 미만이면 CLARIFY 폴백
    history-turns: 2                           # 라우터가 LLM에 넘기는 최근 메시지 수(현재 질문 포함)
```

- [ ] **Step 5: 컴파일 확인**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: 커밋**

```bash
git add src/main/kotlin/com/okestro/ragbot/routing/application/QuestionRouter.kt \
        src/main/kotlin/com/okestro/ragbot/routing/application/LlmClient.kt \
        src/main/kotlin/com/okestro/ragbot/routing/application/RoutingPrompts.kt \
        src/main/kotlin/com/okestro/ragbot/common/config/AppProperties.kt \
        src/main/resources/application.yml
git commit -m "feat(routing): 라우터 포트·프롬프트(few-shot)·strict 스키마·설정 추가"
```

---

## Task 3: LlmQuestionRouter (핵심) + 스텁 기반 로직 테스트

이 모듈의 본체. 프롬프트 구성 → `LlmClient` 호출 → JSON 파싱 → 신뢰도 폴백 → 로깅. 스텁으로 파싱·폴백을 API 호출 없이 검증한다(키 불필요, 항상 실행).

**Files:**
- Create: `src/main/kotlin/com/okestro/ragbot/routing/application/LlmQuestionRouter.kt`
- Create: `src/test/kotlin/com/okestro/ragbot/routing/StubLlmClient.kt`
- Test: `src/test/kotlin/com/okestro/ragbot/routing/LlmQuestionRouterTest.kt`

- [ ] **Step 1: 실패하는 라우터 테스트 작성**

`StubLlmClient.kt`(테스트 전용 — 받은 static JSON 문자열을 그대로 반환):
```kotlin
package com.okestro.ragbot.routing

import com.okestro.ragbot.routing.application.LlmClient
import com.okestro.ragbot.routing.application.LlmRequest

/** LLM 응답이 "임의로 온다"고 가정하고, 테스트가 지정한 static 응답을 그대로 돌려주는 스텁. */
class StubLlmClient(var response: String) : LlmClient {
    var lastRequest: LlmRequest? = null
    override fun complete(request: LlmRequest): String {
        lastRequest = request
        return response
    }
}
```

`LlmQuestionRouterTest.kt`:
```kotlin
package com.okestro.ragbot.routing

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.routing.application.LlmQuestionRouter
import com.okestro.ragbot.routing.domain.ConversationMessage
import com.okestro.ragbot.routing.domain.ConversationMessage.Role
import com.okestro.ragbot.routing.domain.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmQuestionRouterTest {

    private val props = AppProperties.Router(minConfidence = 0.5, historyTurns = 2)
    private fun routerWith(response: String) =
        LlmQuestionRouter(StubLlmClient(response), AppProperties(router = props), jacksonObjectMapper())

    private fun ask(text: String) = listOf(ConversationMessage(Role.USER, text))

    @Test
    fun `정상 JSON이면 해당 route로 매핑한다`() {
        val r = routerWith("""{"route":"DOC","confidence":0.9,"reason":"가이드"}""")
        val d = r.route(ask("임베딩 설정 어떻게 해?"))
        assertEquals(Route.DOC, d.route)
        assertEquals(0.9, d.confidence)
    }

    @Test
    fun `confidence가 임계값 미만이면 CLARIFY로 폴백한다`() {
        val r = routerWith("""{"route":"RESOURCE","confidence":0.2,"reason":"불확실"}""")
        val d = r.route(ask("그거 보여줘"))
        assertEquals(Route.CLARIFY, d.route)
    }

    @Test
    fun `깨진 JSON이면 CLARIFY로 폴백한다`() {
        val r = routerWith("이건 JSON이 아니다")
        val d = r.route(ask("아무거나"))
        assertEquals(Route.CLARIFY, d.route)
        assertTrue(d.reason.contains("parse"))
    }

    @Test
    fun `알 수 없는 route 값이면 CLARIFY로 폴백한다`() {
        val r = routerWith("""{"route":"FOO","confidence":0.9,"reason":"x"}""")
        val d = r.route(ask("아무거나"))
        assertEquals(Route.CLARIFY, d.route)
    }

    @Test
    fun `최근 N턴만 LLM에 전달한다 - 맥락 의존 케이스`() {
        val stub = StubLlmClient("""{"route":"RESOURCE","confidence":0.9,"reason":"맥락"}""")
        val router = LlmQuestionRouter(stub, AppProperties(router = props), jacksonObjectMapper())
        val history = listOf(
            ConversationMessage(Role.USER, "인스턴스 목록 보여줘"),
            ConversationMessage(Role.ASSISTANT, "1) web-01 2) web-02"),
            ConversationMessage(Role.USER, "1번 상세 알려줘"),
        )
        val d = router.route(history)
        assertEquals(Route.RESOURCE, d.route)
        // historyTurns=2 → 마지막 2개만 전달
        assertEquals(2, stub.lastRequest!!.messages.size)
        assertEquals("1번 상세 알려줘", stub.lastRequest!!.messages.last().content)
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew test --tests "com.okestro.ragbot.routing.LlmQuestionRouterTest"`
Expected: FAIL — `LlmQuestionRouter` 미존재(컴파일 에러).

- [ ] **Step 3: `LlmQuestionRouter` 구현**

`LlmQuestionRouter.kt`:
```kotlin
package com.okestro.ragbot.routing.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.routing.domain.ConversationMessage
import com.okestro.ragbot.routing.domain.Route
import com.okestro.ragbot.routing.domain.RouteDecision
import com.okestro.ragbot.routing.domain.RoutingPolicy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 기본 라우터 구현. 프롬프트 구성 → LlmClient 호출 → JSON 파싱 → 신뢰도 폴백 → 로깅.
 * 파싱 실패·빈 응답·알 수 없는 enum → CLARIFY 안전 폴백(불변식: 모호하면 되묻는다).
 * 모든 결정을 route·confidence·reason·latencyMs로 로깅한다(추후 메트릭).
 */
@Service
class LlmQuestionRouter(
    private val llmClient: LlmClient,
    private val properties: AppProperties,
    private val objectMapper: ObjectMapper,
) : QuestionRouter {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cfg get() = properties.router

    override fun route(history: List<ConversationMessage>): RouteDecision {
        val recent = history.takeLast(cfg.historyTurns)
        val request = LlmRequest(
            system = RoutingPrompts.SYSTEM,
            messages = recent,
            model = cfg.model,
            temperature = cfg.temperature,
            jsonSchema = RoutingPrompts.SCHEMA,
        )

        val start = System.nanoTime()
        val decision = try {
            val raw = llmClient.complete(request)
            val parsed = objectMapper.readValue(raw, RawDecision::class.java)
            RoutingPolicy.applyConfidenceFloor(
                RouteDecision(Route.valueOf(parsed.route), parsed.confidence, parsed.reason),
                cfg.minConfidence,
            )
        } catch (e: Exception) {
            RouteDecision(Route.CLARIFY, 0.0, "parse_failed: ${e.javaClass.simpleName}")
        }
        val latencyMs = (System.nanoTime() - start) / 1_000_000

        log.info(
            "routing decision route={} confidence={} reason={} latencyMs={}",
            decision.route, decision.confidence, decision.reason, latencyMs,
        )
        return decision
    }

    /** LLM 원시 JSON 매핑용. route는 String으로 받아 valueOf에서 검증(알 수 없으면 예외 → CLARIFY). */
    private data class RawDecision(
        val route: String = "",
        val confidence: Double = 0.0,
        val reason: String = "",
    )
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew test --tests "com.okestro.ragbot.routing.LlmQuestionRouterTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: 커밋**

```bash
git add src/main/kotlin/com/okestro/ragbot/routing/application/LlmQuestionRouter.kt \
        src/test/kotlin/com/okestro/ragbot/routing/StubLlmClient.kt \
        src/test/kotlin/com/okestro/ragbot/routing/LlmQuestionRouterTest.kt
git commit -m "feat(routing): LlmQuestionRouter(파싱·신뢰도 폴백·로깅) + 스텁 로직 테스트"
```

---

## Task 4: OpenAiRouterLlmClient (실제 OpenAI 호출)

Spring AI `ChatClient` + `ResponseFormat(JSON_SCHEMA, strict)`로 OpenAI를 호출하는 `LlmClient` 구현. 기존 OpenAI 호출처럼 Resilience4j `openai` 인스턴스로 보호. 단위 테스트는 없음(실 호출은 Task 6 정확도 테스트에서 env-gated로 검증) — 컴파일/빈 등록만 확인.

**Files:**
- Create: `src/main/kotlin/com/okestro/ragbot/routing/infrastructure/OpenAiRouterLlmClient.kt`

- [ ] **Step 1: 구현 작성**

`OpenAiRouterLlmClient.kt`:
```kotlin
package com.okestro.ragbot.routing.infrastructure

import com.okestro.ragbot.routing.application.LlmClient
import com.okestro.ragbot.routing.application.LlmRequest
import com.okestro.ragbot.routing.domain.ConversationMessage.Role
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.ResponseFormat
import org.springframework.stereotype.Component

/**
 * Spring AI ChatClient로 OpenAI 라우팅 호출. native Structured Outputs(strict json_schema)로
 * 유효 JSON과 enum 값을 API 레벨에서 강제한다. model/temperature는 호출당 옵션으로 적용(채팅 모델과 독립).
 * OpenAI 호출은 Resilience4j 'openai' 인스턴스로 Retry+CircuitBreaker 보호.
 * 단일 ChatModel 빈으로 ChatClient를 수동 구성(generation/OpenAiChatClient와 동일 관례 — 생성자 모호성 없음).
 */
@Component
class OpenAiRouterLlmClient(chatModel: ChatModel) : LlmClient {

    private val chatClient = ChatClient.builder(chatModel).build()

    @Retry(name = "openai")
    @CircuitBreaker(name = "openai")
    override fun complete(request: LlmRequest): String {
        val responseFormat = ResponseFormat.builder()
            .type(ResponseFormat.Type.JSON_SCHEMA)
            .jsonSchema(
                ResponseFormat.JsonSchema.builder()
                    .name("route_decision")
                    .schema(request.jsonSchema)
                    .strict(true)
                    .build(),
            )
            .build()

        val options = OpenAiChatOptions.builder()
            .model(request.model)
            .temperature(request.temperature)
            .responseFormat(responseFormat)
            .build()

        val messages: List<Message> = request.messages.map { m ->
            when (m.role) {
                Role.USER -> UserMessage(m.content)
                Role.ASSISTANT -> AssistantMessage(m.content)
            }
        }

        return chatClient.prompt()
            .system(request.system)
            .messages(messages)
            .options(options)
            .call()
            .content() ?: ""
    }
}
```

> 참고: 위 보조 생성자(`constructor(chatModel)`)는 Spring이 `ChatModel` 빈으로 주입할 때와 CLI(Task 5)에서 수동 조립할 때 모두 쓰인다. Spring은 단일 `ChatModel` 빈(기존 자동구성)을 주입한다.

- [ ] **Step 2: 컴파일 + 전체 테스트(회귀 없음) 확인**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — 기존 테스트 + routing 테스트 모두 통과(이 클래스는 실 호출 없음).

- [ ] **Step 3: 커밋**

```bash
git add src/main/kotlin/com/okestro/ragbot/routing/infrastructure/OpenAiRouterLlmClient.kt
git commit -m "feat(routing): OpenAI 라우터 클라이언트(strict json_schema + Resilience4j)"
```

---

## Task 5: 독립 CLI (수동 질문 도구)

Spring 전체 부팅(DB·Slack) 없이 `OpenAiChatModel` + 라우터만 손으로 조립하는 독립 `main()`. 터미널에서 질문을 타이핑해 결과를 눈으로 확인.

**Files:**
- Create: `src/main/kotlin/com/okestro/ragbot/routing/interfaces/RoutingCli.kt`
- Modify: `build.gradle.kts`

- [ ] **Step 1: CLI 작성**

`RoutingCli.kt`:
```kotlin
package com.okestro.ragbot.routing.interfaces

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.routing.application.LlmQuestionRouter
import com.okestro.ragbot.routing.domain.ConversationMessage
import com.okestro.ragbot.routing.domain.ConversationMessage.Role
import com.okestro.ragbot.routing.infrastructure.OpenAiRouterLlmClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.api.OpenAiApi

/**
 * 라우터 수동 확인용 독립 실행기. Spring 컨텍스트(DB·Slack) 없이 OpenAI 호출만 조립한다.
 * 실행: OPENAI_API_KEY=... ./gradlew routingCli -q --console=plain
 * (단일 질문 단위로 받는다 — 맥락 의존 케이스는 정확도 테스트에서 다룬다.)
 */
fun main() {
    val apiKey = System.getenv("OPENAI_API_KEY")
        ?: error("OPENAI_API_KEY 환경변수가 필요합니다")

    val api = OpenAiApi.builder().apiKey(apiKey).build()
    val chatModel = OpenAiChatModel.builder().openAiApi(api).build()
    val router = LlmQuestionRouter(
        OpenAiRouterLlmClient(chatModel),
        AppProperties(),                 // 기본값(model=gpt-4o-mini, minConfidence=0.5 …)
        jacksonObjectMapper(),
    )

    println("질문을 입력하세요(빈 줄/Ctrl-D 종료):")
    generateSequence(::readLine).forEach { line ->
        if (line.isBlank()) return@forEach
        val d = router.route(listOf(ConversationMessage(Role.USER, line)))
        println("→ ${d.route} (confidence=${d.confidence}, reason=${d.reason})")
    }
}
```

- [ ] **Step 2: Gradle 실행 태스크 추가**

`build.gradle.kts`의 `tasks.withType<Test> { ... }` 블록 아래에 추가:
```kotlin
tasks.register<JavaExec>("routingCli") {
    group = "application"
    description = "질문 라우터 수동 확인용 CLI (OPENAI_API_KEY 필요)"
    mainClass.set("com.okestro.ragbot.routing.interfaces.RoutingCliKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew compileKotlin && ./gradlew help --task routingCli`
Expected: BUILD SUCCESSFUL이고 `routingCli` 태스크가 인식됨.

- [ ] **Step 4: 커밋**

```bash
git add src/main/kotlin/com/okestro/ragbot/routing/interfaces/RoutingCli.kt build.gradle.kts
git commit -m "feat(routing): 독립 CLI(main) + routingCli Gradle 태스크"
```

---

## Task 6: 선택 정확도 테스트(env-gated) + README

실제 OpenAI를 부르는 정확도 테스트(기본 off)와 실행/테스트 문서.

**Files:**
- Create: `src/test/kotlin/com/okestro/ragbot/routing/RoutingAccuracyTest.kt`
- Modify: `README.md`

- [ ] **Step 1: 정확도 테스트 작성(기본 off)**

`RoutingAccuracyTest.kt`:
```kotlin
package com.okestro.ragbot.routing

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.okestro.ragbot.common.config.AppProperties
import com.okestro.ragbot.routing.application.LlmQuestionRouter
import com.okestro.ragbot.routing.domain.ConversationMessage
import com.okestro.ragbot.routing.domain.ConversationMessage.Role
import com.okestro.ragbot.routing.domain.Route
import com.okestro.ragbot.routing.infrastructure.OpenAiRouterLlmClient
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.api.OpenAiApi
import kotlin.test.Test
import kotlin.test.assertEquals

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

    @Test
    fun `혼합 의도는 BOTH`() {
        assertEquals(
            Route.BOTH,
            realRouter().route(user("Prometheus 알람 설정법 알려주고, 지금 떠 있는 알람도 보여줘")).route,
        )
    }
}
```

> 참고: 맥락 의존 케이스에서 `historyTurns=2`이므로 위 history(2개)가 그대로 전달된다. LLM 비결정성으로 드물게 실패할 수 있으나 기본 off라 CI를 깨지 않는다.

- [ ] **Step 2: 정확도 테스트가 기본 off인지 확인**

Run: `./gradlew test`
Expected: PASS — `OPENAI_API_KEY` 미설정 시 `RoutingAccuracyTest`는 skip, 나머지는 통과.

- [ ] **Step 3: README에 라우터 섹션 추가**

`README.md` 끝에 추가:
```markdown
## 질문 라우터 (Question Router)

사용자 질문을 `DOC / RESOURCE / BOTH / CLARIFY`로 분류하는 독립 모듈(`com.okestro.ragbot.routing`).
아직 RAG/리소스 파이프라인에 연결되지 않은 1단계 컴포넌트다.

### 설정 (`application.yml`)
`app.router.*` — `model`(라우팅 모델), `temperature`, `min-confidence`(미만이면 CLARIFY), `history-turns`(LLM에 넘기는 최근 메시지 수).

### 테스트
- 로직 테스트(키 불필요, 항상 실행): `./gradlew test`
- 실제 분류 정확도(선택): `OPENAI_API_KEY`가 있으면 `RoutingAccuracyTest`가 자동 실행된다.

### 수동 CLI
```
OPENAI_API_KEY=sk-... ./gradlew routingCli -q --console=plain
```
질문을 타이핑하면 `route / confidence / reason`을 출력한다(빈 줄/Ctrl-D 종료).
```

- [ ] **Step 4: 커밋**

```bash
git add src/test/kotlin/com/okestro/ragbot/routing/RoutingAccuracyTest.kt README.md
git commit -m "feat(routing): 선택 정확도 테스트(env-gated) + README 라우터 섹션"
```

---

## 최종 검증

- [ ] **전체 테스트 통과(키 없이)**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — RoutingPolicyTest(3) + LlmQuestionRouterTest(5) 통과, RoutingAccuracyTest skip, 기존 테스트 회귀 없음.

- [ ] **(선택) 실 호출 스모크**

Run: `OPENAI_API_KEY=sk-... ./gradlew routingCli -q --console=plain` → 질문 몇 개 입력해 라우트 확인.
