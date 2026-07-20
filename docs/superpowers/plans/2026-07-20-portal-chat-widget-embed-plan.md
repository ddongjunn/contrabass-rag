# 챗봇 위젯 사내 포털 임베드 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 챗봇 위젯(`chat-widget.js`)을 사내 포털(`remote-contrabass-admin`)에 자동으로 뜨게 임베드하고,
포털이 이미 아는 로그인 사용자/프로젝트 컨텍스트를 챗봇이 재사용하게 한다.

**Architecture:** 백엔드(`ragbot-server`)는 `ChatRequest`에 `project` 필드를 받아 QUOTA 추출이 프로젝트를
못 찾았을 때 폴백으로 쓰고, `/api/chat`에 설정 기반 CORS를 연다. 프론트(`chat-widget.js`)는 호스트 페이지의
마크업 없이 스스로 마운트하고, `window.CONTRABASS_CHAT_USER_ID`/`PROJECT`를 매 질문 전송 시점마다 다시 읽는다.
포털(`remote-contrabass-admin`)은 이미 가진 `useUserInfoStore`/`ProviderStore`를 재사용해 그 전역변수를
채우고 위젯 스크립트를 주입한다. Host 레포(`hostBootFactory`/`hostMaestro`)는 건드리지 않는다.

**Tech Stack:** Kotlin/Spring Boot 3.5.14(`ragbot-server`) · 바닐라 ESM `node --test`(`chat-widget.js`) ·
Vue 3 + Pinia + Vite + Vitest(`remote-contrabass-admin`)

**관련 스펙:** [`docs/superpowers/specs/2026-07-20-portal-chat-widget-embed-design.md`](../specs/2026-07-20-portal-chat-widget-embed-design.md)

## Global Constraints

- 설정값(모델·top-k·임계값·CORS origin 목록 등)은 `application.yml`의 `app.*` + `AppProperties`로만 — 코드에 하드코딩 금지(루트 CLAUDE.md 절대 불변식 7).
- 시크릿은 환경변수만 — 이번 작업엔 새 시크릿 없음.
- Surgical — 각 diff의 모든 줄이 해당 Task 요청에 직접 대응해야 한다. 무관한 리팩터·포맷 변경 금지.
- Host 레포(`hostBootFactory`/`hostMaestro`) 수정 금지, `/api/chat` 인증 추가 금지, 웹 위젯 멀티턴 히스토리 지원 금지(스펙 §2 비목표).
- 이 프로젝트는 `docs/process.md`의 Phase 사이클(착수 전 정렬 → 개발 → **Claude 검수**(빌드/테스트/불변식/설정외부화/범위/구조/시크릿) → **사용자 검수**(실행/외부의존/비용/보안) → 커밋 → 다음 Phase)을 따른다. 각 Phase 끝에 있는 "사용자 검수" 절을 건너뛰지 말 것 — 다음 Phase 코드는 그 전에 시작하지 않는다.

---

## Phase 1 — 백엔드: `ChatRequest`/`ChatCommand`에 `project` 필드 배선

**DoD:** `POST /api/chat`이 `project` 필드를 받아 `ChatCommand.project`까지 전달한다. 아직 아무 로직도
이 값을 사용하지 않는다(Phase 2에서 소비). 기존 요청(필드 없음)은 동작 무변화.

### Task 1: `project` 필드 추가 + 전달 경로

**Files:**
- Modify: `src/main/kotlin/com/okestro/ragbot/chat/interfaces/ChatRequest.kt`
- Modify: `src/main/kotlin/com/okestro/ragbot/chat/application/ChatCommand.kt`
- Modify: `src/main/kotlin/com/okestro/ragbot/chat/interfaces/ChatController.kt`
- Test: `src/test/kotlin/com/okestro/ragbot/chat/interfaces/ChatControllerTest.kt`

**Interfaces:**
- Produces: `ChatCommand.project: String?` (Phase 2의 `DefaultChatService`/`ResourceService`가 소비)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/kotlin/com/okestro/ragbot/chat/interfaces/ChatControllerTest.kt`에 아래 두 테스트를 추가한다
(기존 `mockMvc` 기반 테스트 아래, 클래스 닫는 `}` 앞에):

```kotlin
    @Test
    fun `project 필드가 있으면 ChatCommand로 전달된다`() {
        var received: ChatCommand? = null
        val capturing = object : ChatService {
            override fun handle(command: ChatCommand): ChatResult {
                received = command
                return ChatResult(answer = "ok", sources = emptyList())
            }
        }
        val controller = ChatController(capturing)

        controller.chat(ChatRequest(question = "쿼터 얼마나 썼어?", userId = "u1", project = "AUTOTEST"))

        assertEquals("AUTOTEST", received?.project)
    }

    @Test
    fun `project가 없으면 null로 전달된다(하위호환)`() {
        var received: ChatCommand? = null
        val capturing = object : ChatService {
            override fun handle(command: ChatCommand): ChatResult {
                received = command
                return ChatResult(answer = "ok", sources = emptyList())
            }
        }
        val controller = ChatController(capturing)

        controller.chat(ChatRequest(question = "질문", userId = "u1"))

        assertEquals(null, received?.project)
    }
```

파일 상단 import에 아래를 추가한다:

```kotlin
import com.okestro.ragbot.chat.application.ChatCommand
import com.okestro.ragbot.chat.application.ChatResult
import com.okestro.ragbot.chat.application.ChatService
import kotlin.test.assertEquals
```

- [ ] **Step 2: 테스트 실행 → 컴파일 실패 확인**

Run: `./gradlew test --tests "com.okestro.ragbot.chat.interfaces.ChatControllerTest"`
Expected: FAIL — `ChatRequest`에 `project` 파라미터가 없어 컴파일 오류(`No value passed for parameter 'project'`가 아니라 `Cannot find a parameter with this name: project`).

- [ ] **Step 3: `ChatRequest`/`ChatCommand`/`ChatController`에 `project` 추가**

`src/main/kotlin/com/okestro/ragbot/chat/interfaces/ChatRequest.kt` 전체를 아래로 교체:

```kotlin
package com.okestro.ragbot.chat.interfaces

/**
 * POST /api/chat 요청. userId 미지정 시 'anonymous'로 처리(REST 직접 호출 등).
 * project는 호출부(포털)가 이미 아는 프로젝트/테넌트 컨텍스트 — RESOURCE의 QUOTA 추출이
 * 질문에서 프로젝트를 못 찾았을 때만 폴백으로 쓴다(설계 §3.3b).
 */
data class ChatRequest(
    val question: String,
    val userId: String? = null,
    val project: String? = null,
)
```

`src/main/kotlin/com/okestro/ragbot/chat/application/ChatCommand.kt` 전체를 아래로 교체:

```kotlin
package com.okestro.ragbot.chat.application

import com.okestro.ragbot.chat.domain.ConversationMessage

