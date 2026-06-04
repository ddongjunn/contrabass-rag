# plan.md — ragbot-server 단계별 개발 계획

> 요구사항 [`requirements.md`](requirements.md) · 설계 [`architecture.md`](architecture.md).
> Phase 0 → 7 순차, 각 Phase는 **완료조건(DoD)** 충족 후 다음으로.
> DB 의존(테이블명·색인 데이터)은 스텁/placeholder로 우회하고 확정 시 `application.yml` 한 곳만 수정.
>
> **★ 1차 범위 = Phase 0·1·2·3·5·6·7. Phase 4(시맨틱 캐시)는 고도화로 보류**(2026-06-04 결정 —
> 삭제 아님, §Phase 4 참고). v1 경로 = **Phase 3 → 5 → 6 → 7**.

---

## ★ 튜닝 단일화

품질 개선으로 계속 바뀔 값은 코드에 흩지 말고 **단일 소스**(`application.yml`의 `app.*` +
`@ConfigurationProperties` POJO `AppProperties`)에서 관리한다. 값 변경 = yml 한 줄(+재기동).

| 키 | 의미 | 초기값 | 비고 |
|---|---|---|---|
| `app.openai.chat-model` | 생성 모델 | `gpt-4o-mini` | 비용 최소 우선·교체 가능 |
| `app.openai.embedding-model` | 임베딩 모델 | `text-embedding-3-small` | **1536, 색인과 동일**. OpenAI API |
| `app.openai.embedding-dim` | 임베딩 차원 | `1536` | documents/캐시 공통 |
| `app.retrieval.top-k` | 검색 청크 수 | `5` | 2차 튜닝 |
| `app.retrieval.min-score` | 유사도 하한 | `0.32` | 실측(정상≥0.376/무관≤0.263) 사이값. 저유사 0건 차단 |
| `app.retrieval.table` | documents 테이블명 | `documents` | **읽기 전용**(색인 적재는 별도) |
| `app.cache.enabled` / `.cosine-threshold` | 캐시 on·off / 히트 기준 | `true` / `0.95` | **고도화 보류**(Phase 4) |
| `app.cache.table` | 캐시/질의로그 테이블명 | `(이 앱 확정)` | **이 앱 소유** — 구현 고도화 보류 |
| `app.slack.mode` | 연결 방식 | `socket` | **1차 고정**(공개 URL·서명검증 불필요) |
| `app.guard.max-question-len` / `.rate-per-min` | 입력 길이 / **사용자별** 분당 한도 | `1000` / `5` | RateLimiter(`user_id` 키) |
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
- [x] DB VM PostgreSQL+pgvector 연결성 확인 — **`contrabass_rag` DB에 documents 159행 적재 확인**
      (스키마 = `db/init/01-schema.sql` 일치, vector 확장 설치). env `DB_NAME=contrabass_rag`.
- [ ] OpenAI Key·Slack 토큰 env 주입(실행 시).

**DoD:** `./gradlew build` 성공 ✅ / (실행) `GET /actuator/health` → `UP`.

> M4(회복탄력성)·M5(Slack) 의존성은 `build.gradle.kts`에 주석 — 해당 Phase에서 활성화.

---

## Phase 1 — 스켈레톤 & 설정 외부화

- [x] 모듈 골격: 이번 Phase 사용 모듈만 생성(`chat`,`common`). 나머지(embedding/retrieval/…)는
      빈 패키지 미리 만들지 않고 해당 Phase에서 생성(범위 고정).
- [x] **`common.config.AppProperties`**(@ConfigurationProperties, `@ConfigurationPropertiesScan`) ↔ `app.*` 전체 매핑.
- [x] `chat.application.ChatService` 인터페이스/스텁(`StubChatService`, 단일 진입점), `ChatCommand/Result`.
- [x] `chat.interfaces`: `POST /api/chat` 컨트롤러(`ChatRequest/Response` 확정, 에코).
- [x] 전역 예외 처리(`common.web.GlobalExceptionHandler`)·요청 로깅.

**DoD:** `POST /api/chat` → 고정 스텁 응답 + 요청 로그. ✅
(검증: 빌드/슬라이스테스트 그린, 런타임 health=UP, `POST /api/chat`→`{"answer":"stub: …"}`, 로그 `userId=… question.len=…`)

