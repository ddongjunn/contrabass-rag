# 질문 라우터 (Question Router) 설계

> 상태: 확정(2026-06-16) · 범위: 라우터 모듈 + 테스트 하니스만. RAG/리소스 조회 경로는 **이번에 구현하지 않음**.

## 1. 목적

사용자 질문을 4개 라우트로 분류하는 **독립 모듈**. 더 큰 챗봇 백엔드의 1단계로, 공통 가드(RateLimit/금칙어/Moderation) **다음**, RAG·리소스 경로 **앞**에 위치한다(아키텍처 다이어그램 기준). 이번 단계에선 라우터와 그 검증 수단까지만 만든다.

| Route | 의미 |
|---|---|
| `DOC` | 개념·사용법·가이드 등 문서(RAG) 기반으로 답할 질문 |
| `RESOURCE` | 실제 인프라 상태·지표·리소스를 조회해야 하는 질문 (DB / Prometheus) |
| `BOTH` | 두 의도가 섞인 질문 |
| `CLARIFY` | 맥락만으로 분류가 모호해 되물어야 하는 경우 |

## 2. 설계 결정 (확정)

- **언어/스택**: Kotlin. 기존 레포 관례(`com.okestro.ragbot`, Spring Boot 3.5.14 + Spring AI 1.1.7, 도메인별 `interfaces/application/domain/infrastructure` 4계층)를 따른다. (요청서의 "Java 21"은 일반 표현으로 해석 — 단일 레포 Kotlin 일관성 유지가 CLAUDE.md 불변식과 부합.)
- **LLM 호출 경로**: 기존 레포처럼 Spring AI `ChatClient`/`ChatModel`을 통해 OpenAI 호출. 원시 OpenAI HTTP SDK는 쓰지 않는다.
- **구조화 출력**: Spring AI `ResponseFormat(JSON_SCHEMA, strict)`로 네이티브 Structured Outputs 사용 시도. **구현 시점에 1.1.7의 strict API 노출을 실제 확인**하고, 깔끔히 안 되면 `JSON_OBJECT` + 스키마를 프롬프트에 명시하는 방식으로 폴백한다. 어느 쪽이든 라우터의 "파싱 실패 → CLARIFY" 경로가 최종 안전망.
- **라우팅 모델**: 작고 빠른 모델(`gpt-4o-mini` 등). 기존 `ChatModel` 빈을 재사용하되 **호출당 옵션 오버라이드**(model, temperature=0, responseFormat)로 채팅 모델과 독립 설정. 모델명은 `application.yml`로 교체 가능.
- **컨텍스트 의존성**: 라우터는 마지막 질문 1개가 아니라 **최근 N턴**(`historyTurns`, 기본 **2**)을 입력으로 받는다. ("1번 인스턴스 상세 알려줘"가 직전 인스턴스 목록 맥락으로 RESOURCE가 되는 케이스를 위함.)
- **폴백**: JSON 파싱 실패·빈 응답 → `CLARIFY`. `confidence < minConfidence`(기본 0.5) → `CLARIFY`.
- **로깅**: 모든 결정에 `route · confidence · reason · latencyMs` 로깅(추후 메트릭).
- **격리**: 라우터는 이번에 `ChatService`/가드 파이프라인에 **연결하지 않는다**. BOTH/CLARIFY 다운스트림 처리도 만들지 않는다.
- **튜닝값**: 하드코딩 금지. `AppProperties` + `application.yml`의 `app.router.*`.

## 3. 패키지 / 클래스 구조

새 도메인 모듈 `com.okestro.ragbot.routing`.

```
routing/
├─ domain/                         # 순수 모델·정책 (프레임워크 의존 없음)
│  ├─ Route.kt                     # enum: DOC, RESOURCE, BOTH, CLARIFY
│  ├─ RouteDecision.kt             # data class(route: Route, confidence: Double, reason: String)
│  ├─ ConversationMessage.kt       # data class(role: Role, content: String); enum Role { USER, ASSISTANT }
│  └─ RoutingPolicy.kt             # 순수 함수: confidence<minConfidence → CLARIFY 폴백 규칙
│
├─ application/
│  ├─ QuestionRouter.kt            # 인터페이스: route(history: List<ConversationMessage>): RouteDecision
│  ├─ LlmQuestionRouter.kt         # 구현: 프롬프트 구성 → LlmClient 호출 → JSON 파싱 → RoutingPolicy 적용
│  │                               #        파싱실패/저신뢰 → CLARIFY, 로깅(route·confidence·reason·latencyMs)
│  ├─ LlmClient.kt                 # 포트 인터페이스: complete(req: LlmRequest): String (원시 JSON 본문 반환)
│  └─ RoutingPrompts.kt            # system 지시문 + few-shot (generation/PromptTemplates 패턴)
│
└─ infrastructure/
   └─ OpenAiRouterLlmClient.kt     # Spring AI ChatClient + ResponseFormat(JSON_SCHEMA, strict)
                                   #   model=app.router.model, temperature=0, Resilience4j @Retry/@CircuitBreaker("openai")

interfaces/  (또는 routing/interfaces)
└─ RoutingCli.kt                  # 독립 main() — Spring 전체 부팅 없이 ChatModel+라우터만 조립, stdin 질문 → 결과 출력
```

