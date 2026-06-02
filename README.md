# ragbot-server

사내 API·도메인 지식을 **RAG**로 검색해 **Slack**에서 답하는 사내 LLM 챗봇의 **봇 서버**입니다.
별도 UI 없이 Slack을 프론트로 쓰며, 본 레포는 검색·생성·캐시를 오케스트레이션하는 **Spring AI
기반 봇 서버**만 다룹니다. **이 봇 서버가 곧 "RAG 챗봇 서버"** — 별도 RAG 서버를 두지
않습니다. 문서 색인(임베딩해 적재)은 별도(색인 레포)가 담당하며, 이 앱은 `documents`를 **읽기만** 합니다.

> **임베딩은 OpenAI API 호출**입니다(로컬 모델 아님). `text-embedding-3-small`은 OpenAI 독점이라
> 로컬 구동이 불가하며, 색인·질의 임베딩 모두 OpenAI를 HTTP로 호출합니다.

## 핵심 목표
- **토큰 낭비 방지** — 캐시 히트·검색 실패·유해 입력 시 LLM 호출을 앞단에서 차단
- **환각 방지** — 검색 근거(top-k 청크)에 기반해서만 답하고 **출처(title/page)** 표기

## 동작 흐름 (질의)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant B as 봇 서버
    participant E as OpenAI Embedding API<br/>(text-embedding-3-small)
    participant C as 시맨틱 캐시 (pgvector)
    participant D as pgvector (documents)
    participant L as 생성 LLM (gpt)

    U->>B: 질문
    B->>E: 질문 임베딩 요청 (★색인과 동일 모델)
    E-->>B: 질문 벡터 (1536d)
    B->>C: 유사 질문 조회 (cosine > 0.95)
    alt 캐시 히트
        C-->>B: 저장된 답변
        B-->>U: 답변 반환 (LLM 호출 없음 ✅)
    else 캐시 미스
        B->>D: 유사도 검색 ORDER BY embedding <=> 질문벡터 LIMIT 5
        D-->>B: top-5 청크 + 출처
        B->>L: 시스템 + 검색청크 + 질문 (★여기서만 생성 호출)
        L-->>B: 답변 생성
        B->>C: 질문벡터 + 답변 저장 (모든 질의 기록)
        B-->>U: 답변 + 출처(title/page)
    end
```

> 질문 임베딩은 **요청당 1회만** 계산해 캐시 조회·문서 검색에 재사용하고, 비싼 LLM 생성은
> **캐시 미스 + 검색 성공 경로에서만** 발생합니다.

## 기술 스택
- **Kotlin** · JDK 21(LTS) · **Spring Boot 3.5.14** · **Spring AI 1.1.7** · Gradle(Kotlin DSL)
- OpenAI (Chat, Embedding, **Moderation**) — 모두 API 호출
- 데이터 접근: **JdbcTemplate** (JPA 미사용)
- 회복탄력성: **Resilience4j 통합** (Retry·CircuitBreaker·TimeLimiter·RateLimiter)
- PostgreSQL + **pgvector** (cosine / HNSW)
- Slack Bolt (Socket Mode 고정)

## 프로젝트 구조

**Layered 4-tier**(`interfaces / application / domain / infrastructure`)를 **도메인(능력)별
모듈** 안에 둡니다 (`com.okestro.ragbot`, `src/main/kotlin`).

```
chat/          오케스트레이션 유스케이스 — 파이프라인 단일 진입점 (ChatService)
embedding/     질문 임베딩 (OpenAI API, 1회 계산 후 재사용)
retrieval/     documents top-k 검색 + 출처
cache/         시맨틱 캐시 조회/저장 (이 앱 소유 테이블, JdbcTemplate)
generation/    LLM 답변 생성 (ChatClient) · 프롬프트
guard/         입력 검증 · 레이트리밋 · 콘텐츠 필터(금칙어 + Moderation)
slack/         Slack 인그레스 · 응답 게시
common/        config · Resilience4j
```

> 현재는 M0(스캐폴드) 단계 — `RagbotApplication.kt`만 존재하며 빌드가 통과합니다.
> 각 모듈은 [`docs/plan.md`](docs/plan.md)의 Phase 1~5에서 추가됩니다.

## 빌드 / 실행

```bash
./gradlew build          # 빌드 (BUILD SUCCESSFUL)
./gradlew bootRun        # 로컬 실행 (OPENAI_API_KEY·DB 접속정보 env 필요)
./gradlew test           # 테스트
```

- 시크릿은 환경변수로 주입: `OPENAI_API_KEY`(Moderation 재사용), `SLACK_BOT_TOKEN`,
  `SLACK_APP_TOKEN`, DB 접속정보 (`.env.example` 참고)
- 튜닝 값(모델·top-k·임계값·테이블명·회복탄력성)은 `application.yml` 한 곳에서 관리

## 문서
- [`CLAUDE.md`](./CLAUDE.md) — 코딩 가이드라인 + 프로젝트 불변식
- [`docs/requirements.md`](docs/requirements.md) — 요구사항·시퀀스·데이터 소유·데이터 계약
- [`docs/architecture.md`](docs/architecture.md) — 기술스택·패키지 구조·컴포넌트 규약·설정
- [`docs/plan.md`](docs/plan.md) — Phase별 개발 계획
