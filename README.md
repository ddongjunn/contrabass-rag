# ragbot-server

사내 API·도메인 지식을 **RAG**로 검색해 **Slack**에서 답하는 사내 LLM 챗봇의 **봇 서버**입니다.
별도 UI 없이 Slack을 프론트로 쓰며, 본 레포는 검색·생성을 오케스트레이션하는 **Spring AI
기반 봇 서버**만 다룹니다. **이 봇 서버가 곧 "RAG 챗봇 서버"** — 별도 RAG 서버를 두지
않습니다. 문서 색인(임베딩해 적재)은 별도(색인 레포)가 담당하며, 이 앱은 `documents`를 **읽기만** 합니다.

> **임베딩은 OpenAI API 호출**입니다(로컬 모델 아님). `text-embedding-3-small`은 OpenAI 독점이라
> 로컬 구동이 불가하며, 색인·질의 임베딩 모두 OpenAI를 HTTP로 호출합니다.

## 핵심 목표
- **토큰 낭비 방지** — 검색 실패·유해 입력 시 LLM 호출을 앞단에서 차단 (캐시 히트 차단은 고도화)
- **환각 방지** — 검색 근거(top-k 청크)에 기반해서만 답하고 **출처(title/page)** 표기

## 동작 흐름 (질의)

> **1차 보류:** 아래 시퀀스의 **시맨틱 캐시(C) 단계는 고도화로 보류**(삭제 아님). 1차 실행 경로는
> 캐시 조회/저장을 건너뛴 **임베딩→검색→생성**입니다. 임베딩 1회 재사용 seam은 유지합니다.

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

> 질문 임베딩은 **요청당 1회만** 계산해 문서 검색에 재사용하고(캐시 조회 재사용은 고도화), 비싼 LLM
> 생성은 **검색 성공 경로에서만** 발생합니다(캐시 미스 게이트는 고도화).

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
cache/         시맨틱 캐시 조회/저장 (이 앱 소유 테이블) — ⏸ 고도화 보류(1차 미생성)
generation/    LLM 답변 생성 (ChatClient) · 프롬프트
guard/         입력 검증 · 레이트리밋 · 콘텐츠 필터(금칙어 + Moderation)
slack/         Slack 인그레스 · 응답 게시
routing/       질문 라우터 (DOC / RESOURCE / CLARIFY 분류) — 완성, 파이프라인 배선 예정
resource/      인프라 실시간 지표 조회 (Prometheus TopN) — 🚧 개발 예정(Phase R1~R4)
common/        config · Resilience4j
```

> 현재 1차(Phase 0~7) 구현 완료. `routing/`은 완성됐으나 파이프라인 미연결.
> `resource/`는 [`docs/future/resource-prometheus-path.md`](docs/future/resource-prometheus-path.md)의 Phase R1~R4에서 추가됩니다.

## 빌드 / 실행

```bash
./gradlew build          # 빌드 (BUILD SUCCESSFUL)
./gradlew bootRun        # 로컬 실행 (OPENAI_API_KEY·DB 접속정보 env 필요)
./gradlew test           # 테스트
```

VM 배포(앱+pgvector 컨테이너):

```bash
./deploy.sh              # git pull → 빌드 + 기동(up -d --build) 후 헬스 대기  ← 기본
./deploy.sh up           # pull 없이 빌드 + 기동
./deploy.sh down|logs|ps|restart
./deploy.sh <그 외>      # 정의되지 않은 인자는 docker compose 로 그대로 전달 (예: exec app sh)
```

- 시크릿은 환경변수로 주입: `OPENAI_API_KEY`(Moderation 재사용), `SLACK_BOT_TOKEN`,
  `SLACK_APP_TOKEN`, DB 접속정보, `PROMETHEUS_URL`(RESOURCE 경로 활성 시) (`.env.example` 참고)
- 튜닝 값(모델·top-k·임계값·테이블명·회복탄력성)은 `application.yml` 한 곳에서 관리

## 문서
- [`CLAUDE.md`](./CLAUDE.md) — 코딩 가이드라인 + 프로젝트 불변식
- [`docs/requirements.md`](docs/requirements.md) — 요구사항·시퀀스·데이터 소유·데이터 계약 (1차+2차 통합)
- [`docs/architecture.md`](docs/architecture.md) — 기술스택·패키지 구조·컴포넌트 규약·설정
- [`docs/process.md`](docs/process.md) — 단계별 개발·검수 사이클 규약
- [`docs/phase1/plan.md`](docs/phase1/plan.md) — 1차 개발 계획 Phase 0~7 (완료)
- [`docs/phase2/plan.md`](docs/phase2/plan.md) — 2차 개발 계획 R0~R4 (진행 중, 설계도 포함)

## 질문 라우터 (Question Router)

사용자 질문을 `DOC / RESOURCE / CLARIFY`로 분류하는 독립 모듈(`com.okestro.ragbot.routing`).
구현 완료 · 단독 CLI 동작 확인됨. RAG/리소스 파이프라인 배선은 Phase R4에서 진행.

### 설정 (`application.yml`)
`app.router.*` — `model`(라우팅 모델), `temperature`, `min-confidence`(미만이면 CLARIFY), `history-turns`(LLM에 넘기는 최근 메시지 수).

### 테스트
- 로직 테스트(키 불필요, 항상 실행): `./gradlew test`
- 실제 분류 정확도(선택): `OPENAI_API_KEY`가 있으면 `RoutingAccuracyTest`가 자동 실행된다.

### 수동 CLI
```
OPENAI_API_KEY=sk-... ./gradlew routingCli -q --console=plain
```
질문을 타이핑하면 `route / confidence / reason`을 출력한다(빈 줄/Ctrl-D 종료).
