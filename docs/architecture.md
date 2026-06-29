# architecture.md — ragbot-server 설계

> 어떻게 만드는가. 요구사항은 [`requirements.md`](requirements.md).
> 1차 계획: [`phase1/plan.md`](phase1/plan.md) · 2차 계획: [`phase2/plan.md`](phase2/plan.md)

## 1. 기술 스택

- **Kotlin** · **JDK 21(LTS, Gradle toolchain)** · **Spring Boot 3.5.14** · **Spring AI 1.1.7** ·
  Gradle(Kotlin DSL, wrapper 8.14.5)
- Spring AI: OpenAI(**Chat + Embedding + Moderation**), PgVectorStore
  - starter: `spring-ai-starter-model-openai`, `spring-ai-starter-vector-store-pgvector`
    (+ `spring-boot-starter-jdbc`), BOM `org.springframework.ai:spring-ai-bom:1.1.7`
- **데이터 접근: `JdbcTemplate`**(JPA 미사용 — pgvector `vector`/`<=>`는 SQL 직접이 자연스럽고,
  documents 검색은 `PgVectorStore`가 내부적으로 JdbcTemplate 사용)
- **회복탄력성 — Resilience4j로 통합**(M4): 아웃바운드 `Retry`(429/5xx 백오프)+`CircuitBreaker`+
  `TimeLimiter`(OpenAI 호출 격리), 인바운드 `RateLimiter`(**사용자별** 빈도 제한 — `RateLimiterRegistry`를 Slack `user_id`로 키잉, 전역 단일 인스턴스 아님).
  Spring AI 내장 재시도는 **비활성**(`spring.ai.retry.max-attempts=1`)해 이중 재시도 방지.
- **콘텐츠 가드레일**: 로컬 금칙어 사전 + **OpenAI Moderation**(`omni-moderation-latest`).
- Slack: `com.slack.api:bolt` + `bolt-socket-mode`(M5, Socket Mode 고정)
- DB: PostgreSQL + **pgvector**(사내 내부망)
- 관측: Spring Boot Actuator (+ Micrometer, Resilience4j 메트릭)

> M4(회복탄력성)·M5(Slack) 의존성은 `build.gradle.kts`에 주석으로 표시 — 해당 Phase에서 활성화.

## 2. 패키지 구조 (`com.okestro.ragbot`, `src/main/kotlin`)

**Layered 4-tier**(헥사고날 아님)를 **도메인(능력)별 모듈** 안에 둔다. 모듈 내부 계층:
`interfaces`(request/response) → `application`(command/result + 서비스 인터페이스) → `domain`
(모델·정책) / `infrastructure`(외부 기술 구현). DIP는 *infrastructure 구현이 application 인터페이스를
구현하고 DI로 주입*되는 선까지.

```
com.okestro.ragbot
├── RagbotApplication.kt        # @SpringBootApplication 진입점
├── chat/                       # 유스케이스: 파이프라인 오케스트레이션(단일 진입점)
│   ├── interfaces/             #   ChatController(/api/chat), ChatRequest/Response
│   ├── application/            #   ChatService(오케스트레이터), ChatCommand/Result
│   └── domain/                 #   Question, QuestionVector, RetrievedChunk, Source, Answer
├── embedding/
│   ├── application/            #   EmbeddingService (인터페이스)
│   └── infrastructure/         #   OpenAiEmbeddingClient
├── retrieval/
│   ├── application/            #   DocumentSearchService (인터페이스)
│   ├── domain/                 #   RetrievalPolicy(min-score·0건 차단)
│   └── infrastructure/         #   PgVectorDocumentSearch
├── cache/                      # ⏸ 고도화 보류(Phase 4) — 1차 미생성
│   ├── application/            #   SemanticCacheService (인터페이스)
│   ├── domain/                 #   CacheEntry, CacheMatchPolicy(>0.95)
│   └── infrastructure/         #   JdbcSemanticCacheRepository
├── generation/
│   ├── application/            #   AnswerGenerationService (인터페이스), PromptTemplates
│   └── infrastructure/         #   OpenAiChatClient (ChatClient.Builder)
├── guard/
│   ├── application/            #   ModerationService (인터페이스)
│   ├── domain/                 #   InputValidation(빈입력·길이), ContentPolicy(금칙어)
│   └── infrastructure/         #   OpenAiModerationClient
├── slack/
│   ├── interfaces/             #   SocketModeRunner(수신·ack)
│   ├── application/            #   SlackResponseService (인터페이스)
│   └── infrastructure/         #   SlackResponder (chat.postMessage)
├── routing/                    # ✅ 완성 (미배선 — R4에서 DefaultChatService에 연결)
│   ├── interfaces/             #   RoutingCli (./gradlew routingCli 수동확인)
│   ├── application/            #   QuestionRouter(포트)·LlmQuestionRouter·LlmClient·RoutingPrompts
│   ├── domain/                 #   Route(DOC/RESOURCE/CLARIFY)·RouteDecision·ConversationMessage·RoutingPolicy
│   └── infrastructure/         #   OpenAiRouterLlmClient (strict json_schema + Resilience4j)
├── resource/                   # 🚧 R1 완성, R2~R4 예정 — docs/future/resource-prometheus-path.md
│   ├── interfaces/             #   ResourceCli (./gradlew resourceCli 수동확인)
│   ├── application/            #   MetricQueryExtractor(포트)·LlmMetricQueryExtractor·ResourcePrompts
│   │                           #   (R2+) MetricCatalog·PromQlBuilder·PrometheusClient(포트)·ResourceAnswerTemplate·ResourceService
│   ├── domain/                 #   MetricPattern·ResourceQuery·ResourceExtraction(sealed)
│   │                           #   (R2+) MetricCatalogEntry·MetricSample·PromQlBuilder
│   └── infrastructure/         #   (R3+) HttpPrometheusClient (RestClient + Resilience4j 'prometheus')
└── common/
    ├── config/                 #   OpenAI/PgVectorStore/Async/Slack/Resilience4j 빈, AppProperties
    └── resilience/             #   Resilience4j 데코레이터·설정
```