테스트(`src/test/.../routing/`):
```
StubLlmClient.kt                  # 생성자/필드로 받은 static JSON 문자열을 그대로 반환 (StubChatService 패턴)
LlmQuestionRouterTest.kt          # 로직 검증: 정상 매핑 + 저신뢰→CLARIFY + 파싱실패→CLARIFY + 맥락의존 케이스
RoutingAccuracyTest.kt            # 선택: @EnabledIfEnvironmentVariable("OPENAI_API_KEY"), 기본 off — 실제 분류 정확도
```

### 두 인터페이스 이유
`QuestionRouter`는 교체 가능한 라우터 포트. `LlmClient`는 LLM 호출 seam으로, 파싱/폴백 로직을 **스텁으로 단위 테스트**(API 호출 0, 키 불필요)하게 해준다. `LlmClient`가 파싱된 빈이 아니라 **원시 JSON 문자열**을 반환하는 것은 의도적 — "깨진 응답 → CLARIFY" 경로를 정직하게 테스트하기 위함.

## 4. Few-shot 세트 (`RoutingPrompts`, 한국어 — 사내 봇)

6개. 4개 라우트 + 맥락 의존 + 대비쌍(DOC↔RESOURCE)을 모두 커버.

| # | History 맥락 | User 질문 | Route | reason 요지 |
|---|---|---|---|---|
| 1 | — | "RAG에서 임베딩 모델은 어떻게 설정하나요?" | **DOC** | 개념·설정 가이드 |
| 2 | — | "지금 prod 클러스터 CPU 사용률 보여줘" | **RESOURCE** | 실시간 지표 조회 |
| 3 | assistant가 직전 턴에 인스턴스 **목록** 제시 | "1번 인스턴스 상세 알려줘" | **RESOURCE** | 직전 턴 목록 맥락 의존 |
| 4 | — | "Prometheus 알람 설정법 알려주고, 지금 떠 있는 알람도 보여줘" | **BOTH** | 방법(문서)+현재상태(리소스) |
| 5 | — (맥락 없음) | "그거 어떻게 해?" | **CLARIFY** | 지시 대상 불명확 |
| 6 | — | "메모리 지금 얼마나 쓰고 있어?" | **RESOURCE** | (5/유사 "늘리는 법"=DOC과 대비) |

## 5. 설정 (`AppProperties` + `application.yml`)

```kotlin
// AppProperties 에 추가
data class Router(
    val model: String = "gpt-4o-mini",   // 라우팅용 작고 빠른 모델 (교체 가능)
    val temperature: Double = 0.0,
    val minConfidence: Double = 0.5,      // 미만 → CLARIFY 폴백
    val historyTurns: Int = 2,            // 라우터가 입력으로 받는 최근 메시지 수
)
```
```yaml
# application.yml
app:
  router:
    model: gpt-4o-mini
    temperature: 0.0
    min-confidence: 0.5
    history-turns: 2
```

## 6. 테스트 / 성공 기준

- **기본 테스트(항상 실행, 키 불필요)**: `LlmQuestionRouterTest`가 `StubLlmClient`로 라우터의 **배선·파싱·폴백** 검증.
  - 정상 JSON → 해당 route 매핑
  - `confidence < minConfidence` → CLARIFY
  - 깨진/빈 JSON → CLARIFY
  - 맥락 의존 케이스(직전 턴 포함) 전달 형태 검증
  - → `./gradlew test` 그린 (네트워크·API 키 없이)
- **선택 정확도 테스트(기본 off)**: `RoutingAccuracyTest`, `@EnabledIfEnvironmentVariable("OPENAI_API_KEY")` — 실제 질문→기대 라우트.
- **수동 CLI**: 독립 `main()` 실행 → 질문 타이핑 → `route / confidence / reason / latency` 출력.
- **README**: API 키 환경변수 설정법 + 테스트/CLI 실행법(짧게).

### 한계(명시)
stub 기반 테이블은 라우터의 **로직**을 검증하지, LLM의 **분류 정확도**를 검증하지 않는다. 정확도는 선택 정확도 테스트(실 API)에서만 확인 가능.

## 7. 범위 밖 (이번에 안 함)
- RAG 파이프라인·리소스(DB/Prometheus) 조회 경로 구현
- 라우터를 `ChatService`/가드 파이프라인에 연결
- BOTH/CLARIFY 다운스트림 처리
- 자연어→조회조건 변환
