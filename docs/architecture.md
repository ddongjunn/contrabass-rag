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

- Prometheus: `org.springframework:spring-web` `RestClient` HTTP GET `/api/v1/query`,
  Resilience4j `prometheus` 인스턴스(Retry + CircuitBreaker), TLS 바이패스 옵션(`ssl-verify: false`)

## 2. 패키지 구조 (`com.okestro.ragbot`, `src/main/kotlin`)

**Layered 4-tier**(헥사고날 아님)를 **도메인(능력)별 모듈** 안에 둔다. 모듈 내부 계층:
`interfaces`(request/response) → `application`(command/result + 서비스 인터페이스) → `domain`
(모델·정책) / `infrastructure`(외부 기술 구현). DIP는 *infrastructure 구현이 application 인터페이스를
구현하고 DI로 주입*되는 선까지.

```
com.okestro.ragbot
├── RagbotApplication.kt        # @SpringBootApplication 진입점
├── chat/                       # 유스케이스: 파이프라인 오케스트레이션(단일 진입점)
│   ├── interfaces/             #   ChatController(/api/chat), ChatRequest(+project)/Response
│   ├── application/            #   ChatService(오케스트레이터), ChatCommand(+history/project)/Result
│   └── domain/                 #   ConversationMessage(role/content) — 스레드 히스토리 타입
│                               #   (routing·resource가 이 타입을 참조; chat이 오케스트레이터)
├── embedding/
│   ├── application/            #   EmbeddingService (인터페이스)
│   └── infrastructure/         #   OpenAiEmbeddingClient
├── retrieval/
│   ├── application/            #   DocumentSearchService (인터페이스)
│   ├── domain/                 #   RetrievedChunk·Source·RetrievalPolicy(min-score·0건 차단)
│   └── infrastructure/         #   PgVectorDocumentSearch
├── cache/                      # ⏸ 고도화 보류(Phase 4) — 미생성
│   ├── application/            #   SemanticCacheService (인터페이스)
│   ├── domain/                 #   CacheEntry, CacheMatchPolicy(>0.95)
│   └── infrastructure/         #   JdbcSemanticCacheRepository
├── generation/
│   ├── application/            #   AnswerGenerationService (인터페이스), PromptTemplates
│   └── infrastructure/         #   OpenAiChatClient (ChatClient.Builder)
├── guard/
│   ├── application/            #   InputGuard(포트)·RateLimitGuard(포트)·ModerationService
│   ├── domain/                 #   GuardDecision(Allowed/Blocked)·InputValidation·ContentPolicy
│   └── infrastructure/         #   OpenAiModerationClient
├── slack/
│   ├── interfaces/             #   SocketModeRunner(수신·ack·스레드히스토리·가상스레드 비동기)
│   ├── application/            #   SlackResponseService (인터페이스)
│   └── infrastructure/         #   SlackResponder (chat.postMessage)
├── routing/                    # ✅ 완료 — DefaultChatService에 배선
│   ├── interfaces/             #   RoutingCli (./gradlew routingCli 수동확인)
│   ├── application/            #   QuestionRouter(포트)·LlmQuestionRouter·RoutingPrompts
│   ├── domain/                 #   Route(DOC/RESOURCE/CLARIFY)·RouteDecision·RoutingPolicy
│   └── infrastructure/         #   OpenAiRouterLlmClient (strict json_schema + Resilience4j)
├── resource/                   # ✅ 완료 — R1~R4 구현 완료
│   ├── interfaces/             #   ResourceCli (./gradlew resourceCli 수동확인)
│   ├── application/            #   MetricQueryExtractor(포트)·LlmMetricQueryExtractor·ResourcePrompts
│   │                           #   MetricCatalog·PrometheusClient(포트)·ResourceAnswerTemplate
│   │                           #   ResourceService(인터페이스)·DefaultResourceService
│   ├── domain/                 #   MetricPattern·ResourceQuery(metric/sort/topN/window/project/instanceName)
│   │                           #   ResourceExtraction(sealed: Resolved/NeedsClarification)
│   │                           #   MetricCatalogEntry·MetricSample·PromPattern·PromQlBuilder
│   └── infrastructure/         #   HttpPrometheusClient (RestClient + TLS 바이패스 + Resilience4j 'prometheus')
└── common/
    ├── config/                 #   OpenAI/PgVectorStore/Slack/Resilience4j 빈, AppProperties, CorsConfig(/api/chat)
    └── resilience/             #   Resilience4j 데코레이터·설정
```

- **단일 진입점은 `chat.application.ChatService`** — Slack 인그레스(`slack/interfaces`)와 REST
  (`chat/interfaces`)가 모두 이 하나를 호출(채널만 다름).