- **단일 진입점은 `chat.application.ChatService`** — Slack 인그레스(`slack/interfaces`)와 REST
  (`chat/interfaces`)가 모두 이 하나를 호출(채널만 다름).
- `chat.application`은 각 능력 모듈의 **application 인터페이스**에만 의존(외부 기술은 모름).
- 능력 모듈이 자기 **서비스 인터페이스를 소유**하고 `infrastructure`가 구현(DI 주입).
- `interfaces` 계층은 인바운드 진입점이 있는 `chat`·`slack`에만, `command/result`는 `chat`에만.

> 현재 레포에는 `RagbotApplication.kt`만 존재(M0). 각 모듈/계층은 M1~M5에서 추가한다(`plan.md`).

## 3. 컴포넌트 규약

- **embedding**: `OpenAiEmbeddingModel`, `text-embedding-3-small`(1536), **OpenAI API 호출**.
  `EmbeddingService`에서 질문 1회 임베딩 후 파이프라인에 전달(재계산 금지).
- **retrieval**: Spring AI `PgVectorStore` 표준 스키마, 거리=cosine(`vector_cosine_ops`),
  `topK(5)`. 출처는 `metadata`의 `title`/`page`. `min-score` 미달·0건 → "관련 문서 없음".
- **cache**(겸 질의 로그) ⏸ **고도화 보류(Phase 4) — 1차 미구현**: **이 앱 소유 테이블**, `JdbcTemplate` 조회/저장. 히트 기준
  **cosine > 0.95**. 히트 시 저장 답변+출처 반환. **모든 질의 저장**(히트/미스 무관).
  참고 스키마: `question, q_embedding(vector 1536), answer, sources(jsonb), cache_hit, user_id,
  created_at` + HNSW(cosine). DDL은 본 레포 보관(예: `src/main/resources/db/`).
- **generation**: `OpenAiChatModel` 기반 `ChatClient`(`ChatClient.Builder`). provider 교체
  여지(Claude 등)를 위해 자동구성 끄고(`spring.ai.chat.client.enabled=false`) 수동 빈 구성.
  프롬프트 = 시스템 규칙 + top-5 청크 + 질문. (RAG 자동 `QuestionAnswerAdvisor`는 참고만 —
  캐시 선조회·저유사도 차단·출처 포맷을 직접 제어해야 하므로 명시 조립.)
- **guard**: 입력 검증(빈입력·길이) + 레이트리밋(Resilience4j `RateLimiter`) + 콘텐츠 필터
  (로컬 금칙어 → 애매하면 `OpenAiModerationModel`). 차단 시 임베딩·LLM **0회**, 안내 응답.
- **slack**: Socket Mode 고정(1차 — 아웃바운드 WebSocket, **공개 URL·서명검증 불필요**). 수신 즉시 ack(3초 제약) 후 **비동기**(가상스레드)
  파이프라인 실행 → 완료 시 원 스레드에 `chat.postMessage`로 답변+출처 게시.

## 4. 설정 (외부화 — 하드코딩 금지)

튜닝값은 `src/main/resources/application.yml`의 `app.*`(단일 소스, `@ConfigurationProperties`
POJO `AppProperties`로 매핑) + Spring AI 표준 키(`spring.ai.*`). 주요 키:

| 키 | 의미 | 초기값 |
| --- | --- | --- |
| `app.openai.chat-model` | 생성 모델 | `gpt-4o-mini` |
| `app.openai.embedding-model` / `-dim` | 임베딩 모델/차원 | `text-embedding-3-small` / `1536` |
| `app.retrieval.top-k` / `min-score` | 검색 청크 수 / 유사도 하한 | `5` / (2차) |
| `app.retrieval.table` | documents 테이블명(읽기 전용) | `documents` |
| `app.cache.cosine-threshold` | 캐시 히트 기준 | `0.95` (고도화 보류) |
| `app.guard.max-question-len` / `rate-per-min` | 입력 길이 / **사용자별** 분당 한도 | `1000` / `5` |
| `app.guard.moderation.enabled` / `.model` | Moderation 사용/모델 | `true` / `omni-moderation-latest` |
| `app.guard.banned-words-path` | 로컬 금칙어 사전 | `classpath:banned-words.txt` |
| `spring.ai.retry.max-attempts` | 내장 재시도 | `1`(비활성) |
| `resilience4j.{retry,circuitbreaker,timelimiter,ratelimiter}.*` | 회복탄력성 | (M4 설정) |
| `app.router.*` | 질문 라우터 (model·temperature·min-confidence·history-turns) | `gpt-4o-mini` / `0.0` / `0.5` / `2` |
| `app.resource.*` | RESOURCE 경로 (extraction-model·min-confidence·default-window·default-top-n·prometheus.*) | `gpt-4o-mini` / `0.5` / `5m` / `5` |

시크릿은 **환경변수만**: `OPENAI_API_KEY`(Moderation 재사용), `SLACK_BOT_TOKEN`,
`SLACK_APP_TOKEN`, DB 접속정보(`SPRING_DATASOURCE_URL`/`DB_*`), `PROMETHEUS_URL`(RESOURCE 경로).
