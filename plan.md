# plan.md — ragbot-server 단계별 개발 계획

> 설계 기준: `CLAUDE.md` / 청사진: `~/.claude/plans/llm-sparkling-marshmallow.md`
> 진행 방식: Phase 0 → 7 순차. 각 Phase는 **완료조건(DoD)** 를 만족해야 다음으로 넘어간다.
> DB팀과 **동시 작업** 중 — 테이블명·색인 데이터 등 DB 의존은 placeholder/스텁으로 우회하고
> 확정되면 **튜닝 파일 한 곳만** 수정한다(§ 튜닝 단일화 참고).

---

## ★ 튜닝 단일화 (가장 중요한 운영 원칙)

이후 품질 개선으로 **계속 바뀔 값**들은 코드에 흩지 말고 **단일 소스**에서 관리한다.
구현 방식: `application.yml` + `@ConfigurationProperties` POJO(`AppProperties`) 1개.
→ 값 변경 = yml 한 줄 수정(+재기동). 코드 수정/재컴파일 불필요.

**튜닝 대상(전부 외부화, 하드코딩 금지):**

| 키 | 의미 | 초기값 | 비고 |
|---|---|---|---|
| `app.openai.chat-model` | 생성 모델 | `gpt-4o` | 교체 가능 |
| `app.openai.embedding-model` | 임베딩 모델 | `text-embedding-3-small` | **1536, 색인과 동일 고정** |
| `app.openai.embedding-dim` | 임베딩 차원 | `1536` | documents/캐시 공통 |
| `app.retrieval.top-k` | 검색 청크 수 | `5` | 2차 튜닝 |
| `app.retrieval.min-score` | 검색 유사도 하한 | `(미정)` | 0건/저유사 차단, 2차 튜닝 |
| `app.retrieval.table` | documents 테이블명 | `TODO_DB팀` | **DB팀 합의 대기** |
| `app.cache.enabled` | 캐시 on/off | `true` | |
| `app.cache.cosine-threshold` | 캐시 히트 기준 | `0.95` | 2차 튜닝 |
| `app.cache.table` | 캐시/질의로그 테이블명 | `TODO_DB팀` | **DB팀 소유·관리**(서버는 조회/저장만) |
| `app.slack.mode` | socket/events | `socket` | |
| `app.guard.max-question-len` | 입력 길이 상한 | `(설정)` | |
| `app.guard.rate-per-min` | 사용자 분당 한도 | `(설정)` | |

- `metadata` 출처 키(`title`,`page`)도 상수화(`app.retrieval.source-keys.*`)해 DB팀 확정 시 즉시 반영.
- 시크릿(`OPENAI_API_KEY`,`SLACK_BOT_TOKEN`,`SLACK_APP_TOKEN`,DB 접속)은 **환경변수만**, yml/코드 금지.

---

## Phase 0 — 환경 구성 (Environment & Spec 설치)

**목표:** 빈 Spring Boot 앱이 부팅되고 외부 의존(OpenAI·pgvector·Slack 토큰)에 연결 준비 완료.

- [ ] JDK 21 설치 확인, Gradle Wrapper 사용(`./gradlew`).
- [ ] `start.spring.io`로 프로젝트 생성: Spring Boot 3.x, Gradle, Java 21.
      의존성: Spring Web, Actuator, **Spring AI OpenAI**, **Spring AI PgVectorStore**,
      JDBC, (Slack `bolt`,`bolt-socket-mode`는 수동 추가).
- [ ] `build.gradle`에 Spring AI BOM + Slack Bolt 의존성 정리.
- [ ] DB VM의 PostgreSQL + pgvector **연결성 확인**(`SELECT version();`, `CREATE EXTENSION IF NOT EXISTS vector;` 확인은 DB팀).
- [ ] OpenAI API Key 발급/주입(env), Slack App 생성(Socket Mode → Bot/App 토큰 env).
- [ ] `git init`, `.gitignore`(빌드 산출물·`*.env`·로컬 시크릿 제외).

**산출물:** 부팅되는 빈 앱, `application.yml`(빈 골격), env 주입 체계.
**완료조건(DoD):** `./gradlew bootRun` 성공, `GET /actuator/health` → `UP`.

---

