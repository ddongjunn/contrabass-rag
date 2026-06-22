# 핸드오프 — 질문 라우터 통합 & RESOURCE(Prometheus) 경로

> 작성 2026-06-22 · 대상: 다음 작업자
> 선행 산출물: 설계 [`../superpowers/specs/2026-06-16-question-router-design.md`](../superpowers/specs/2026-06-16-question-router-design.md) ·
> 구현 계획 [`../superpowers/plans/2026-06-16-question-router.md`](../superpowers/plans/2026-06-16-question-router.md)
> 전체 맥락: [`../requirements.md`](../requirements.md) · [`../architecture.md`](../architecture.md) · [`../plan.md`](../plan.md)

이 문서는 **지금까지 한 일**, **확정된 결정**, **남은 일**을 정리한 인수인계 노트다.
브랜치 `feat/question-router`에 라우터 **모듈만** 독립 구현돼 있고, 아직 실제 파이프라인에 **연결되지 않았다.**

---

## 1. 지금까지 한 일 (브랜치 `feat/question-router`)

질문을 `DOC / RESOURCE / CLARIFY`로 분류하는 **독립 라우터 모듈**을 만들었다. 다이어그램의
`공통 가드 → 질문 라우터 → (DOC 경로 | RESOURCE 경로)`에서 **라우터 박스 하나만** 완성한 상태.

**파일 맵** (`com.okestro.ragbot.routing`):

| 계층 | 파일 | 역할 |
|---|---|---|
| domain | `Route.kt` | enum `DOC / RESOURCE / CLARIFY` |
| domain | `RouteDecision.kt` | `(route, confidence, reason)` |
| domain | `ConversationMessage.kt` | `(role: USER/ASSISTANT, content)` — 라우터 입력 1턴 |
| domain | `RoutingPolicy.kt` | 순수 함수: `confidence < minConfidence` → CLARIFY 폴백 |
| application | `QuestionRouter.kt` | 포트: `route(history): RouteDecision` |
| application | `LlmClient.kt` | LLM 호출 seam(원시 JSON 문자열 반환) + `LlmRequest` |
| application | `RoutingPrompts.kt` | system 지시문 + few-shot 5개 + strict JSON 스키마 |
| application | `LlmQuestionRouter.kt` | **본체**: 프롬프트 구성 → 호출 → 파싱 → 폴백 → 로깅 |
| infrastructure | `OpenAiRouterLlmClient.kt` | Spring AI `ChatClient` + strict `json_schema` + Resilience4j |
| interfaces | `RoutingCli.kt` | 독립 `main()` — Spring 부팅 없이 수동 확인(`./gradlew routingCli`) |

**테스트**: `RoutingPolicyTest`(3), `LlmQuestionRouterTest`(5, 스텁 기반 — 키 불필요) 항상 실행 /
`RoutingAccuracyTest`(실 OpenAI, `@EnabledIfEnvironmentVariable(OPENAI_API_KEY)` — 기본 skip).
`./gradlew test`는 키 없이 그린.

**설정** (`application.yml` `app.router.*`, `AppProperties.Router`):

| 키 | 기본값 | 의미 |
|---|---|---|
| `app.router.model` | `gpt-4o-mini` | 라우팅용 작고 빠른 모델 |
| `app.router.temperature` | `0.0` | 결정성 |
| `app.router.min-confidence` | `0.5` | 미만이면 CLARIFY 폴백 |
| `app.router.history-turns` | `2` | LLM에 넘기는 최근 메시지 수(현재 질문 포함) |

---

## 2. 확정된 결정 (왜 이렇게 했는지)

- **라우팅은 LLM 호출 1회**(텍스트 분류). 임베딩이 아니다. 자유 자연어 + 맥락 의존 후속질문
  ("1번 상세 알려줘")을 견고하게 가르려면 규칙 기반으론 부족해 LLM을 택했다.
  → 비용: 질문당 라우팅 LLM 1회가 **추가**된다(불변식 4의 "호출 전 차단"과 약한 긴장 관계. §5 참고).
- **라우트는 DOC / RESOURCE / CLARIFY 3개.** `BOTH`는 실사용 안 해서 제거(커밋 `77fdfbb`).
  실 라우트는 **DOC(RAG)·RESOURCE(Prometheus)** 2개이고, **CLARIFY는 폴백 겸 되물음**으로 유지
  (파싱 실패·저신뢰·알 수 없는 값 → CLARIFY).
- **최근 대화 출처 = Slack 스레드.** 별도 저장소를 만들지 않는다. 라우터는 히스토리를 **인자로 받기만** 하고
  저장/조회하지 않는다 — 현재 프로젝트엔 유저별 대화 저장소가 **없다**(`ChatCommand`는 `userId`+`question` 단건).