---

## Phase 2 — M1: 임베딩 + 문서 검색

> **서브분할**(process.md): 외부 I/O 2종(OpenAI 임베딩 + pgvector 검색) 동시 도입 → 안전하게 2-a/2-b로.
> 각 서브 Phase는 자체 빌드·검수 가능 단위. DB 우회(스텁/시드)는 **불필요** — `contrabass_rag.documents`
> 159행 실데이터로 검증(인터페이스는 그대로 고정, 스텁 경로 안 만듦 — 범위 축소).

### Phase 2-a — 임베딩 (질문 1회 임베딩) ✅
- [x] `embedding/application/EmbeddingService`(인터페이스) + `embedding/infrastructure/OpenAiEmbeddingClient`
      — Spring AI 자동구성 `EmbeddingModel`(yml의 `text-embedding-3-small`/1536) 주입, `embed(text)->FloatArray`.
- [x] 단위 테스트: `EmbeddingModel` 페이크로 1회 호출 위임·결과 전달 검증(외부 호출 없음).

**DoD(2-a):** 빌드/단위테스트 그린 ✅. (런타임) 2-b `/api/chat` 경유 실 OpenAI 임베딩 1회 검증됨.

### Phase 2-b — 문서 검색 + 파이프라인 연결 ✅
> **검색 방식 결정**: Spring AI `VectorStore.similaritySearch`는 질문 텍스트만 받아 **스토어 내부에서 재임베딩**
> → 불변식 2(임베딩 1회·재사용) 위반·2-a 우회. 따라서 `PgVectorStore` 빈을 쓰지 않고, **2-a가 만든
> 벡터를 받아 `JdbcTemplate`로 pgvector 코사인 검색**(`embedding <=> :vec`). documents 읽기 전용 SELECT.
- [x] `retrieval/application/DocumentSearchService`(인터페이스 `search(queryVector, topK)`) +
      `retrieval/infrastructure/PgVectorDocumentSearch`(JdbcTemplate, `1-(embedding<=>vec)` score, `topK`),
      `retrieval/domain/RetrievalPolicy`(`min-score` 미설정 시 0건만, 설정 시 저유사 필터),
      `RetrievedChunk`/`Source` 도메인.
- [x] **출처 표기**: `title #chunk_index`(항상 — 개발자가 근거 청크를 DB에서 찾아 검증). `page`는 non-null 시 추가
      (현 docx는 전부 null, 코드가 null 허용). `doc_id`·`source`는 1차 미사용. 출처키 = `app.retrieval.source-keys`.
- [x] `ChatService`를 임베딩(2-a)→검색→top-k 반환(`DefaultChatService`)으로 연결(**생성은 제외**, Phase 3).

**DoD(2-b):** `/api/chat`(생성 제외) → 실 documents 기준 top-k 청크 + 출처(`title #chunk_index`, page는 있을 때). ✅
(검증: 빌드/단위테스트 8건 그린, 런타임 `POST /api/chat`→실 문서 제목+청크번호 출처 표기 확인.)

---

## Phase 3 — M2: 생성 LLM ✅

- [x] `generation`: `AnswerGenerationService` + `OpenAiChatClient`(`ChatModel` 주입→수동 `ChatClient`,
      `spring.ai.chat.client.enabled=false`로 자동구성 끔). `PromptTemplates`(시스템 + top-k + 질문).
- [x] `chat.application.ChatService`(`DefaultChatService`)에 임베딩→검색→생성→응답 경로 연결.
      검색 0건 시 생성 0회·"관련 문서 없음"(불변식 3).

**DoD:** `/api/chat` → 검색 근거 기반 답변 + 출처. ✅
(검증: 빌드/단위테스트 11건 그린, 런타임 `POST /api/chat`→gpt-4o-mini 근거 기반 답변+출처 확인.)

---

## Phase 4 — M3: 시맨틱 캐시 / 질의 로그  ⏸ 고도화 보류 (1차 범위 외)

