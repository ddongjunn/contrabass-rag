# requirements.md — ragbot-server 요구사항

> 무엇을·왜 만드는가. 설계/구조는 [`architecture.md`](architecture.md).
> 1차 실행 계획: [`phase1/plan.md`](phase1/plan.md) · 2차 실행 계획: [`phase2/plan.md`](phase2/plan.md)

## 1. 개요

사내 API·도메인 지식을 RAG로 검색해 **Slack**에서 답하는 사내 LLM 챗봇의 **봇 서버**.
별도 UI 없이 Slack을 프론트로 쓰며, 이 레포는 **오케스트레이터 봇 서버(Spring AI)** 만 구현한다.
**이 봇 서버가 곧 "RAG 챗봇 서버"** — 별도 RAG 서버를 추가로 두지 않는다.
**문서 색인(임베딩해 documents에 적재)은 별도(색인 레포)** 가 담당하며, 이 앱은 `documents`를 **읽기만** 한다.

> **임베딩 = OpenAI API 호출(로컬 모델 아님).** `text-embedding-3-small`은 OpenAI 독점이라
> 로컬 구동 불가. 색인·질의 임베딩 모두 OpenAI를 HTTP로 호출한다(§5 보안 승인 전제).
> "로컬 색인"은 *색인 스크립트 실행 위치*를 뜻할 뿐, 임베딩 연산은 OpenAI에서 일어난다.

**핵심 목표:** ① **토큰 낭비 방지**(검색 실패·유해 입력을 호출 전 차단; 캐시 히트 차단은 고도화) ②
**환각 방지**(검색 근거 top-k에 기반해서만 답하고 출처 표기).

## 2. 확정 시퀀스 (불변)

> **1차 보류:** ②캐시 조회·⑤질의 저장은 **고도화로 보류**(삭제 아님). 1차 실행 경로 = ①→③→④→⑥.
> 임베딩 1회 재사용 seam은 유지해 고도화 시 ②⑤를 끼운다.

```
사용자 ─질문→ 봇 서버
  ① 질문 임베딩  : OpenAI Embedding API(text-embedding-3-small, 1536d) ★색인과 동일 모델
  ② 캐시 조회    : 시맨틱 캐시(pgvector) cosine > 0.95   〔고도화 보류 — 1차 미적용〕
       [히트] → 저장된 답변 반환 (LLM 0회 ✅)
       [미스] ↓
  ③ 문서 검색    : documents ORDER BY embedding <=> 질문벡터 LIMIT 5 → top-5 청크 + 출처
  ④ 답변 생성    : 생성 LLM(gpt) ← 시스템 + 검색청크 + 질문   ★여기서만 비싼 생성 호출
  ⑤ 질의 저장    : 질문벡터 + 답변을 캐시에 저장(히트/미스 무관 전건)   〔고도화 보류〕
  ⑥ 응답         : 답변 + 출처(title/page)
```

**불변 규칙:**

- 질문 임베딩은 **요청당 1회만** 계산하고 그 벡터를 **문서 검색에 재사용**(재계산 금지; 캐시 조회 재사용은 고도화).
- 비싼 LLM 생성은 **검색 성공 경로에서만** 발생(캐시 미스 게이트는 고도화).
- 색인·질의 임베딩은 **반드시 동일 모델(`text-embedding-3-small`, 1536d)** — 최우선 불변식.
- 임베딩·생성·모더레이션은 **외부(OpenAI) 호출** → 회복탄력성·레이트리밋 대상.

## 3. 데이터 소유 / 외부 의존

| 데이터 | 이 앱(본 레포)의 책임 |
| --- | --- |
| `documents` (사내 문서 임베딩) | **읽기 전용** — 조회만. 테이블·벡터 인덱스 생성과 색인 적재(INSERT)는 **별도**(색인 레포 / `db/init/01-schema.sql`)가 담당 |
| 시맨틱 캐시 / 질의 로그 테이블 | **이 앱이 소유** — DDL·조회·저장 모두. 캐시 겸 질의 로그로 히트/미스 무관 **모든 질의 저장** |

