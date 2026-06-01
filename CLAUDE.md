# CLAUDE.md — 사내 LLM 챗봇 봇 서버 (ragbot-server)

> 이 파일은 Claude Code가 본 프로젝트에서 작업할 때 따라야 할 설계·규약·맥락을 정의한다.

## 1. 프로젝트 개요

사내 API·도메인 지식을 RAG로 검색해 **Slack**에서 답하는 사내 LLM 챗봇의 **봇 서버**.
별도 채팅 UI 없이 Slack을 프론트로 사용한다. 우리(서버팀)는 **오케스트레이터 봇 서버
(Spring AI)** 만 구현한다. 문서 색인/임베딩 데이터 적재는 **DB팀**이 담당한다.

핵심 목표:

1. **토큰 낭비 방지** — 불필요한 LLM 호출을 호출 전에 차단(시맨틱 캐시 히트, 검색 실패 시 차단).
2. **환각(거짓말) 방지** — 검색된 근거(top-k 청크)에 기반해서만 답하고 출처를 표기.

## 2. 확정 설계 (DB팀 합의 시퀀스)

```
사용자 ─질문→ 봇 서버
  ① 봇 서버 → OpenAI Embedding(text-embedding-3-small): 질문 임베딩 (★색인과 동일 모델)
  ② OpenAI → 봇 서버: 질문 벡터(1536d)
  ③ 봇 서버 → 시맨틱 캐시(pgvector): 유사 질문 조회 (cosine > 0.95)
  alt [캐시 히트] → 저장된 답변 반환 (LLM 호출 0회 ✅)
      [캐시 미스]
  ④ 봇 서버 → pgvector(documents): ORDER BY embedding <=> 질문벡터 LIMIT 5
  ⑤ pgvector → 봇 서버: top-5 청크 + 출처
  ⑥ 봇 서버 → 생성 LLM(gpt): 시스템 + 검색청크 + 질문   ★여기서만 비싼 생성 호출
  ⑦ 생성 LLM → 봇 서버: 답변
  ⑧ 봇 서버 → 시맨틱 캐시: 질문벡터 + 답변 저장
  ⑨ 봇 서버 → 사용자: 답변 + 출처(title/page)
```

**불변 규칙(어기지 말 것):**

- 질문 임베딩은 **요청당 1회만** 계산하고, 그 벡터를 **캐시 조회(③)와 문서 검색(④)에 재사용**한다.
- 비싼 LLM 생성 호출은 **캐시 미스 + 검색 성공 경로(⑥)에서만** 발생한다.
- 색인·질의 임베딩은 **반드시 동일 모델(`text-embedding-3-small`, 1536차원)** 이어야 한다.
  (다른 모델/차원을 섞으면 검색이 무의미해짐 — 최우선 불변식)

## 3. 책임 경계 (서버팀 ↔ DB팀)

| 영역                                                                                                                           | 소유                |
| ------------------------------------------------------------------------------------------------------------------------------ | ------------------- |
| 봇 서버/오케스트레이션, 질문 임베딩 호출, **캐시 조회·저장 호출**, documents 조회, LLM 생성·프롬프트·출처 포맷, 요청/응답 처리 | **서버팀(본 레포)** |
| 문서 색인(동일 모델), documents 테이블·벡터 인덱스(HNSW cosine), 청킹, **시맨틱 캐시/질의 로그 테이블 생성·관리**              | **DB팀**            |

> 시맨틱 캐시 테이블은 **DB팀이 생성·관리**한다. 서버팀은 합의된 스키마로 **조회/저장(insert)만**
> 수행한다. 이 테이블은 캐시 겸 **질의 로그** — 캐시 히트뿐 아니라 **모든 질의를 저장**한다.

## 4. 기술 스택

- **Java 21** (가상스레드 비동기), **Spring Boot 3.x**, **Spring AI 1.0.x**, Gradle
- Spring AI 모듈: OpenAI(Chat+Embedding), PgVectorStore
- Slack: `com.slack.api:bolt` + `bolt-socket-mode` (Events API는 옵션)
- DB: PostgreSQL + **pgvector** (DB VM, App VM과 동일 사내 내부망)
- 관측: Spring Boot Actuator (+ Micrometer)

## 5. 패키지 구조 (`com.okestro.ragbot`)

```
config/        OpenAI(Chat/Embedding)·PgVectorStore·Async·Slack 빈 설정
slack/         SocketModeRunner, (SlackEventsController), SlackResponder
orchestration/ ChatOrchestrator  — ①~⑨ 파이프라인의 단일 진입점
embedding/     EmbeddingService  — 질문 1회 임베딩 + 벡터 공유
cache/         SemanticCacheRepository — qa_cache, cosine>0.95 조회/저장(JdbcTemplate)
retrieval/     DocumentSearch    — PgVectorStore top-5 + 출처
llm/           ChatClientFactory(provider), PromptTemplates
guardrail/     InputGuard        — 입력 검증·레이트리밋
api/           REST /api/chat (Slack 없이 테스트), /actuator
```

`ChatOrchestrator`가 파이프라인의 **단일 진입점**이다. Slack 인그레스와 REST `/api/chat`는
모두 동일한 `ChatOrchestrator`를 호출한다(채널만 다름).

## 6. 핵심 컴포넌트 규약

- **임베딩**: `OpenAiEmbeddingModel`, 모델 `text-embedding-3-small`(1536). 질문 임베딩은
  `EmbeddingService`에서 1회 계산 후 파이프라인 내에서 전달(재계산 금지).