- `chat.application`은 각 능력 모듈의 **application 인터페이스**에만 의존(외부 기술은 모름).
- 능력 모듈이 자기 **서비스 인터페이스를 소유**하고 `infrastructure`가 구현(DI 주입).
- `ConversationMessage`는 `chat/domain`이 소유 — `routing`·`resource`가 단방향 참조
  (의존성 방향: `chat` → `routing`/`resource`, 역방향 없음).
- `interfaces` 계층은 인바운드 진입점이 있는 `chat`·`slack`·`routing`·`resource`에 존재.

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
- **slack**: Socket Mode 고정(아웃바운드 WebSocket, **공개 URL·서명검증 불필요**). 수신 즉시 ack(3초 제약) 후 **비동기**(가상스레드)
  파이프라인 실행 → 완료 시 `chat.postMessage`로 답변+출처 게시.
  스레드 멘션이면 `ctx.client().conversationsReplies(channel, threadTs)` 호출로 이전 메시지 조회 →
  `List<ConversationMessage>`로 변환해 `ChatCommand.history`에 담아 전달.
  루트 메시지(첫 멘션)는 히스토리 없이 단발성으로 처리.
- **routing**: LLM 1회 호출(strict json_schema)로 `Route(DOC/RESOURCE/CLARIFY)` 분류.
  `history.takeLast(historyTurns)` 슬라이스로 최근 대화 맥락 사용. `DefaultChatService`에 배선 완료.
- **resource**: 자연어 질문 → `MetricQueryExtractor`(LLM strict json_schema) → `ResourceQuery` →
  `MetricCatalog.lookup` → `PromQlBuilder.build` → `HttpPrometheusClient.query` → `ResourceAnswerTemplate`.
  생성 LLM 호출 없이 템플릿으로 한국어 답변 생성. Prometheus는 사설 인증서 환경이므로
  `ssl-verify: false`시 `JdkClientHttpRequestFactory` + trust-all SSLContext로 TLS 바이패스.

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
| `app.router.model` / `.temperature` | 라우팅 모델·온도 | `gpt-4o-mini` / `0.0` |
| `app.router.min-confidence` | 미만이면 CLARIFY | `0.5` |
| `app.router.history-turns` | LLM에 넘기는 최근 대화 수 (Slack 스레드 슬라이스 기준) | `2` |
| `app.resource.extraction-model` / `.min-confidence` | 조건추출 모델·임계값 | `gpt-4o-mini` / `0.5` |
| `app.resource.default-window` / `.default-top-n` | PromQL 기본 윈도우·TopN | `5m` / `5` |
| `app.resource.prometheus.url` | Prometheus 서버 URL (환경변수 `PROMETHEUS_URL` 권장) | — |
| `app.resource.prometheus.ssl-verify` | Prometheus TLS 인증서 검증 여부 | `true` |
| `app.resource.catalog.<KEY>.pattern` | MetricPattern → PromQL 템플릿 타입 | (yml에 6개 정의) |
| `app.resource.catalog.<KEY>.raw-metric` / `.unit` | Prometheus 원시 메트릭명·단위 | (yml에 6개 정의) |
| `app.resource.severity.warn-percent` / `.crit-percent` | 위젯 색상 임계치(%). THRESHOLD 트랙의 초과 기준도 동일값 | `70` / `85` |
| `app.resource.widgets.usage-top-n` | `usage_bar` 표시 상한(IP_USAGE·CAPACITY 공용). 항목이 많으면 채팅창이 덮인다(실측 IP 네트워크 17개) | `10` |
| `app.resource.trend.default-range` | TREND(시계열) 기본 조회 구간(질문에 없을 때) | `1h` |
| `app.resource.trend.points` | TREND 구간을 나눌 목표 포인트 수 → step = range/points (최소 15s) | `60` |
| `app.resource.trend.max-series` | `metric_line` 라인 수 상한(마지막 값 기준 상위만) | `5` |
| `app.cors.allowed-origins` | `/api/chat` + `/chat-widget/**`(위젯 스크립트, `type="module"`이라 크로스오리진이면 CORS 필수) 허용 목록. 비어있으면(기본값) CORS 미적용 | `[]` |

시크릿은 **환경변수만**: `OPENAI_API_KEY`(Moderation 재사용), `SLACK_BOT_TOKEN`,
`SLACK_APP_TOKEN`, DB 접속정보(`SPRING_DATASOURCE_URL`/`DB_*`), `PROMETHEUS_URL`(RESOURCE 경로).