/** 단일 진입점 ChatService 입력. 채널(REST/Slack) 무관 공통 커맨드. */
data class ChatCommand(
    val question: String,
    val userId: String,                                          // 레이트리밋·질의 로그 키(Slack user_id / REST는 anonymous)
    val history: List<ConversationMessage> = emptyList(),        // Slack 스레드 히스토리(REST는 빈 목록)
    val project: String? = null,                                  // 호출부(포털) 컨텍스트 — QUOTA 추출 폴백용(Phase 2)
)
```

`src/main/kotlin/com/okestro/ragbot/chat/interfaces/ChatController.kt`의 `chat` 메서드 본문을 교체:

```kotlin
    @PostMapping
    fun chat(@RequestBody request: ChatRequest): ChatResponse {
        val userId = request.userId ?: "anonymous"
        log.info("POST /api/chat userId={} question.len={}", userId, request.question.length)
        val result = chatService.handle(
            ChatCommand(
                question = request.question,
                userId = userId,
                project = request.project?.takeIf { it.isNotBlank() },
            ),
        )
        return ChatResponse(answer = result.answer, sources = result.sources, widgets = result.widgets, followups = result.followups)
    }
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.okestro.ragbot.chat.interfaces.ChatControllerTest"`
Expected: PASS (4 tests — 기존 2개 + 신규 2개)

- [ ] **Step 5: 전체 빌드 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (다른 파일이 `ChatCommand`/`ChatRequest`를 위치 기반 인자로 생성하지 않는지 확인 —
전부 named argument라 컴파일 영향 없음)

- [ ] **Step 6: 커밋**

```bash
git add src/main/kotlin/com/okestro/ragbot/chat/interfaces/ChatRequest.kt \
        src/main/kotlin/com/okestro/ragbot/chat/application/ChatCommand.kt \
        src/main/kotlin/com/okestro/ragbot/chat/interfaces/ChatController.kt \
        src/test/kotlin/com/okestro/ragbot/chat/interfaces/ChatControllerTest.kt
git commit -m "feat(chat): ChatRequest/ChatCommand에 project 필드 배선"
```

### Phase 1 검수

**Claude 검수(자동):** Step 4·5의 빌드/테스트 통과가 증거.

**사용자 검수(실 환경):**
- [ ] 로컬에서 `./gradlew bootRun`(`SLACK_BOT_TOKEN=` `SLACK_APP_TOKEN=` 빈 값) 후
  `curl -X POST localhost:8080/api/chat -H 'Content-Type: application/json' -d '{"question":"안녕","userId":"u1","project":"AUTOTEST"}'`
  가 기존과 동일하게 200 응답하는지(project를 보내도 에러 없음, 아직 응답 내용에 영향 없음).
- [ ] `project` 필드를 안 보내도(기존 클라이언트) 그대로 동작하는지.

두 항목 확인되면 Phase 2로.

---

## Phase 2 — 백엔드: QUOTA 컨텍스트 폴백

**DoD:** QUOTA 질문에서 프로젝트가 질문 문장에 없어도 `ChatCommand.project`(포털 컨텍스트)가 있으면
되묻지 않고 답변한다. 질문에 프로젝트가 명시되면 그게 우선한다. 둘 다 없으면 지금처럼 되묻는다.

### Task 2: `ResourceExtraction.QuotaResolved`를 nullable로 — 추출기는 더 이상 "프로젝트 없음"을 스스로 되묻지 않는다

**Files:**
- Modify: `src/main/kotlin/com/okestro/ragbot/resource/domain/ResourceExtraction.kt`
- Modify: `src/main/kotlin/com/okestro/ragbot/resource/application/LlmMetricQueryExtractor.kt`
- Modify: `src/main/kotlin/com/okestro/ragbot/resource/application/DefaultResourceService.kt` (컴파일 유지용
  최소 수정만 — Step 3-b. 시그니처·폴백 로직은 Task 3의 몫)
- Modify: `src/test/kotlin/com/okestro/ragbot/resource/LlmMetricQueryExtractorTest.kt`

**Interfaces:**
- Produces: `ResourceExtraction.QuotaResolved(project: String?)` (Task 3의 `DefaultResourceService`가 소비)

> ⚠️ **주의(실행 중 발견)**: `QuotaResolved.project`를 nullable로 바꾸면 `DefaultResourceService.kt`의
> 기존 호출 `quotaGauge(extraction.project)`가 `String?`을 `String`에 넘기게 되어 **컴파일이 깨진다.**
> Task 2/3 경계가 "독립적으로 컴파일된다"는 원칙(Task Right-Sizing)을 어겼던 지점이다. Step 3-b에서
> 그 한 분기만 null 체크로 최소 수정한다 — `handle()`의 시그니처나 컨텍스트 폴백은 여전히 Task 3의 몫이다.

- [ ] **Step 1: 실패하는 테스트로 기존 테스트 교체**

`src/test/kotlin/com/okestro/ragbot/resource/LlmMetricQueryExtractorTest.kt`에서 기존 테스트

```kotlin
    @Test
    fun `QUOTA인데 프로젝트가 없으면 되물음 - 테넌트가 43개라 특정이 필요하다`() {
        val result = extractorWith(
            """{"target":"QUOTA","clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","project":null,"confidence":0.9}"""
        ).extract(ask("쿼터 얼마나 썼어?"))

        assertIs<ResourceExtraction.NeedsClarification>(result)
    }
```

를 아래로 교체(프로젝트 유무 판단과 "되물을지"는 이제 서비스 계층의 책임 — 설계 §3.3b):

```kotlin
    @Test
    fun `QUOTA인데 프로젝트가 없으면 project null인 QuotaResolved - 되물을지는 서비스가 결정한다`() {
        val result = extractorWith(
            """{"target":"QUOTA","clarificationNeeded":false,"clarificationMessage":"","metric":"INSTANCE_CPU","sort":"DESC","topN":5,"window":"5m","project":null,"confidence":0.9}"""
        ).extract(ask("쿼터 얼마나 썼어?"))

        assertNull(assertIs<ResourceExtraction.QuotaResolved>(result).project)
    }
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `./gradlew test --tests "com.okestro.ragbot.resource.LlmMetricQueryExtractorTest"`
Expected: FAIL — 실제 결과가 `NeedsClarification`이라 `QuotaResolved`로의 `assertIs` 실패.

- [ ] **Step 3: `QuotaResolved` nullable화 + `toQuota()` 단순화**

`src/main/kotlin/com/okestro/ragbot/resource/domain/ResourceExtraction.kt`에서 `QuotaResolved` 정의를 교체:

```kotlin
    /**
     * QUOTA 트랙 — 특정 프로젝트의 쿼터 사용량(quota_gauge).
     *
     * project가 null이면(질문에 명시 안 됨) 서비스 계층이 호출부 컨텍스트(포털이 아는 현재
     * 프로젝트)로 폴백을 시도한다 — 그래도 없으면 그때 되묻는다(DefaultResourceService).
     */
    data class QuotaResolved(val project: String?) : ResourceExtraction()
```

`src/main/kotlin/com/okestro/ragbot/resource/application/LlmMetricQueryExtractor.kt`의 `toQuota()`를 교체:

```kotlin
    /**
     * QUOTA는 대상 테넌트가 있어야 게이지를 채울 수 있지만, "없으면 되묻는다"는 결정은
     * 서비스 계층으로 옮겼다(호출부 컨텍스트로 폴백할 여지를 주기 위해). 여기서는 그대로 싣기만 한다.
     */
    private fun toQuota(raw: RawExtraction): ResourceExtraction =
        ResourceExtraction.QuotaResolved(raw.project?.takeIf { it.isNotBlank() })
```

`logResult()`의 `QuotaResolved` 로그 줄(`project={}` 사용)은 `result.project`가 nullable이 되어도 문자열
포맷팅은 그대로 동작하므로 변경 불필요.

- [ ] **Step 3-b: `DefaultResourceService.kt`를 컴파일 가능하게 최소 수정 (동작은 그대로 보존)**

`src/main/kotlin/com/okestro/ragbot/resource/application/DefaultResourceService.kt`의 `when` 블록에서

```kotlin
            is ResourceExtraction.QuotaResolved -> quotaGauge(extraction.project)