> 이 앱은 `documents`에 **읽기만** 한다(현재 `contrabass_rag.documents`에 159행 적재됨, 색인은 계속 증가 가능). 캐시/질의 로그 테이블만 이 앱이 생성·관리한다.

## 4. 작업 범위

**1차 (완료):** M0 스캐폴드 → M1 임베딩+문서검색(출처) → M2 생성 →
M4 요청/응답 처리(가드·레이트리밋·**콘텐츠 필터**·**회복탄력성**) → M5 Slack 연동.
*(M3 시맨틱 캐시는 **고도화로 보류** — 삭제 아님, `plan.md §Phase 4` 참고.)*

**2차 (완료):** 질문 라우터(`routing/`) + RESOURCE(Prometheus) 경로(`resource/`).
DOC 전용 파이프라인을 인프라 실시간 지표 조회로 확장한다.

| 단계 | 내용 | 상태 |
| --- | --- | --- |
| 질문 라우터 R0 | 자연어(+ 스레드 히스토리) → DOC / RESOURCE / CLARIFY 분류 (LLM 1회) | ✅ 완료 |
| RESOURCE R1 | 자연어 → ResourceQuery 조건추출 (LLM strict json_schema, instanceName 포함) | ✅ 완료 |
| RESOURCE R2 | Metric Catalog + PromQlBuilder (자연어→PromQL 조립, 6개 메트릭 패턴) | ✅ 완료 |
| RESOURCE R3 | Prometheus HTTP 클라이언트 + 템플릿 답변 (TLS 바이패스 + Resilience4j) | ✅ 완료 |
| RESOURCE R4 | 라우터·RESOURCE·Slack 스레드 히스토리를 DefaultChatService에 배선 | ✅ 완료 |
| RESOURCE TREND | 시계열 질문("추이") → `query_range` → `metric_line` 라인그래프 위젯 | ✅ 완료 |
| REST 히스토리 | 웹 위젯 → `ChatRequest.history` → 라우터·추출 맥락 상속(후속질문) | ✅ 완료 |
| QUOTA·PROJECT_USAGE | 실 Prometheus에 limits 메트릭 부재 → 제거 확정(정책: 실존 메트릭만 지원) | 제거됨 |
| 실존 메트릭 target 3종 | IP_USAGE(네트워크 IP)·CAPACITY(스토리지 용량)·AGENT(에이전트 헬스) — 전부 실측 검증 | ✅ 완료 |
| TREND 클러스터 확장 | TOTAL_VMS(VM 수 추이)·STORAGE_USED(스토리지 사용률 추이) | ✅ 완료 |

> 2차 상세 계획: [`phase2/plan.md`](phase2/plan.md)

**후속 (착수 금지):** **시맨틱 캐시/질의 로그(Phase 4)**, 청킹 품질, top-k·`min-score` 튜닝, 리랭킹,
하이브리드 검색(RRF), "근거 없으면 모른다" 프롬프트 정교화, LLM-as-judge 검증, 캐시 무효화, 메트릭.

## 5. 데이터 계약 (색인과의 불변식 — 변경 시 합의 필요)

1. 색인·질의 임베딩 모델 통일: `text-embedding-3-small`(OpenAI API), **1536d**.
2. `documents`: Spring AI `PgVectorStore` 표준 스키마(`db/init/01-schema.sql`). 합의 항목 — 테이블명
   (`documents`), 차원(1536), 거리=cosine, **metadata 키(`title`,`page`)**. 색인 적재는 별도(이 앱은 읽기 전용).
3. 시맨틱 캐시/질의 로그 테이블은 **이 앱 소유**.
4. OpenAI 외부 전송(임베딩·생성·**Moderation**) 보안 정책 승인 — 사내 문서·질의가 OpenAI로 전송됨.