## Phase 1 — 기본 껍데기 (Skeleton & 설정 외부화)

**목표:** 패키지 구조·튜닝 파일·더미 엔드포인트로 골격 완성. 이후 Phase는 살만 붙이면 됨.

- [ ] 패키지 골격 생성: `config / slack / orchestration / embedding / cache / retrieval / llm / guardrail / api`.
- [ ] **`AppProperties`(@ConfigurationProperties)** 작성 + `application.yml` 매핑(위 튜닝표 전부).
      테이블명 등 미정값은 `TODO_*` placeholder + 주석.
- [ ] `ChatOrchestrator` 인터페이스/스텁(단일 진입점) 정의.
- [ ] `POST /api/chat` 더미 컨트롤러(요청/응답 DTO 확정, 에코 반환).
- [ ] 전역 예외 처리·로깅 기본 설정.

**산출물:** 패키지 골격, `AppProperties`, `/api/chat` DTO.
**DoD:** `POST /api/chat`에 질문 보내면 고정 스텁 응답 + 요청 로그 확인.

---

## Phase 2 — M1: 임베딩 + 문서 검색 (④⑤)

**목표:** 질문을 1회 임베딩해 documents에서 top-k 청크+출처를 가져온다(생성 전까지).

- [ ] `config`: `OpenAiEmbeddingModel`(model·dim은 `AppProperties`에서) 빈.
- [ ] `embedding/EmbeddingService`: 질문 1회 임베딩 → `float[]` 반환(이후 단계서 재사용).
- [ ] `config`: `PgVectorStore` 빈 — 테이블명/차원/거리(cosine)를 `AppProperties`에서 주입.
- [ ] `retrieval/DocumentSearch`: `SearchRequest.topK(app.retrieval.top-k)` + `min-score` 적용,
      결과 청크 + `metadata`(title/page)로 출처 추출.
- [ ] **DB 의존 우회**: DB팀 테이블/데이터 미정 시 → 임시 테스트 테이블에 소량 시드 또는
      스텁 `DocumentSearch`로 개발 진행(인터페이스 고정). 확정 시 `app.retrieval.table`만 교체.

**산출물:** `EmbeddingService`, `DocumentSearch`, 임베딩/벡터스토어 설정.
**DoD:** `/api/chat`(생성 제외 모드)에서 질문→top-k 청크+출처(title/page) 반환 확인.

---

## Phase 3 — M2: 생성 LLM (⑥⑦)

**목표:** 검색 청크를 컨텍스트로 LLM 답변 생성. 전체 RAG 1패스 완성.

- [ ] `llm/ChatClientFactory`: `app.llm.provider`로 `ChatClient` 선택(OpenAI gpt 기본).
- [ ] `llm/PromptTemplates`: **시스템 규칙 + top-k 청크 + 질문** 조립(기본 근거 주입).
- [ ] `ChatOrchestrator`에 ②→④→⑤→⑥→⑦→⑨ 경로 연결, 답변+출처 반환.

**산출물:** `ChatClientFactory`, `PromptTemplates`, 동작하는 RAG 파이프라인(캐시 전).
**DoD:** `/api/chat` 질문 → 검색 근거 기반 답변 + 출처(title/page) 반환.

---

## Phase 4 — M3: 시맨틱 캐시 / 질의 로그 (①③⑧)

**목표:** 반복 질문 시 LLM 0회. 질문 벡터 재사용으로 임베딩도 1회만.
**테이블은 DB팀이 생성·관리** — 서버는 합의 스키마로 **조회/저장(insert)만** 한다.

- [ ] **테이블/DDL은 DB팀**(차원=`embedding-dim`, HNSW cosine). 서버는 `app.cache.table`로 참조만.
      → DB팀과 컬럼 스키마(question·q_embedding·answer·sources·cache_hit·user_id·created_at 등) 합의.
- [ ] `cache/SemanticCacheRepository`(JdbcTemplate): 조회(`cosine > app.cache.cosine-threshold`), 저장.
- [ ] `ChatOrchestrator` 순서 확정: 임베딩(1회) → **캐시 조회(히트 시 즉시 반환·LLM 0)** →
      미스 시 검색→생성 → **질의 저장**.