- **strict structured output 사용 가능 확인됨**: Spring AI 1.1.7이 `ResponseFormat.JsonSchema.builder().strict(true)`를
  실제 지원(jar 바이트코드 확인). 그래도 CLARIFY 파싱 가드는 안전망으로 유지.

---

## 3. 남은 일 (다음 작업자)

세 덩어리. 의존 순서: **(A) 배선 → (B) Slack 히스토리 → (C) Prometheus 경로**.
A·B는 작고, C가 큰 작업이다. 각각 별도 spec/plan으로 진행 권장(`process.md` 규약).

### (A) 라우터를 파이프라인에 배선
- 현재 `chat/application/DefaultChatService.kt`는 `rateLimit → inputGuard → 임베딩 → 검색 → 생성`으로,
  **사실상 항상 DOC 경로**다. 여기 가드 직후에 라우터를 끼운다:
  `rateLimit → inputGuard → **router.route(history)** → 분기`.
- 분기:
  - `DOC` → 기존 임베딩→검색→생성 경로(그대로).
  - `RESOURCE` → 새 Prometheus 경로(C).
  - `CLARIFY` → 되물음 메시지 반환, **이후 유료 호출 없음**(불변식 4 정신 유지).
- `ChatCommand`에 히스토리를 실어야 한다(예: `history: List<ConversationMessage> = emptyList()` 추가).
  REST(`ChatController`)는 스레드가 없으니 빈 히스토리 → 단발성으로 동작(맥락 후속질문 미지원, 허용).

### (B) Slack 스레드 → 히스토리
- `slack/` 모듈(`SocketModeRunner`/`SlackResponder`/`SlackResponseService`)에서 처리.
- bolt API로 해당 스레드(`thread_ts`)의 최근 메시지를 읽어 `ConversationMessage`로 변환
  (봇 메시지 → `ASSISTANT`, 사용자 → `USER`), **마지막 `history-turns`개**만 라우터에 전달.
- 별도 저장소 불필요 — Slack이 이미 보관 중인 스레드를 출처로 쓴다.

### (C) RESOURCE(Prometheus) 경로 — 신규 모듈 (가장 큰 작업)
- 새 도메인 모듈 권장(예: `resource/` 4계층). 흐름: **자연어 → PromQL → Prometheus 쿼리 → 결과 기반 답변.**
- 하위 작업:
  1. 자연어 → PromQL 변환 (LLM 호출, strict 스키마로 쿼리 구조 강제 검토).
  2. Prometheus HTTP API(`/api/v1/query`) 호출 — base URL은 `application.yml`(시크릿이면 env).
  3. 결과 포맷 → 답변(템플릿 또는 LLM 정리).
- **설계 주의(반드시 결정 필요)**: LLM이 PromQL을 자유 생성하면 **존재하지 않는 메트릭/라벨을 환각**할 수 있다.
  안전한 대안 = 허용된 쿼리/메트릭 화이트리스트·템플릿 기반. 자유 PromQL 생성 vs 템플릿은 다음 작업자의 핵심 결정.

---

## 4. 코드 진입점 빠른 참조
- 라우터 호출: `QuestionRouter.route(history: List<ConversationMessage>): RouteDecision`
- 현재 파이프라인: `chat/application/DefaultChatService.kt` (여기 배선)
- Slack 수신: `slack/interfaces/SocketModeRunner.kt`, 응답: `slack/infrastructure/SlackResponder.kt`
- 설정 단일 소스: `common/config/AppProperties.kt` + `application.yml` (하드코딩 금지 — 불변식 7)

## 5. 지킬 불변식 (CLAUDE.md / requirements.md)
- 시크릿은 **환경변수만**, 튜닝값은 **`application.yml`**(`app.router.*` 패턴 따라 `app.resource.*` 등).
- `documents` 테이블은 **읽기 전용**. Prometheus는 외부 HTTP(이 앱이 DB로 소유하지 않음).
- 유료 호출(라우팅·임베딩·생성)은 **필요한 경로에서만**. CLARIFY/가드 차단 시 이후 유료 호출 0회.
- 비용 관찰: 라우팅 LLM 1회 추가분을 로그/메트릭으로 추적(라우터는 이미 `route·confidence·reason·latencyMs` 로깅).
  추후 비용이 문제면 명백한 케이스용 **규칙 기반 fast-path**를 라우터 앞에 둘 수 있음(현재는 미구현, YAGNI).

## 6. 열린 질문
- RESOURCE 경로: 자유 PromQL 생성 vs 화이트리스트/템플릿? (환각 위험 — §3-C)
- REST 엔드포인트는 스레드가 없어 맥락 후속질문 불가 — 그대로 둘지?
- 라우팅 비용(질문당 LLM 1회)이 허용 범위인지, fast-path가 필요한 시점인지?