> **결정(2026-06-04): 시맨틱 캐시는 1차에서 제외하고 고도화로 보류한다(삭제 아님).** 근거:
> ① 1차 비용 방어선은 캐시가 아니라 **검색 0건·유해 입력의 호출 전 단락**(Phase 5)에서 대부분 확보됨,
> ② 시맨틱 캐시는 *임베딩 유사 ≠ 정답 동일*로 인한 **오답 히트**·**문서 갱신 시 staleness** 위험이 있어
>    만료/무효화 전략과 함께 별도 설계가 필요, ③ chat 모델을 `gpt-4o-mini`로 두면 생성 단가가 낮아
>    캐시 보류의 비용 영향이 작다.
> **아래 설계·DDL은 그대로 보존**하고, 1차 코드는 임베딩 1회 재사용 seam만 유지해(이미 적용됨)
> 고도화 시 이 자리(임베딩 직후·검색 직전)에 캐시 조회/저장을 끼우도록 한다. 1차는 이 Phase를 건너뛴다.

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

> **서브분할**(process.md, 2026-06-04 결정): 새 파일 6+ · 새 외부 I/O(Moderation) · 새 의존성(resilience4j)
> 동시 도입 → **5-a/5-b/5-c**로 분할. 각 서브는 자체 빌드·검수 가능 최소 단위.

### Phase 5-a — 입력·콘텐츠 가드 (로컬, 외부 I/O 0) ✅
- [x] `guard/domain`: `InputValidation`(빈입력·`max-question-len` 초과), `ContentPolicy`(로컬 금칙어,
      `banned-words-path` 로드·대소문자 무시 부분일치), `GuardDecision`(Allowed/Blocked(reason·message)).
- [x] `guard/application`: `InputGuard`(인터페이스) + `DefaultInputGuard`(입력검증 → 금칙어 순 1차 차단).
- [x] `chat.application.DefaultChatService` 앞단에 가드 삽입 — 차단 시 임베딩·생성 **0회**(불변식 4), 안내 응답.

**DoD(5-a):** 빌드/단위테스트 그린 ✅(총 21건). 빈입력·과길이·금칙어 → 임베딩/생성 0회 정적 검증.
(런타임 차단 시연·LLM 0회 실측은 사용자 검수.)

### Phase 5-b — Moderation 2차 필터 (OpenAI) ✅
- [x] `guard/application/ModerationService`(인터페이스) + `guard/infrastructure/OpenAiModerationClient`
      (Spring AI `ModerationModel` autoconfig, 모델명 = `app.guard.moderation.model`을 `OpenAiModerationOptions`로
      요청당 주입 — 하드코딩 금지). `flagged` 시 차단(reason=moderation).
- [x] `DefaultInputGuard` 체인 끝에 Moderation 연결 — `enabled=false` 시 `@ConditionalOnProperty`로 빈 미생성→
      nullable 주입으로 건너뜀. 앞 단계(입력검증·금칙어) 차단 시 moderation 미호출(차단=임베딩·생성 **0회**).

**DoD(5-b):** 빌드/단위테스트 그린 ✅(총 25건, OpenAiModerationClient flagged→Blocked 매핑·체인 단락 검증).
유해 입력 Moderation 차단 시 LLM 0회는 사용자 런타임 검수(실 API 호출).

> **서브분할**(2026-06-04 결정): 새 의존성(resilience4j) + 인바운드 레이트리밋 + 아웃바운드 회복탄력성 +
> 튜닝(min-score)이 섞여 큼 → **5-c-1/5-c-2**로 분할. min-score는 무관 질문 가짜 출처(런타임 관찰) 즉시
> 해결책이라 먼저 뺀다.

### Phase 5-c-1 — min-score (검색 품질 가드) ✅
> 기제는 2-b에 이미 있음(`RetrievalPolicy.minScore` 필터, 0건 → "관련 문서 없음"·LLM 0회).
> 남은 일 = 실측용 score 로깅 + 임계값 설정.
- [x] `DefaultChatService`에 검색 raw score 로깅(정상/무관 질문 점수 분포 실측용).
- [x] 실측(정상 [0.512..0.376] / 무관 [0.263..0.242]) 후 `app.retrieval.min-score = 0.32` 설정 →
      무관 질문 → 5청크 전부 필터 → "관련 문서 없음"·출처0·**LLM 0회**.