- [ ] **모든 질의 저장**: 캐시는 히트/미스를 가르지만, 저장은 **히트·미스 무관 전건 기록**(질의 로그 겸용,
      `cache_hit` 플래그 포함).
- [ ] `app.cache.enabled=false`면 캐시 **조회**는 우회하되, 질의 로그 저장 정책은 별도 플래그로 분리 가능.

**산출물:** `SemanticCacheRepository`(조회/저장), 캐시 통합 파이프라인. (DDL은 DB팀 산출물)
**DoD:** 유사(>0.95) 질문 2회 → 2회차 캐시 히트·**LLM 0회**, 모든 질의가 테이블에 적재됨(로그로 검증).

---

## Phase 5 — M4: 요청 처리 / 응답 처리

**목표:** 입력 가드와 빈 검색 결과 처리로 토큰 낭비·오답을 앞단에서 차단.

- [ ] `guardrail/InputGuard`: 빈입력·길이 상한(`max-question-len`), 사용자별 레이트리밋(`rate-per-min`).
      차단 시 임베딩·LLM **0회**, 안내 응답.
- [ ] 응답 처리: 검색 0건 / `min-score` 미달 → "관련 문서 없음" 안내(LLM 0회).
- [ ] 출처 포맷 통일(title/page) — Slack/REST 공통 포맷터.

**산출물:** `InputGuard`, 출처 포맷터, 빈 결과 처리.
**DoD:** 잡담/과도요청 차단, 무관 질문 "문서 없음", 정상 질문 출처 포함 — 각 케이스 LLM 호출수 검증.

---

## Phase 6 — M5: Slack 연동

**목표:** Slack에서 멘션 → 스레드에 답변+출처. ack 3초 제약 준수.

- [ ] `config`: Slack Bolt(Socket Mode) 빈, 토큰 env 주입.
- [ ] `slack/SocketModeRunner`: 멘션/슬래시커맨드 수신 → **즉시 ack** → 비동기 위임.
- [ ] 비동기 실행기(Java 21 가상스레드/`@Async`) → `ChatOrchestrator` 호출.
- [ ] `slack/SlackResponder`: 완료 시 원 스레드에 `chat.postMessage`(답변+출처). (선택)"생각 중…" 후 갱신.
- [ ] (옵션) `SlackEventsController` 골격 — `app.slack.mode=events` 대비.

**산출물:** Socket Mode 봇, 비동기 처리, `SlackResponder`.
**DoD:** 테스트 워크스페이스에서 멘션 → 스레드 답변+출처(title/page), 3초 내 ack.

---

## Phase 7 — 마무리 / 검증 / 인계

- [ ] E2E 점검: 캐시 히트/미스, 검색 실패, 입력 차단, Slack 게시 전 경로.
- [ ] 로깅/지표 최소화: 요청별 LLM 호출 여부·캐시 적중·차단 사유.
- [ ] App VM 배포 절차 문서화, 환경변수 체크리스트.
- [ ] DB팀 확정값(테이블명·metadata 키·min-score) 반영 = `application.yml`만 수정.

**DoD:** 1차 시퀀스(①~⑨) 전 구간 동작 + 검증 케이스 통과.

---

## 병행 작업 메모 (DB팀 동시 진행)
- `documents` 테이블명/스키마/색인 데이터 미정 → Phase 2~3는 **스텁/임시 시드**로 진행, 인터페이스 고정.
- **시맨틱 캐시/질의 로그 테이블도 DB팀 소유·관리** → 테이블명/컬럼 스키마 합의 대기. 서버는 조회/저장만,
  Phase 4도 스키마 확정 전엔 인터페이스(`SemanticCacheRepository`) 고정 후 진행.
- 확정 시 변경 지점은 **`application.yml`(documents·캐시 테이블명·차원·출처 키·min-score)** 한 곳.
- 임베딩 모델/차원은 **DB팀 색인과 반드시 일치**(text-embedding-3-small / 1536) — 변경 금지 합의값.

---

## 2차(고도화) — 본 계획 범위 밖 (착수 금지)
청킹 품질, top-k·min-score 튜닝, 리랭킹, 하이브리드 검색(키워드+벡터·RRF),
"근거 없으면 모른다" 프롬프트 정교화, LLM-as-judge 검증, 캐시 무효화 정교화, 상세 메트릭.
