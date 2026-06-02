# plan.md — ragbot-server 단계별 개발 계획

> 요구사항 [`requirements.md`](requirements.md) · 설계 [`architecture.md`](architecture.md).
> Phase 0 → 7 순차, 각 Phase는 **완료조건(DoD)** 충족 후 다음으로.
> DB 의존(테이블명·색인 데이터)은 스텁/placeholder로 우회하고 확정 시 `application.yml` 한 곳만 수정.

---

## ★ 튜닝 단일화

품질 개선으로 계속 바뀔 값은 코드에 흩지 말고 **단일 소스**(`application.yml`의 `app.*` +
`@ConfigurationProperties` POJO `AppProperties`)에서 관리한다. 값 변경 = yml 한 줄(+재기동).

| 키 | 의미 | 초기값 | 비고 |
|---|---|---|---|
| `app.openai.chat-model` | 생성 모델 | `gpt-4o` | 교체 가능 |
| `app.openai.embedding-model` | 임베딩 모델 | `text-embedding-3-small` | **1536, 색인과 동일**. OpenAI API |
| `app.openai.embedding-dim` | 임베딩 차원 | `1536` | documents/캐시 공통 |
| `app.retrieval.top-k` | 검색 청크 수 | `5` | 2차 튜닝 |
| `app.retrieval.min-score` | 유사도 하한 | `(미정)` | 0건/저유사 차단, 2차 |
| `app.retrieval.table` | documents 테이블명 | `documents` | **읽기 전용**(색인 적재는 별도) |
| `app.cache.enabled` / `.cosine-threshold` | 캐시 on·off / 히트 기준 | `true` / `0.95` | 2차 튜닝 |
| `app.cache.table` | 캐시/질의로그 테이블명 | `(이 앱 확정)` | **이 앱 소유**(DDL·조회/저장) |
| `app.slack.mode` | socket/events | `socket` | |
| `app.guard.max-question-len` / `.rate-per-min` | 입력 길이 / 분당 한도 | `1000` / `20` | RateLimiter |
| `app.guard.moderation.enabled` / `.model` | Moderation | `true` / `omni-moderation-latest` | 무료 |
| `app.guard.banned-words-path` | 로컬 금칙어 사전 | `classpath:banned-words.txt` | 1차 차단 |
| `spring.ai.retry.max-attempts` | 내장 재시도 | `1` | **비활성**(이중 재시도 방지) |
| `resilience4j.{retry,circuitbreaker,timelimiter,ratelimiter}.*` | 회복탄력성 | `(M4 설정)` | Resilience4j 통합 |

- 시크릿(`OPENAI_API_KEY`(Moderation 재사용)·`SLACK_*`·DB)은 **환경변수만**.
- 데이터 접근은 **`JdbcTemplate`**(JPA 미사용). 패키지 구조는 `architecture.md §2`.

---

## Phase 0 — 환경 구성 ✅ (완료)

- [x] Kotlin + Spring Boot 3.5.14 + JDK 21(toolchain) 프로젝트 생성(Spring Initializr 베이스).
- [x] `build.gradle.kts`: Spring AI BOM `1.1.7` + `spring-ai-starter-model-openai` +
      `spring-ai-starter-vector-store-pgvector`(+ `spring-boot-starter-jdbc`), web·actuator.
- [x] `RagbotApplication.kt`, `application.yml`(튜닝값 골격), `banned-words.txt`, Gradle wrapper.
- [x] **`./gradlew build` 성공**(BUILD SUCCESSFUL).
- [ ] DB VM PostgreSQL+pgvector 연결성 확인. OpenAI Key·Slack 토큰 env 주입(실행 시).

**DoD:** `./gradlew build` 성공 ✅ / (실행) `GET /actuator/health` → `UP`.

> M4(회복탄력성)·M5(Slack) 의존성은 `build.gradle.kts`에 주석 — 해당 Phase에서 활성화.

---

## Phase 1 — 스켈레톤 & 설정 외부화

- [ ] 모듈 골격: `chat / embedding / retrieval / cache / generation / guard / slack / common`
      + 각 모듈 계층(`architecture.md §2`).
- [ ] **`common.config.AppProperties`**(@ConfigurationProperties) ↔ `application.yml`(`app.*`) 매핑.
- [ ] `chat.application.ChatService` 인터페이스/스텁(단일 진입점), `ChatCommand/Result`.
- [ ] `chat.interfaces`: `POST /api/chat` 더미 컨트롤러(`ChatRequest/Response` 확정, 에코).
- [ ] 전역 예외 처리·로깅.

**DoD:** `POST /api/chat` → 고정 스텁 응답 + 요청 로그.

---

## Phase 2 — M1: 임베딩 + 문서 검색

- [ ] `common.config`: `OpenAiEmbeddingModel`·`PgVectorStore` 빈(값은 `AppProperties`).
- [ ] `embedding`: `EmbeddingService` + `OpenAiEmbeddingClient` — 질문 1회 임베딩→`FloatArray`.
- [ ] `retrieval`: `DocumentSearchService` + `PgVectorDocumentSearch`(`topK`·`min-score`,
      `metadata` title/page 출처), `RetrievalPolicy`(0건·저유사 차단).
- [ ] **DB 우회**: documents 미정 시 임시 시드/스텁(인터페이스 고정), 확정 시 `app.retrieval.table`만 교체.