- **문서 검색**: Spring AI `PgVectorStore` 표준 스키마, 거리=cosine(`vector_cosine_ops`),
  `SearchRequest.topK(5)`. 출처는 `metadata`의 `title`/`page`에서 읽는다.
- **시맨틱 캐시(겸 질의 로그)**: 테이블은 **DB팀 소유·관리**. 서버는 합의 스키마로 **조회/저장만**.
  히트 기준 **cosine > 0.95**(= `<=>` 거리 < 0.05). 히트 시 저장된 답변+출처 반환.
  **캐시 히트/미스와 무관하게 모든 질의를 저장**한다(질의 로그 용도 겸용).
  아래는 합의 전 **참고 스키마**(최종 컬럼/테이블명은 DB팀 확정값을 따른다):
  ```sql
  -- (DB팀 소유) 예시: question, q_embedding(vector 1536), answer, sources(jsonb),
  --   cache_hit(bool), user_id, created_at + HNSW(q_embedding, vector_cosine_ops)
  ```
- **생성**: `OpenAiChatModel`(gpt-4o 등) 기반 `ChatClient`. provider는 프로퍼티로 두어
  교체 여지 유지(Claude 등). 프롬프트 = 시스템 규칙 + top-5 청크 + 질문.
- **Slack**: Socket Mode 우선(공개 URL 불필요). 수신 즉시 ack(3초 제약) 후 **비동기**
  파이프라인 실행 → 완료 시 원 스레드에 `chat.postMessage`로 답변+출처 게시.
- **요청/응답 처리**: 입력 가드(빈입력·길이·레이트리밋), 검색 0건/저유사도 시 "관련 문서 없음"
  안내, 답변에 출처(title/page) 항상 포함.

## 7. 설정 (프로퍼티 외부화 — 하드코딩 금지)

```
app.llm.provider           = openai          # 교체 여지
app.openai.chat.model      = gpt-4o
app.openai.embedding.model = text-embedding-3-small   # 1536
app.retrieval.top-k        = 5
app.retrieval.min-score    = <튜닝, 2차>
app.cache.enabled          = true
app.cache.cosine-threshold = 0.95
app.slack.mode             = socket          # | events
```

시크릿은 **환경변수**로만: `OPENAI_API_KEY`, `SLACK_BOT_TOKEN`, `SLACK_APP_TOKEN`, DB 접속정보.
임계값/모델/top-k 등 튜닝 대상은 절대 코드에 하드코딩하지 말고 프로퍼티로 노출한다.

## 8. DB팀 계약 (변경 시 양 팀 합의 필요)

1. 색인·질의 임베딩 모델 통일: `text-embedding-3-small`, **1536차원**.
2. `documents`: Spring AI `PgVectorStore` 표준 스키마. 합의 항목 — 테이블명, 차원(1536),
   거리=cosine, **metadata 키(`title`, `page`)**.
3. **시맨틱 캐시/질의 로그 테이블**: **DB팀이 생성·관리**. 서버는 조회/저장만. 합의 필요 —
   테이블명, 컬럼(question·q_embedding(1536)·answer·sources·cache_hit·user_id·created_at 등),
   HNSW(cosine) 인덱스. **모든 질의 저장**(히트/미스 무관).
4. OpenAI 외부 전송(임베딩·생성) 보안 정책 승인.

## 9. 작업 범위 (1차 / 2차)

**1차 (지금 구현 — 합의 시퀀스 그대로):**

- M0 스캐폴드 → M1 임베딩+문서검색(출처) → M2 생성 → M3 시맨틱 캐시 → M4 요청/응답 처리 → M5 Slack 연동.

**2차 (품질 고도화 — 지금 구현하지 말 것):**

- 청킹 품질 개선, top-k·`min-score` 튜닝, 리랭킹, 하이브리드 검색(키워드+벡터, RRF),
  "근거 없으면 모른다" 프롬프트 정교화, LLM-as-judge 근거 검증, 캐시 무효화 정교화, 메트릭.

## 10. 빌드 / 실행 / 검증 (Gradle, 예정)

```bash
./gradlew build                 # 빌드 + 테스트
./gradlew bootRun               # 로컬 실행
./gradlew test                  # 단위/통합 테스트
```

- **Slack 없이 파이프라인 테스트**: `POST /api/chat` 로 질문→top-5 출처 포함 답변 확인.
- **캐시 검증**: 유사(>0.95) 질문 2회 → 2회차 캐시 히트, **LLM 호출 0회**(로그 확인).
- **검색 실패**: 무관 질문 → "관련 문서 없음", LLM 0회.
- **헬스**: `GET /actuator/health`.

## 11. 컨벤션 / 하지 말 것

- 질문 임베딩 **재계산 금지** — 한 요청에서 1회만, 벡터를 전달해 재사용.
- 임베딩 모델/차원을 documents 색인과 **다르게 두지 말 것**.
- 임계값·top-k·모델명 **하드코딩 금지** — 프로퍼티.
- 2차(고도화) 기능을 1차에 끌어오지 말 것 — 범위 고정.
- 시크릿을 코드/설정파일에 커밋 금지 — 환경변수.
- 외부 채널(Slack 게시, OpenAI 호출)은 부수효과이므로, 핵심 로직은 채널과 분리해 테스트 가능하게.