**DoD(5-c-1):** 무관 질문 → 출처 0·LLM 0회(로그 검증). 정상 질문은 임계값 통과해 답변+출처 유지.
(빌드/테스트 25건 그린. 런타임 무관 질문 LLM 0회 시연은 사용자 검수.)

### Phase 5-c-2 — 레이트리밋 + 회복탄력성 (Resilience4j 통합)
- [ ] `build.gradle.kts`에서 `resilience4j-spring-boot3` 활성화.
- [ ] **사용자별** 레이트리밋(`rate-per-min` 템플릿, `RateLimiterRegistry`를 Slack `user_id`로 키잉 — 전역 단일 인스턴스 금지).
- [ ] **회복탄력성**: OpenAI 임베딩·생성·모더레이션에 `Retry`+`CircuitBreaker`(+`TimeLimiter` — 동기 호출이라
      executor 데코레이션 vs HTTP read-timeout 중 착수 시 결정). 내장 재시도 비활성(`spring.ai.retry.max-attempts=1`, 이미 적용).
- [ ] 출처 포맷 통일(Slack/REST 공통).

**DoD:** 잡담/과도요청/욕설 차단, 무관 질문 "문서 없음", 정상 질문 출처 포함 — 케이스별 LLM
호출수 검증. OpenAI 장애 모사 시 CircuitBreaker·타임아웃 동작 확인.

---

## Phase 6 — M5: Slack 연동

- [ ] `build.gradle.kts`에서 `bolt`·`bolt-socket-mode` 활성화.
- [ ] `slack.interfaces.SocketModeRunner`(수신 → **즉시 ack** → 비동기 위임).
- [ ] **봇/자기 메시지 무시**(`bot_id` 존재 시 드롭) — 봇 답변 재유입에 의한 무한 루프·비용 폭주 차단.
- [ ] 비동기 실행(가상스레드/`@Async`) → `chat.application.ChatService` 호출.
- [ ] `slack`: `SlackResponseService` + `SlackResponder`(완료 시 원 스레드 `chat.postMessage`).

**DoD:** 테스트 워크스페이스 멘션 → 스레드 답변+출처, 3초 내 ack. 봇 답변이 재유입돼도 루프 안 됨(`bot_id` 드롭 확인).

---

## Phase 7 — 마무리 / 검증

- [ ] E2E: 검색 실패, 입력·콘텐츠 차단, Slack 게시 전 경로. (캐시 히트/미스는 고도화 시 추가)
- [ ] 로깅: 요청별 LLM 호출 여부·차단 사유. (캐시 적중 로깅은 고도화)
- [ ] App VM 배포 절차·환경변수 체크리스트.
- [ ] 외부 색인 확정값(metadata 키·min-score) 반영 = `application.yml`만 수정.

**DoD:** 1차 시퀀스 전 구간 동작 + 검증 케이스 통과.

---

## 병행 작업 메모 (외부 색인과 동시)

- **색인 적재는 별도** — 문서를 `text-embedding-3-small`(OpenAI API)로 임베딩해 `documents`에 INSERT.
  이 앱은 `documents`를 **읽기만** 한다.
- **현재 상태(2026-06-04 확인): `contrabass_rag.documents` 159행 적재 완료** → Phase 2~3 스텁 불필요, 실데이터로 진행.
  (색인은 계속 늘어날 수 있으나 이 앱은 읽기만 하므로 영향 없음.)
- **캐시/질의로그 테이블은 이 앱 소유**(외부 대기 없음) → 단, **Phase 4는 고도화로 보류**(1차 범위 외).
- 외부(색인) 의존 변경 지점은 **`application.yml`(metadata 출처 키·min-score)** 한 곳.
- 임베딩 모델/차원은 **색인과 일치**(text-embedding-3-small / 1536) — 변경 금지 불변식.
- 실측 metadata 키: `doc_id, source, title, page(현재 전부 null), chunk_index, content_hash`.

---

## 2차 (착수 금지)

**시맨틱 캐시/질의 로그(Phase 4 전체)**, 청킹 품질, top-k·min-score 튜닝, 리랭킹,
하이브리드 검색(RRF), "근거 없으면 모른다" 프롬프트 정교화, LLM-as-judge 검증, 캐시 무효화, 상세 메트릭.