```

를 아래로 교체(추출기가 하던 되물음을 그대로 여기로 옮긴 것뿐 — 동작 변화 없음, `contextProject` 폴백은
아직 없음):

```kotlin
            is ResourceExtraction.QuotaResolved -> {
                val project = extraction.project
                if (project == null) {
                    ResourceService.Result("어느 프로젝트의 쿼터를 조회할까요?", needsClarification = true)
                } else {
                    quotaGauge(project)
                }
            }
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.okestro.ragbot.resource.LlmMetricQueryExtractorTest"` (단위 테스트)
Run: `./gradlew build` (Step 3-b 없이는 컴파일이 깨지므로 전체 빌드로 반드시 재확인)
Expected: PASS / BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/kotlin/com/okestro/ragbot/resource/domain/ResourceExtraction.kt \
        src/main/kotlin/com/okestro/ragbot/resource/application/LlmMetricQueryExtractor.kt \
        src/main/kotlin/com/okestro/ragbot/resource/application/DefaultResourceService.kt \
        src/test/kotlin/com/okestro/ragbot/resource/LlmMetricQueryExtractorTest.kt
git commit -m "refactor(resource): QUOTA 되물음 여부 결정을 추출기에서 서비스로 이동"
```

### Task 3: `ResourceService`/`DefaultResourceService`에 컨텍스트 폴백 적용 + `DefaultChatService` 배선

**Files:**
- Modify: `src/main/kotlin/com/okestro/ragbot/resource/application/ResourceService.kt`
- Modify: `src/main/kotlin/com/okestro/ragbot/resource/application/DefaultResourceService.kt`
- Modify: `src/main/kotlin/com/okestro/ragbot/chat/application/DefaultChatService.kt`
- Modify: `src/test/kotlin/com/okestro/ragbot/chat/application/DefaultChatServiceTest.kt`
- Modify: `src/test/kotlin/com/okestro/ragbot/resource/DefaultResourceServiceQuotaTest.kt`

**Interfaces:**
- Consumes: `ResourceExtraction.QuotaResolved(project: String?)` (Task 2)
- Produces: `ResourceService.handle(history, contextProject: String? = null): Result` — 이후 아무도 이 필드를
  더 소비하지 않음(Phase 종료 지점).

- [ ] **Step 1: 실패하는 테스트 작성 — `DefaultChatServiceTest`**

`src/test/kotlin/com/okestro/ragbot/chat/application/DefaultChatServiceTest.kt`의 `FakeResourceService`를 교체:

```kotlin
    private class FakeResourceService(private val result: ResourceService.Result) : ResourceService {
        var calls = 0
        var receivedProject: String? = null
        override fun handle(history: List<ConversationMessage>, contextProject: String?): ResourceService.Result {
            calls++
            receivedProject = contextProject
            return result
        }
    }
```

같은 파일의 "RESOURCE 경로" 섹션 마지막에 새 테스트 추가:

```kotlin
    @Test
    fun `RESOURCE - ChatCommand의 project가 ResourceService로 전달된다`() {
        val resource = FakeResourceService(ResourceService.Result("답변"))
        val svc = service(router = FakeRouter(Route.RESOURCE), resource = resource)

        svc.handle(ChatCommand(question = "쿼터 얼마나 썼어?", userId = "u1", project = "AUTOTEST"))

        assertThat(resource.receivedProject).isEqualTo("AUTOTEST")
    }
```

- [ ] **Step 2: 테스트 실행 → 컴파일 실패 확인**

Run: `./gradlew test --tests "com.okestro.ragbot.chat.application.DefaultChatServiceTest"`
Expected: FAIL — `ResourceService.handle`이 1개 인자만 받아 `override` 시그니처 불일치로 컴파일 오류.

- [ ] **Step 3: `ResourceService` 인터페이스 + `DefaultResourceService` 구현**

`src/main/kotlin/com/okestro/ragbot/resource/application/ResourceService.kt`의 `handle` 시그니처를 교체:

```kotlin
package com.okestro.ragbot.resource.application

import com.okestro.ragbot.chat.domain.ConversationMessage
import com.okestro.ragbot.resource.domain.Widget

interface ResourceService {
    data class Result(
        val answer: String,
        val needsClarification: Boolean = false,
        val widgets: List<Widget> = emptyList(),
        val followups: List<String> = emptyList(),
    )