**DoD:** `/api/chat`(생성 제외) → top-k 청크 + 출처(title/page).

---

## Phase 3 — M2: 생성 LLM

- [ ] `generation`: `AnswerGenerationService` + `OpenAiChatClient`(`ChatClient.Builder`,
      provider 교체 위해 자동구성 끄고 수동 빈). `PromptTemplates`(시스템 + top-k + 질문).
- [ ] `chat.application.ChatService`에 임베딩→검색→생성→응답 경로 연결.

**DoD:** `/api/chat` → 검색 근거 기반 답변 + 출처.

---

## Phase 4 — M3: 시맨틱 캐시 / 질의 로그

**캐시/질의로그 테이블은 이 앱 소유** — DDL·인덱스·조회/저장 모두 이 앱(외부 합의 불필요).

- [ ] **DDL을 이 앱에서 작성**(차원=`embedding-dim`, HNSW cosine, 컬럼: question·q_embedding·
      answer·sources·cache_hit·user_id·created_at). 본 레포 보관(예: `src/main/resources/db/`).
- [ ] `cache`: `SemanticCacheService` + `JdbcSemanticCacheRepository`(조회 `cosine > threshold`, 저장),
      `CacheEntry`·`CacheMatchPolicy`.
- [ ] `ChatService` 순서 확정: 임베딩(1회) → **캐시 조회(히트 즉시 반환·LLM 0)** → 미스 시
      검색→생성 → **질의 저장**(히트/미스 무관 전건, `cache_hit` 플래그).

**DoD:** 유사(>0.95) 질문 2회 → 2회차 히트·**LLM 0회**, 모든 질의 적재(로그 검증).

---

## Phase 5 — M4: 요청/응답 처리 + 회복탄력성

- [ ] `build.gradle.kts`에서 `resilience4j-spring-boot3` 활성화.
- [ ] `guard`: `InputValidation`(빈입력·길이), 레이트리밋(`rate-per-min`, Resilience4j `RateLimiter`).
- [ ] **콘텐츠 필터**: `ContentPolicy`(로컬 금칙어) → 애매하면 `ModerationService`
      (`OpenAiModerationClient`). 차단 시 임베딩·생성 **0회**, 안내.
- [ ] **회복탄력성(Resilience4j 통합)**: OpenAI 임베딩·생성·모더레이션에 `Retry`+`CircuitBreaker`+
      `TimeLimiter`. 내장 재시도 비활성(`spring.ai.retry.max-attempts=1`).
- [ ] 응답: 검색 0건/`min-score` 미달 → "관련 문서 없음"(LLM 0회). 출처 포맷 통일(Slack/REST 공통).

**DoD:** 잡담/과도요청/욕설 차단, 무관 질문 "문서 없음", 정상 질문 출처 포함 — 케이스별 LLM
호출수 검증. OpenAI 장애 모사 시 CircuitBreaker·타임아웃 동작 확인.

---

## Phase 6 — M5: Slack 연동

- [ ] `build.gradle.kts`에서 `bolt`·`bolt-socket-mode` 활성화.
- [ ] `slack.interfaces.SocketModeRunner`(수신 → **즉시 ack** → 비동기 위임).
- [ ] 비동기 실행(가상스레드/`@Async`) → `chat.application.ChatService` 호출.
- [ ] `slack`: `SlackResponseService` + `SlackResponder`(완료 시 원 스레드 `chat.postMessage`).
- [ ] (옵션) `SlackEventHandler` — `app.slack.mode=events` 대비.

**DoD:** 테스트 워크스페이스 멘션 → 스레드 답변+출처, 3초 내 ack.

---

## Phase 7 — 마무리 / 검증

- [ ] E2E: 캐시 히트/미스, 검색 실패, 입력·콘텐츠 차단, Slack 게시 전 경로.
- [ ] 로깅: 요청별 LLM 호출 여부·캐시 적중·차단 사유.
- [ ] App VM 배포 절차·환경변수 체크리스트.
- [ ] 외부 색인 확정값(metadata 키·min-score) 반영 = `application.yml`만 수정.

**DoD:** 1차 시퀀스 전 구간 동작 + 검증 케이스 통과.

---

## 병행 작업 메모 (외부 색인과 동시)

- **색인 적재는 별도** — 문서를 `text-embedding-3-small`(OpenAI API)로 임베딩해 `documents`에 INSERT.
  이 앱은 `documents`를 **읽기만** 한다(초기엔 비어 있을 수 있음).
- `documents` 데이터 미적재 → Phase 2~3는 스텁/임시 시드로 진행, 인터페이스 고정.
- **캐시/질의로그 테이블은 이 앱 소유** → 외부 대기 없이 Phase 4 진행 가능.
- 외부(색인) 의존 변경 지점은 **`application.yml`(metadata 출처 키·min-score)** 한 곳.
- 임베딩 모델/차원은 **색인과 일치**(text-embedding-3-small / 1536) — 변경 금지 불변식.

---

## 2차 (착수 금지)

청킹 품질, top-k·min-score 튜닝, 리랭킹, 하이브리드 검색(RRF), "근거 없으면 모른다" 프롬프트
정교화, LLM-as-judge 검증, 캐시 무효화, 상세 메트릭.