    /** contextProject: 호출부(포털) 컨텍스트 — QUOTA가 질문에서 project를 못 찾았을 때만 폴백으로 쓴다. */
    fun handle(history: List<ConversationMessage>, contextProject: String? = null): Result
}
```

`src/main/kotlin/com/okestro/ragbot/resource/application/DefaultResourceService.kt`의 `handle()` 시그니처와
`QuotaResolved` 분기를 교체. (Task 2 Step 3-b가 이미 `QuotaResolved` 분기를 null 체크가 있는 형태로 한 번
바꿔놨다 — 지금 파일에는 `extraction.project`가 아니라 `val project = extraction.project; if (project ==
null) ... else quotaGauge(project)` 형태가 이미 있을 것이다. 아래 최종 코드로 그 블록을 다시 교체하면 된다.)

```kotlin
    override fun handle(history: List<ConversationMessage>, contextProject: String?): ResourceService.Result {
        return when (val extraction = extractor.extract(history)) {
            is ResourceExtraction.NeedsClarification -> ResourceService.Result(extraction.message, needsClarification = true)
            is ResourceExtraction.StatusResolved -> statusDonut()
            is ResourceExtraction.ThresholdResolved -> thresholdBanner()
            is ResourceExtraction.QuotaResolved -> {
                val project = extraction.project ?: contextProject
                if (project == null) {
                    ResourceService.Result("어느 프로젝트의 쿼터를 조회할까요?", needsClarification = true)
                } else {
                    quotaGauge(project)
                }
            }
            is ResourceExtraction.ProjectUsageResolved -> projectUsageBar()
```

(이 아래 `is ResourceExtraction.Resolved`, `is ResourceExtraction.InventoryResolved` 분기는 그대로 둔다 —
이번 폴백은 QUOTA에만 적용한다. METRIC/INVENTORY의 `project`는 원래도 선택적 필터라 되묻지 않으므로
컨텍스트 자동 적용 대상이 아니다.)

`private fun quotaGauge(project: String)`는 시그니처 변경 없음(호출부에서 이미 non-null로 좁혀서 넘김).

- [ ] **Step 4: `DefaultChatService`에서 `command.project` 전달**

`src/main/kotlin/com/okestro/ragbot/chat/application/DefaultChatService.kt`의 `handle()`에서 RESOURCE 분기와
`handleResource()`를 교체:

```kotlin
        return when (routeDecision.route) {
            Route.DOC      -> handleDoc(command)
            Route.RESOURCE -> handleResource(history, command.userId, command.project)
            Route.CLARIFY  -> {
```

```kotlin
    private fun handleResource(history: List<ConversationMessage>, userId: String, project: String?): ChatResult {
        val result = resourceService.handle(history, project)
        val callType = if (result.needsClarification) "clarify" else "answered"
        log.info("chat resource-{} userId={} routingCalls=1 extractionCalls=1 llmCalls=0", callType, userId)
        return ChatResult(answer = result.answer, sources = emptyList(), widgets = result.widgets, followups = result.followups)
    }
```

- [ ] **Step 5: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.okestro.ragbot.chat.application.DefaultChatServiceTest"`
Expected: PASS (기존 7개 + 신규 1개 = 8개)

- [ ] **Step 6: `DefaultResourceServiceQuotaTest`에 컨텍스트 폴백 테스트 추가**

`src/test/kotlin/com/okestro/ragbot/resource/DefaultResourceServiceQuotaTest.kt`의 `handle()` 헬퍼 바로
아래에 새 헬퍼와 테스트 3개를 추가한다:

```kotlin
    private fun handleWithContext(prom: PrometheusClient, extraction: ResourceExtraction.QuotaResolved, contextProject: String?) =
        DefaultResourceService(FixedExtractor(extraction), MetricCatalog(props), prom, emptyProvider(), props)
            .handle(listOf(ConversationMessage(Role.USER, "쿼터 얼마나 썼어?")), contextProject)

    @Test
    fun `질문에 project가 없어도 컨텍스트 project로 폴백한다`() {
        val w = assertIs<QuotaGaugeWidget>(
            handleWithContext(StubPrometheus(live()), ResourceExtraction.QuotaResolved(null), "AUTOTEST").widgets.single(),
        )
        assertEquals(listOf("vCPU", "메모리(GB)", "디스크(GB)"), w.items.map { it.resource })
    }

    @Test
    fun `질문에 project가 명시되면 컨텍스트보다 우선한다`() {
        val prom = StubPrometheus(emptyList())
        handleWithContext(prom, ResourceExtraction.QuotaResolved("EXPLICIT"), "CONTEXT_PROJECT")

        assertTrue(prom.seen.single().contains("""tenant="EXPLICIT""""), prom.seen.single())
    }

    @Test
    fun `project도 컨텍스트도 없으면 되묻는다`() {
        val out = handleWithContext(StubPrometheus(emptyList()), ResourceExtraction.QuotaResolved(null), null)

        assertTrue(out.needsClarification)
        assertEquals("어느 프로젝트의 쿼터를 조회할까요?", out.answer)
    }
```

- [ ] **Step 7: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.okestro.ragbot.resource.DefaultResourceServiceQuotaTest"`
Expected: PASS (기존 8개 + 신규 3개 = 11개)

- [ ] **Step 8: 전체 빌드/테스트**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — `ResourceService`의 다른 구현체·호출부가 없는지 재확인(1개 구현체만 존재,
Phase 시작 전 grep으로 확인됨).

- [ ] **Step 9: 커밋**

```bash
git add src/main/kotlin/com/okestro/ragbot/resource/application/ResourceService.kt \
        src/main/kotlin/com/okestro/ragbot/resource/application/DefaultResourceService.kt \
        src/main/kotlin/com/okestro/ragbot/chat/application/DefaultChatService.kt \
        src/test/kotlin/com/okestro/ragbot/chat/application/DefaultChatServiceTest.kt \
        src/test/kotlin/com/okestro/ragbot/resource/DefaultResourceServiceQuotaTest.kt
git commit -m "feat(resource): QUOTA가 프로젝트를 못 찾으면 호출부 컨텍스트로 폴백"
```

### Phase 2 검수

**Claude 검수(자동):** Step 5·7·8의 빌드/테스트 통과가 증거. 폴백은 QUOTA에만 적용됨(METRIC/INVENTORY
무변경) — 코드 리뷰로 확인.

**사용자 검수(실 환경, OpenAI API 키 필요):**
- [ ] `./gradlew resourceCli`로 "쿼터 얼마나 썼어?"(프로젝트 언급 없음)를 물었을 때 여전히 되묻는지(컨텍스트가
  없는 CLI 경로이므로 Phase 2 이전과 동일해야 함 — 회귀 없음 확인).
- [ ] `curl -X POST localhost:8080/api/chat -d '{"question":"쿼터 얼마나 썼어?","userId":"u1","project":"AUTOTEST"}'`
  로 실제 AUTOTEST 테넌트의 쿼터 게이지가 되묻지 않고 나오는지(실제 Prometheus 연결 필요).
- [ ] 질문에 다른 프로젝트를 명시하면(예: "TEST2 쿼터 얼마나 썼어?") `project` 필드값이 아니라 질문에 명시된
  프로젝트가 조회되는지.

세 항목 확인되면 Phase 3으로.

---

## Phase 3 — 백엔드: CORS

**DoD:** `application.yml`의 `app.cors.allowed-origins`에 등록된 origin에서만 `/api/chat`에 대한 브라우저
크로스오리진 호출이 허용된다. 미설정(기본값)이면 지금처럼 CORS 헤더 없음(안전한 기본값).

### Task 4: `AppProperties.Cors` + `CorsConfig` + `application.yml`

**Files:**
- Modify: `src/main/kotlin/com/okestro/ragbot/common/config/AppProperties.kt`
- Create: `src/main/kotlin/com/okestro/ragbot/common/config/CorsConfig.kt`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/kotlin/com/okestro/ragbot/common/config/CorsConfigTest.kt` (신규)

**Interfaces:**
- Produces: `AppProperties.Cors.allowedOrigins: List<String>` — 다른 Phase가 소비하지 않음(런타임 설정 종단점).

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/kotlin/com/okestro/ragbot/common/config/CorsConfigTest.kt` 신규 작성:

```kotlin
package com.okestro.ragbot.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.springframework.web.servlet.config.annotation.CorsRegistry

class CorsConfigTest {

    @Test
    fun `allowed-origins가 설정되면 api-chat에 CORS 매핑이 등록된다`() {
        val props = AppProperties(cors = AppProperties.Cors(allowedOrigins = listOf("http://localhost:5173")))
        val registry = CorsRegistry()

        CorsConfig(props).addCorsMappings(registry)

        val config = registry.corsConfigurations["/api/chat"]
        assertEquals(listOf("http://localhost:5173"), config?.allowedOrigins)
        assertEquals(listOf("POST"), config?.allowedMethods)
    }

    @Test
    fun `allowed-origins가 비어있으면 매핑을 등록하지 않는다(기본값 안전)`() {
        val props = AppProperties()
        val registry = CorsRegistry()

        CorsConfig(props).addCorsMappings(registry)

        assertNull(registry.corsConfigurations["/api/chat"])
    }
}
```

- [ ] **Step 2: 테스트 실행 → 컴파일 실패 확인**

Run: `./gradlew test --tests "com.okestro.ragbot.common.config.CorsConfigTest"`
Expected: FAIL — `AppProperties`에 `cors` 파라미터가 없고 `CorsConfig` 클래스가 없어 컴파일 오류.

- [ ] **Step 3: `AppProperties.Cors` 추가**

`src/main/kotlin/com/okestro/ragbot/common/config/AppProperties.kt`의 주 생성자에 `cors` 필드 추가:

```kotlin
data class AppProperties(
    val llm: Llm = Llm(),
    val openai: Openai = Openai(),
    val retrieval: Retrieval = Retrieval(),
    val cache: Cache = Cache(),
    val guard: Guard = Guard(),
    val slack: Slack = Slack(),
    val router: Router = Router(),
    val resource: Resource = Resource(),
    val cors: Cors = Cors(),
) {
```

같은 파일에 (다른 `data class` 옆, 클래스 최상위 레벨에) 추가:

```kotlin
    /** 브라우저 크로스오리진 호출 허용 목록. 비어있으면(기본값) CORS 미적용 — 지금처럼 차단. */
    data class Cors(
        val allowedOrigins: List<String> = emptyList(),
    )
```

- [ ] **Step 4: `CorsConfig` 작성**

`src/main/kotlin/com/okestro/ragbot/common/config/CorsConfig.kt` 신규 작성:

```kotlin
package com.okestro.ragbot.common.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * app.cors.allowed-origins가 비어있으면(기본값) 매핑을 등록하지 않는다 — 브라우저 크로스오리진
 * 호출이 지금처럼 차단되는 안전한 기본 동작을 유지한다(불변식 7: 하드코딩 금지, yml로만 조정).
 */
@Configuration
class CorsConfig(private val props: AppProperties) : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        if (props.cors.allowedOrigins.isEmpty()) return
        registry.addMapping("/api/chat")
            .allowedOrigins(*props.cors.allowedOrigins.toTypedArray())
            .allowedMethods("POST")
            .allowedHeaders("Content-Type")
    }
}
```

- [ ] **Step 5: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.okestro.ragbot.common.config.CorsConfigTest"`
Expected: PASS

- [ ] **Step 6: `application.yml`에 설정 항목 추가**

`src/main/resources/application.yml`에서 `resource:` 블록이 끝나는 지점(`query-timeout-ms: 3000` 다음 줄,
`# --- 회복탄력성` 주석 앞)에 추가:

```yaml
  cors:
    allowed-origins: []                        # 예: [http://localhost:5173] — 비어있으면 CORS 미적용(기본값 안전)
```

- [ ] **Step 7: 전체 빌드/테스트 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — 기존 `ChatControllerTest`(`@WebMvcTest`)가 `CorsConfig` 빈이 컨텍스트에 추가돼도
깨지지 않는지 확인(허용 origin 없으니 동작 무변화).

- [ ] **Step 8: 커밋**

```bash
git add src/main/kotlin/com/okestro/ragbot/common/config/AppProperties.kt \
        src/main/kotlin/com/okestro/ragbot/common/config/CorsConfig.kt \
        src/main/resources/application.yml \
        src/test/kotlin/com/okestro/ragbot/common/config/CorsConfigTest.kt
git commit -m "feat(common): app.cors.allowed-origins 기반 /api/chat CORS 설정"
```

### Phase 3 검수

**Claude 검수(자동):** Step 5·7의 빌드/테스트 통과가 증거. 기본값(빈 리스트)이면 매핑 미등록 — 정적 확인됨.

**사용자 검수(실 환경 — 브라우저 필요, curl로는 CORS 프리플라이트를 검증할 수 없음):**
- [ ] `application.yml`(또는 환경변수 오버라이드)에 `app.cors.allowed-origins: [http://localhost:5173]`을
  설정한 뒤 서버를 띄우고, 브라우저 콘솔에서 `fetch('http://localhost:8080/api/chat', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({question:'안녕'})})`를
  `http://localhost:5173` 페이지에서 실행해 성공하는지(개발자도구 Network 탭에서 CORS 에러가 없는지).
- [ ] 다른 origin(예: `http://localhost:9999`)에서 같은 호출을 하면 브라우저가 차단하는지(CORS 에러 확인).
- [ ] 설정을 비운(기본값) 상태로 재기동해 기존처럼 차단되는지(회귀 없음).

세 항목 확인되면 Phase 4로. **여기서 백엔드 변경은 끝난다** — 이후 Phase는 프론트엔드다.

---

## Phase 4 — `chat-widget.js`: 자체 마크업 주입 + 컨텍스트 매 전송 재조회

**DoD:** 호스트 페이지가 `<div id="contrabass-chat">`/`<template id="cc-chrome">`를 들고 있지 않아도
`<script type="module" src=".../chat-widget.js">` 한 줄만으로 위젯이 뜬다. `window.CONTRABASS_CHAT_USER_ID`/
`PROJECT`가 있으면 매 질문 전송마다 다시 읽어 요청 본문에 싣는다. 없으면 기존 폴백(랜덤 UUID, project
미전송) — 데모 페이지 하위호환.

### Task 5: 위젯 마크업을 코드로 이전 (`render/chrome.js`) + 자체 마운트

**Files:**
- Create: `src/main/resources/static/chat-widget/render/chrome.js`
- Create: `src/main/resources/static/chat-widget/test/chrome.test.mjs`
- Modify: `src/main/resources/static/chat-widget/chat-widget.js`
- Modify: `src/main/resources/static/chat-widget/index.html`

**Interfaces:**
- Produces: `buildChrome(): PlainNode` (Task 5 스스로 `chat-widget.js`에서 소비)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/main/resources/static/chat-widget/test/chrome.test.mjs` 신규 작성:

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { buildChrome } from "../render/chrome.js";

function find(node, cls) {
  if (node.className && node.className.split(" ").includes(cls)) return node;
  for (const c of node.children || []) { const h = find(c, cls); if (h) return h; }
  return null;
}

function findByAttr(node, key) {
  if (node.attrs && key in node.attrs) return node;
  for (const c of node.children || []) { const h = findByAttr(c, key); if (h) return h; }
  return null;
}

test("buildChrome includes launcher, panel, form and message markers", () => {
  const node = buildChrome();
  assert.notEqual(find(node, "chat-widget"), null);
  assert.notEqual(find(node, "chat-launcher"), null);
  assert.notEqual(find(node, "chat-panel"), null);
  assert.notEqual(findByAttr(node, "data-chat-messages"), null);
  assert.notEqual(findByAttr(node, "data-chat-form"), null);
  assert.notEqual(findByAttr(node, "data-chat-input"), null);
  assert.notEqual(findByAttr(node, "data-chat-close"), null);
});

test("buildChrome root carries data-open=false initially", () => {
  const node = buildChrome();
  assert.equal(node.attrs["data-open"], "false");
});
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd src/main/resources/static/chat-widget && npm test`
Expected: FAIL — `render/chrome.js` 모듈이 없어 import 오류.

- [ ] **Step 3: `render/chrome.js` 작성 (`index.html`의 `<template id="cc-chrome">` 마크업을 1:1로 이전)**

`src/main/resources/static/chat-widget/render/chrome.js` 신규 작성:

```js
import { h } from "./dom.js";

// index.html의 <template id="cc-chrome"> 마크업을 그대로 코드로 옮긴 것 — 클래스명/데이터
// 속성은 chat-widget.css 및 chat-widget.js의 querySelector와 1:1로 맞아야 한다.
export function buildChrome() {
  return h("section", { className: "chat-widget", attrs: { "data-open": "false", "aria-label": "CONTRABASS assistant" } }, [
    h("button", { className: "chat-launcher", attrs: { type: "button", "aria-label": "채팅 열기", "aria-expanded": "false" } }, [
      h("span", { className: "launcher-glyph", attrs: { "aria-hidden": "true" } }),
      h("span", { className: "launcher-text", text: "Chat" }),
    ]),
    h("div", { className: "chat-panel", attrs: { role: "dialog", "aria-label": "CONTRABASS assistant" } }, [
      h("header", { className: "chat-header" }, [
        h("div", {}, [
          h("p", { className: "chat-kicker", text: "CONTRABASS" }),
          h("h1", { text: "Assistant" }),
        ]),
        h("button", { className: "icon-button", attrs: { type: "button", "data-chat-close": "", "aria-label": "채팅 닫기" } }, [
          h("span", { attrs: { "aria-hidden": "true" }, text: "×" }),
        ]),
      ]),
      h("div", { className: "chat-messages", attrs: { "data-chat-messages": "", "aria-live": "polite" } }),
      h("form", { className: "chat-form", attrs: { "data-chat-form": "" } }, [
        h("label", { className: "sr-only", attrs: { for: "chat-input" }, text: "질문" }),
        h("textarea", { attrs: { id: "chat-input", "data-chat-input": "", rows: "1", maxlength: "1000", placeholder: "질문을 입력하세요" } }),
        h("button", { className: "send-button", attrs: { type: "submit", "aria-label": "전송" }, text: "전송" }),
      ]),
    ]),
  ]);
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `cd src/main/resources/static/chat-widget && npm test`
Expected: PASS (`chrome.test.mjs` 2개 + 기존 전체 그린)

- [ ] **Step 5: `chat-widget.js`가 스스로 마운트하도록 변경**

`src/main/resources/static/chat-widget/chat-widget.js`의 최상단 import와 Shadow DOM 마운트 부분(파일
1~21행)을 교체:

```js
import { mount } from "./render/dom.js";
import { buildWidget } from "./render/dispatch.js";
import { buildChrome } from "./render/chrome.js";

(function () {
  const storage = {
    userId: "contrabass.chat.userId",
    conversationId: "contrabass.chat.conversationId",
    messages: "contrabass.chat.messages",
  };

  // 호스트 페이지가 #contrabass-chat/템플릿을 들고 있지 않아도 되게 스스로 컨테이너를 만든다.
  // (Module Federation 등으로 같은 컴포넌트가 여러 번 마운트될 수 있어 중복 삽입을 막는다.)
  if (document.getElementById("contrabass-chat")) return;

  const host = document.createElement("div");
  host.id = "contrabass-chat";
  document.body.append(host);

  const shadow = host.attachShadow({ mode: "open" });

  const styleLink = document.createElement("link");
  styleLink.rel = "stylesheet";
  // 상대경로가 호스트 페이지(포털) 기준으로 풀리는 것을 막는다 — 이 스크립트 자신의 URL 기준으로 고정.
  styleLink.href = new URL("./chat-widget.css", import.meta.url).href;
  shadow.append(styleLink);

  shadow.append(mount(buildChrome()));

  const widget = shadow.querySelector(".chat-widget");
```

이후 코드(22행부터, `const launcher = shadow.querySelector(".chat-launcher");` 등)는 그대로 둔다.

- [ ] **Step 6: `index.html`을 한 줄 스크립트로 단순화 (자체 마운트 증명)**

`src/main/resources/static/chat-widget/index.html`에서 `<div id="contrabass-chat"></div>`와
`<template id="cc-chrome">...</template>` 블록(32~69행)을 삭제하고, `</main>` 다음을 아래로 교체:

```html
    </main>

    <script type="module" src="./chat-widget.js"></script>
  </body>
</html>
```

- [ ] **Step 7: 브라우저로 수동 확인 (Node 테스트로 커버 불가 — DOM/fetch 필요)**

Run: `cd src/main/resources/static/chat-widget && python3 -m http.server 8790` 후 브라우저로
`http://localhost:8790/`을 열어 우측 하단에 "Chat" 런처가 뜨는지, 클릭 시 패널이 열리는지 확인.
(백엔드 미기동 상태라 질문 전송은 에러 버블로 끝나는 게 정상 — 마크업/마운트만 확인.)

- [ ] **Step 8: 커밋**

```bash
git add src/main/resources/static/chat-widget/render/chrome.js \
        src/main/resources/static/chat-widget/test/chrome.test.mjs \
        src/main/resources/static/chat-widget/chat-widget.js \
        src/main/resources/static/chat-widget/index.html
git commit -m "feat(chat-widget): 위젯이 스스로 마운트 — 호스트 페이지의 템플릿 마크업 불필요"
```

### Task 6: `window.CONTRABASS_CHAT_USER_ID`/`PROJECT`를 매 전송 시점마다 재조회

**Files:**
- Create: `src/main/resources/static/chat-widget/render/context.js`
- Create: `src/main/resources/static/chat-widget/test/context.test.mjs`
- Modify: `src/main/resources/static/chat-widget/chat-widget.js`

**Interfaces:**
- Produces: `resolveUserId(win, fallback): string`, `resolveProject(win): string | undefined`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/main/resources/static/chat-widget/test/context.test.mjs` 신규 작성:

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { resolveUserId, resolveProject } from "../render/context.js";

test("resolveUserId prefers window override when set", () => {
  assert.equal(resolveUserId({ CONTRABASS_CHAT_USER_ID: "u1" }, "fallback-id"), "u1");
});

test("resolveUserId falls back when unset", () => {
  assert.equal(resolveUserId({}, "fallback-id"), "fallback-id");
});

test("resolveUserId falls back when blank string", () => {
  assert.equal(resolveUserId({ CONTRABASS_CHAT_USER_ID: "   " }, "fallback-id"), "fallback-id");
});

test("resolveProject returns value when set", () => {
  assert.equal(resolveProject({ CONTRABASS_CHAT_PROJECT: "AUTOTEST" }), "AUTOTEST");
});

test("resolveProject returns undefined when unset", () => {
  assert.equal(resolveProject({}), undefined);
});

test("resolveProject returns undefined when blank string", () => {
  assert.equal(resolveProject({ CONTRABASS_CHAT_PROJECT: "  " }), undefined);
});
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd src/main/resources/static/chat-widget && npm test`
Expected: FAIL — `render/context.js` 모듈이 없어 import 오류.

- [ ] **Step 3: `render/context.js` 작성**

`src/main/resources/static/chat-widget/render/context.js` 신규 작성:

```js
// 호스트 페이지(포털)가 세팅하는 전역 컨텍스트를 읽는다. 매 질문 전송 시점마다 다시 불려야
// 하므로(사용자가 프로젝트를 전환해도 다음 질문부터 반영), 값을 캐싱하지 않고 그때그때 읽는다.

export function resolveUserId(win, fallback) {
  const value = win.CONTRABASS_CHAT_USER_ID;
  return typeof value === "string" && value.trim() ? value : fallback;
}

export function resolveProject(win) {
  const value = win.CONTRABASS_CHAT_PROJECT;
  return typeof value === "string" && value.trim() ? value : undefined;
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `cd src/main/resources/static/chat-widget && npm test`
Expected: PASS

- [ ] **Step 5: `chat-widget.js`에서 사용 — 매 전송 시점에 재조회**

`chat-widget.js` 상단 import에 추가:

```js
import { resolveUserId, resolveProject } from "./render/context.js";
```

`sendQuestion()` 안의 `fetch` 호출 본문(현재 `body: JSON.stringify({ question, userId: state.userId })`)을
교체:

```js
      const response = await fetch(`${getApiBase()}/api/chat`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          question,
          userId: resolveUserId(window, state.userId),
          project: resolveProject(window),
        }),
      });
```

(`state.userId`는 `getOrCreateId`로 만든 기존 폴백 — 그대로 유지. `resolveUserId`/`resolveProject`는
`window.CONTRABASS_CHAT_*`가 있을 때만 그 값을 우선한다.)

- [ ] **Step 6: 전체 위젯 테스트 재확인**

Run: `cd src/main/resources/static/chat-widget && npm test`
Expected: PASS (전체 그린 — dom/dispatch/format/각 위젯 렌더러/xss/chrome/context)

- [ ] **Step 7: 커밋**

```bash
git add src/main/resources/static/chat-widget/render/context.js \
        src/main/resources/static/chat-widget/test/context.test.mjs \
        src/main/resources/static/chat-widget/chat-widget.js
git commit -m "feat(chat-widget): userId/project를 매 질문 전송 시점마다 window에서 재조회"
```

### Phase 4 검수

**Claude 검수(자동):** Task 5·6의 `npm test` 전체 그린이 증거.

**사용자 검수(브라우저 필요):**
- [ ] `python3 -m http.server 8790`로 `index.html`을 열어(스크립트 태그 한 줄뿐인 상태) 위젯이 정상적으로
  뜨는지, 기존과 똑같이 동작하는지(회귀 없음).
- [ ] 브라우저 콘솔에서 `window.CONTRABASS_CHAT_USER_ID = "u1"; window.CONTRABASS_CHAT_PROJECT = "AUTOTEST";`를
  실행한 뒤 위젯으로 질문을 보내고, Network 탭에서 요청 본문에 `"userId":"u1","project":"AUTOTEST"`가
  실리는지 확인. (Phase 1~3이 완료된 로컬/VM 백엔드와 붙여서 실제 응답까지 확인하면 더 좋음.)
- [ ] 콘솔에서 두 변수를 지운(또는 재로드) 상태로 다시 질문을 보내 기존 폴백(랜덤 UUID, project 없음)으로
  여전히 동작하는지.

세 항목 확인되면 Phase 5로.

---

## Phase 5 — `remote-contrabass-admin`: 포털에 위젯 주입

**DoD:** `remote-contrabass-admin`이 마운트되면(Host 레포 무수정) 위젯이 자동으로 뜨고, 포털의 로그인
사용자/선택된 프로젝트가 `window.CONTRABASS_CHAT_USER_ID`/`PROJECT`로 반영된다. 프로젝트를 전환하면
다음 질문부터 새 프로젝트가 반영된다.

> 이 Phase는 `remote-contrabass-admin` 레포(`/Users/rhotaehee/Desktop/lab_repo/remote-contrabass-admin`)에서
> 작업한다. 명령은 그 디렉터리에서 실행한다.

### Task 7: `useChatWidgetEmbed` — 순수 로직(테스트 가능) 분리

**Files:**
- Create: `src/hooks/chat/useChatWidgetEmbed.ts`
- Create: `tests/hooks/chat/useChatWidgetEmbed.test.ts`

**Interfaces:**
- Produces: `injectChatWidget(win: Window, doc: Document, apiBase: string): void`,
  `applyChatWidgetContext(win: Window, userId: string | undefined, project: string | undefined): void`
  (Task 8이 `ContrabassApp.vue`에서 소비)

이 함수들은 순수하게 `window`/`document`를 인자로 받는다 — Module Federation(`useUserInfoStore`)이나
Pinia(`ProviderStore`)를 직접 참조하지 않는다. 그래야 이 리포의 전역 목(`tests/setup.ts`의
`ProviderStore`/federation 목)과 무관하게, 그리고 `ContrabassApp.vue` 자체가 커버리지에서 제외된 것과도
무관하게 독립적으로 테스트할 수 있다.

- [ ] **Step 1: 실패하는 테스트 작성**

`tests/hooks/chat/useChatWidgetEmbed.test.ts` 신규 작성:

```ts
import { beforeEach, describe, expect, it } from 'vitest';
import { applyChatWidgetContext, injectChatWidget } from '@/hooks/chat/useChatWidgetEmbed';

describe('injectChatWidget', () => {
  beforeEach(() => {
    document.querySelectorAll('script[data-contrabass-chat]').forEach((el) => el.remove());
  });

  it('appends a module script pointing at the given API base', () => {
    injectChatWidget(window, document, 'https://ragbot.example');

    const script = document.querySelector('script[data-contrabass-chat]');
    expect(script?.getAttribute('src')).toBe('https://ragbot.example/chat-widget/chat-widget.js');
    expect(script?.getAttribute('type')).toBe('module');
  });

  it('does not inject twice', () => {
    injectChatWidget(window, document, 'https://ragbot.example');
    injectChatWidget(window, document, 'https://ragbot.example');

    expect(document.querySelectorAll('script[data-contrabass-chat]').length).toBe(1);
  });

  it('sets the widget API base on window', () => {
    injectChatWidget(window, document, 'https://ragbot.example');

    expect((window as any).CONTRABASS_CHAT_API_BASE).toBe('https://ragbot.example');
  });
});

describe('applyChatWidgetContext', () => {
  it('sets userId and project on window', () => {
    const win = {} as Window & Record<string, unknown>;
    applyChatWidgetContext(win, 'u1', 'AUTOTEST');

    expect(win.CONTRABASS_CHAT_USER_ID).toBe('u1');
    expect(win.CONTRABASS_CHAT_PROJECT).toBe('AUTOTEST');
  });

  it('omits blank project', () => {
    const win = {} as Window & Record<string, unknown>;
    applyChatWidgetContext(win, 'u1', '');

    expect(win.CONTRABASS_CHAT_PROJECT).toBeUndefined();
  });

  it('leaves userId undefined when not provided', () => {
    const win = {} as Window & Record<string, unknown>;
    applyChatWidgetContext(win, undefined, undefined);

    expect(win.CONTRABASS_CHAT_USER_ID).toBeUndefined();
  });
});
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `pnpm vitest run tests/hooks/chat/useChatWidgetEmbed.test.ts`
Expected: FAIL — `@/hooks/chat/useChatWidgetEmbed` 모듈이 없어 import 오류.

- [ ] **Step 3: `useChatWidgetEmbed.ts` 작성**

`src/hooks/chat/useChatWidgetEmbed.ts` 신규 작성:

```ts
/**
 * 챗봇 위젯(ragbot-server의 chat-widget.js)을 포털에 주입하는 순수 로직.
 * Module Federation(useUserInfoStore)·Pinia(ProviderStore)를 직접 참조하지 않는다 —
 * 호출부(ContrabassApp.vue)가 그 값을 읽어 여기 넘긴다. 그래야 이 파일을 store/federation
 * 목 없이 독립적으로 테스트할 수 있다.
 */

const INJECT_MARKER = 'data-contrabass-chat';

export function injectChatWidget(win: Window, doc: Document, apiBase: string): void {
  if (doc.querySelector(`script[${INJECT_MARKER}]`)) return;

  (win as Window & Record<string, unknown>).CONTRABASS_CHAT_API_BASE = apiBase;

  const script = doc.createElement('script');
  script.type = 'module';
  script.src = `${apiBase}/chat-widget/chat-widget.js`;
  script.setAttribute(INJECT_MARKER, 'true');
  doc.body.appendChild(script);
}

export function applyChatWidgetContext(
  win: Window,
  userId: string | undefined,
  project: string | undefined,
): void {
  const target = win as Window & Record<string, unknown>;
  target.CONTRABASS_CHAT_USER_ID = userId;
  target.CONTRABASS_CHAT_PROJECT = project?.trim() ? project : undefined;
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `pnpm vitest run tests/hooks/chat/useChatWidgetEmbed.test.ts`
Expected: PASS (6개)

- [ ] **Step 5: 타입체크**

Run: `pnpm typecheck`
Expected: 기존 오류 외 신규 오류 없음(신규 파일 2개가 타입 오류를 내지 않는지 확인)

- [ ] **Step 6: 커밋**

```bash
git add src/hooks/chat/useChatWidgetEmbed.ts tests/hooks/chat/useChatWidgetEmbed.test.ts
git commit -m "feat(chat): 챗봇 위젯 주입/컨텍스트 세팅 순수 로직 추가"
```

### Task 8: `ContrabassApp.vue`에 연결

**Files:**
- Modify: `src/ContrabassApp.vue`

**Interfaces:**
- Consumes: `injectChatWidget`, `applyChatWidgetContext` (Task 7), `useUserInfoStore()`(기존,
  `getUserId`/`getEmail`/`loaded`), `ProviderStore()`(기존, `getSelectedProjectId`)

> `src/ContrabassApp.vue`는 이 레포의 커버리지/마운트 테스트에서 이미 제외되어 있다
> (`vitest.config.ts`의 `coverage.exclude` — "federation host 부트스트랩 셸"). 이 Task의 배선은 순수
> 로직(Task 7)을 얇게 호출하는 코드라 자동 테스트를 새로 만들지 않는다 — 검증은 아래 "사용자 검수"의
> 실제 로그인 확인으로 한다. 이는 임의 누락이 아니라 이 레포의 기존 관례를 따른 것이다.

- [ ] **Step 1: `onMounted`에 위젯 주입 + 컨텍스트 배선 추가**

`src/ContrabassApp.vue`의 `<script setup lang="ts">` 블록 상단 import에 추가(기존
`import { ProviderStore } from '@/stores/provider/ProviderStore';` 근처):

```ts
import { useUserInfoStore } from '@/hooks/store/useUserInfoStore';
import { applyChatWidgetContext, injectChatWidget } from '@/hooks/chat/useChatWidgetEmbed';
```

기존 `const providerStore = ProviderStore();` 다음 줄에 추가:

```ts
const { getUserId, getEmail, loaded: userInfoLoaded } = useUserInfoStore();
const chatApiBase = import.meta.env.VITE_RAGBOT_CHAT_API_BASE as string | undefined;

const syncChatWidgetContext = () => {
  applyChatWidgetContext(window, getUserId.value ?? getEmail.value, providerStore.getSelectedProjectId);
};
```

기존 `onMounted(async () => { ... })` 블록의 **첫 줄**(현재
`window.addEventListener(RBAC_SSE_EVENTS.RBAC_CHANGED, handleRbacChanged);`) 바로 앞에 추가:

```ts
  if (chatApiBase) {
    injectChatWidget(window, document, chatApiBase);
    syncChatWidgetContext();
  }

```

`onMounted` 블록이 끝난(닫는 `});`) 바로 다음 줄에 `watch` 추가:

```ts
watch(
  [userInfoLoaded, () => providerStore.getSelectedProjectId],
  () => {
    if (chatApiBase) syncChatWidgetContext();
  },
);
```

(`watch`는 이미 `vue`에서 이 파일 상단에 import돼 있을 가능성이 높다 — 없다면
`import { watch } from 'vue';`를 다른 vue 코어 import와 함께 추가한다.)

- [ ] **Step 2: 로컬 개발용 `.env`/`.env.localhost`에 `VITE_RAGBOT_CHAT_API_BASE` 추가**

이 레포는 모드별 `.env.*` 파일(`.env`, `.env.localhost`, `.env.dev`, `.env.stg`, `.env.tst`, `.env.bf-tst`,
`.env.prd`)을 쓴다(`vite --mode <name>` → `package.json`의 `local`/`serve`/`dev`/`build:*` 스크립트).
운영·스테이징 챗봇 서버 주소는 아직 확정되지 않았으므로(스펙 §7 열린 리스크), 이번 Task는 로컬 개발에
쓰는 두 파일에만 값을 넣는다 — `.env.prd`/`.env.stg`/`.env.tst`/`.env.bf-tst`는 건드리지 않는다.

`.env`와 `.env.localhost` 양쪽 끝에 한 줄씩 추가:

```
VITE_RAGBOT_CHAT_API_BASE=http://localhost:8080
```

- [ ] **Step 3: 타입체크 + 린트**

Run: `pnpm typecheck`
Expected: 신규 오류 없음

Run: `pnpm lint:check`
Expected: 신규 오류 없음(포맷은 기존 파일 스타일을 그대로 따름 — 이 Step에서 새로 깨지는 규칙이 있으면
`pnpm lint`로 자동수정 후 diff가 이 Task 범위를 벗어나지 않는지 확인)

- [ ] **Step 4: 전체 테스트 스위트 회귀 확인**

Run: `pnpm test:run`
Expected: 기존 테스트 전체 그린(특히 `ContrabassApp.vue`를 마운트하는 테스트가 없으므로 이 변경으로
깨질 자동 테스트는 없어야 함 — 있다면 이 Task의 import/배선이 다른 곳에 영향을 준 것이므로 원인을 확인)

- [ ] **Step 5: 커밋**

```bash
git add src/ContrabassApp.vue .env .env.localhost
git commit -m "feat: 로그인 사용자/선택 프로젝트 컨텍스트로 챗봇 위젯 자동 주입"
```

### Phase 5 검수

**Claude 검수(자동):** Task 7의 `vitest` 통과 + Task 8의 타입체크/린트/기존 스위트 회귀 없음이 증거.

**사용자 검수(실 로그인 필요 — Claude가 대신 할 수 없음):**
- [ ] `VITE_RAGBOT_CHAT_API_BASE`를 로컬 챗봇 서버(Phase 1~3 반영된 `./gradlew bootRun`, CORS에 포탈 dev
  origin 등록)로 설정하고 `pnpm dev`(또는 `pnpm local`)로 포털을 띄워 실제 로그인 후 화면에 위젯이 자동으로
  뜨는지.
- [ ] 위젯으로 질문했을 때 Network 탭에서 `userId`가 로그인한 실제 사용자 식별자로, `project`가 현재 선택된
  프로젝트로 실리는지.
- [ ] 포털에서 다른 프로젝트로 전환한 뒤 새 질문을 보내면 `project`가 바뀐 값으로 반영되는지.
- [ ] "쿼터 얼마나 썼어?"처럼 프로젝트를 언급하지 않는 QUOTA 질문이 되묻지 않고 현재 프로젝트로 답이 나오는지
  (Phase 2 백엔드 폴백 + 이 Phase의 컨텍스트 전달이 합쳐진 최종 확인).
- [ ] 같은 계정으로 짧은 시간에 6번 이상 질문해 분당 5회 레이트리밋이 걸리는지, 그리고 그게 다른 사용자에게는
  영향 없는지(다른 브라우저/시크릿창으로 다른 계정 로그인해 확인).

다섯 항목 확인되면 전체 기능이 끝난다.

---

## 최종 참고

- 스펙 §7의 "네트워크 도달 가능성"(pod ↔ VM)과 "Host 서빙 도메인 → CORS allowed-origins" 확인은 이
  계획의 Phase 3·5 사용자 검수에서 실측하게 되지만, VM에 실제로 배포하기 전에는 인프라팀 확인이 여전히
  필요하다 — 이 계획은 로컬~로컬, 로컬~VM(고정 IP+포트 오픈 가정) 조합까지만 검증한다.
- Host 레포(`hostBootFactory`/`hostMaestro`) 쪽 변경은 이 계획에 없다(스펙 §2 비목표) — 필요해지면 별도
  계획으로 분리한다.
