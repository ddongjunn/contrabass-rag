# Phase 2 개발 계획 — 질문 라우터 + RESOURCE(Prometheus) 경로

> 요구사항 [`../requirements.md`](../requirements.md) · 설계 [`../architecture.md`](../architecture.md) · 개발 규약 [`../process.md`](../process.md)
> Phase R0 → R4 순차, 각 Phase는 **완료조건(DoD)** 충족 후 다음으로 (`process.md` 규약 동일 적용).

---

## 새 세션 시작 시 (인수인계)

> **이 섹션은 AI 세션이 새로 열릴 때 가장 먼저 읽을 것.**

### 현재 상태 (2026-06-29 기준)

| Phase | 상태 | 비고 |
|---|---|---|
| R0 질문 라우터 | ✅ 완료 | `routing/` 모듈 완성. 단, 파이프라인 미연결 |
| R1 조건 추출 | ✅ 완료 | `resource/` 모듈 골격. CLI로 수동 테스트 가능 |
| R2 카탈로그·PromQL | ✅ 완료 | Metric Catalog + PromQlBuilder + instanceName 필터 |
| R3 Prometheus 클라이언트 | ✅ 완료 | |
| R4 파이프라인 배선 | ✅ 완료 | 라우터·RESOURCE·Slack 히스토리 모두 연결 |

### 바로 시작하려면

```bash
# 빌드 확인
./gradlew test

# 라우터 수동 테스트
export $(grep -v '^#' .env | xargs) && ./gradlew routingCli -q --console=plain

# 조건 추출 수동 테스트
export $(grep -v '^#' .env | xargs) && ./gradlew resourceCli -q --console=plain

# 서버 실행 (Slack 비활성, REST만)
export $(grep -v '^#' .env | xargs) && SLACK_APP_TOKEN= SLACK_BOT_TOKEN= ./gradlew bootRun
```

### 다음 할 일

**R4까지 코드 완료. Slack 사용자 검수 단계.** `docs/phase2/plan.md §Phase R4 DoD` 항목을 실제 Slack에서 확인한다.
`process.md`의 개발→검수 사이클(착수 전 정렬 → 개발 → Claude 검수 → 사용자 검수 → 커밋)을 매 Phase 적용한다.

---

## 목표

사용자 질문의 **유형에 따라 파이프라인이 분기**되는 챗봇을 만든다.

- 문서 기반 질문(`DOC`) → 기존 RAG 경로 그대로
- 인프라 지표 질문(`RESOURCE`) → 자연어에서 조건을 추출하고, 빠진 정보는 사용자에게 되물은 후,
  **사내 서비스에서 실제로 사용 중인 메트릭 카탈로그 기반 PromQL 템플릿**으로 쿼리를 조립해
  Prometheus에서 조회한 결과를 사용자 질문에 맞게 답변으로 제공한다.
- 분류 불가(`CLARIFY`) → 무엇을 원하는지 되물음

**목표 흐름 예시:**
```
사용자: "CPU 높은 VM 알려줘"
  → 라우터: RESOURCE 분류
  → 조건 추출: metric=INSTANCE_CPU, sort=DESC, topN=5 (기본값)
  → 카탈로그: ratio_topk 패턴 + libvirt_domain_info_cpu_time_seconds_total
  → PromQL 조립: topk(5, (sum by(domain)(rate(...[5m])) / ...) * on(domain) group_left(...) ...)
  → Prometheus 조회: [web-01: 82%, db-01: 71%, ...]
  → 답변: "현재 CPU 사용량이 가장 높은 인스턴스는 web-01(82%), db-01(71%) 입니다."
```

---

## 핵심 설계 결정

| 결정 | 내용 | 이유 |
|---|---|---|
| 라우팅은 LLM 1회 | 규칙 기반 대신 LLM 분류 | "1번 인스턴스 상세 알려줘" 같은 맥락 의존 질문을 규칙으로 처리 불가 |
| BOTH 라우트 제거 | DOC / RESOURCE / CLARIFY 3개 | 실사용 불필요, 단순화 (커밋 `77fdfbb`) |
| LLM이 PromQL 직접 생성 금지 | 카탈로그 + 패턴 템플릿으로 서버가 조립 | 존재하지 않는 메트릭·라벨 환각 원천 차단 (불변식 5) |
| RESOURCE 답변 = 템플릿 | LLM 생성 무호출 | 비용 절감 + 환각 방지 (불변식 3 보완) |
| strict json_schema 사용 | Spring AI 1.1.7 지원 확인 후 채택 | 파싱 실패 → CLARIFY/NeedsClarification 안전망 유지 |
| 히스토리 타입 재사용 | `ConversationMessage`를 라우터·RESOURCE 공통 사용 | 별도 타입 불필요 |
| LlmClient seam 재사용 | `routing/application/LlmClient` 인터페이스를 resource에서도 사용 | OpenAI 호출 방식 동일, 중복 제거 |

---

## 전체 파이프라인 흐름 (R4 완료 후 목표 상태)

```
[Slack @mention]           [REST POST /api/chat]
        \                          /
         ▼                        ▼
     SocketModeRunner       ChatController
              \                  /
               ▼                ▼
           ChatCommand(question, userId, history)
                        │
                        ▼
         ┌──────────────────────────────────────────┐
         │           DefaultChatService             │
         │                                          │
         │  ① RateLimitGuard ──초과──▶ 안내 응답    │
         │  ② InputGuard     ──차단──▶ 안내 응답    │
         │  ③ QuestionRouter.route(history)  ◀── LLM #1 │
         │         │                                │
         │   ┌─────┼──────┐                         │
         │  DOC  RESOURCE CLARIFY                   │
         └────┼──────┼──────┼────────────────────────┘
              ▼      ▼      ▼
           RAG    Resource  되물음
          경로     경로      응답
```

### RESOURCE 경로 내부

```
ResourceService.handle(history)
    │
    ▼
ⓐ MetricQueryExtractor.extract(history) ◀── LLM #2 (strict json_schema)
    │   └▶ ResourceExtraction
    │        ├ NeedsClarification ─▶ 되물음 (Prometheus 0, 생성 0)
    │        └ Resolved(ResourceQuery: metric, sort, topN, window, project)
    ▼
ⓑ MetricCatalog.lookup(metric) ─▶ MetricCatalogEntry(pattern, rawMetric, unit)
    ▼
ⓒ PromQlBuilder.build(query, entry) ─▶ PromQL 문자열  (순수함수, LLM 무호출)
    ▼
ⓓ PrometheusClient.query(promql) ──HTTP GET /api/v1/query──▶ Prometheus
    └▶ List<MetricSample>(labels, value, unit)
    ▼
ⓔ ResourceAnswerTemplate(query, samples) ─▶ ChatResult(answer, sources)  (LLM 무호출)
```

**비용 요약**: 질문당 유료 LLM 최대 2회 — 라우팅(#1) + 조건추출(#2). Prometheus 조회·템플릿 답변은 무료.

---

## 지원 지표 (MetricPattern / R2 카탈로그)

| enum 키 | 의미 | PromQL 패턴 |
|---|---|---|
| `INSTANCE_CPU` | VM CPU 사용률 (%) | `ratio_topk` |
| `INSTANCE_MEMORY` | VM 메모리 사용률 (%) | `gauge_topk` |
| `INSTANCE_NETWORK_RX` | 네트워크 수신량 | `counter_rate_topk` |
| `INSTANCE_NETWORK_TX` | 네트워크 송신량 | `counter_rate_topk` |
| `INSTANCE_DISK_READ` | 디스크 읽기량 | `counter_rate_topk` |
| `INSTANCE_DISK_WRITE` | 디스크 쓰기량 | `counter_rate_topk` |

---

## Phase R0 — 질문 라우터 ✅ 완료

> 사용자 질문(최근 N턴 포함)을 `DOC / RESOURCE / CLARIFY`로 분류하는 독립 모듈.
> 파이프라인 배선은 R4에서 진행. 커밋: `12d3176` (feat/question-router merge)

- [x] `routing/domain`: `Route`(DOC/RESOURCE/CLARIFY), `RouteDecision`(route, confidence, reason),
      `ConversationMessage`(role: USER/ASSISTANT, content), `RoutingPolicy`(confidence < min → CLARIFY 폴백)
- [x] `routing/application`: `QuestionRouter`(포트), `LlmClient`(LLM 호출 seam — 원시 JSON 반환),
      `RoutingPrompts`(system + few-shot 5개 + strict json_schema), `LlmQuestionRouter`(본체 — 프롬프트→호출→파싱→폴백→로깅)
- [x] `routing/infrastructure`: `OpenAiRouterLlmClient`(Spring AI ChatClient + strict json_schema + Resilience4j @Retry/@CircuitBreaker)
- [x] `routing/interfaces`: `RoutingCli`(독립 main — `./gradlew routingCli` 수동확인)
- [x] `AppProperties.Router` + `app.router.*` (model·temperature·min-confidence·history-turns)
- [x] `build.gradle.kts` `routingCli` 태스크
- [x] 테스트: `RoutingPolicyTest`(3), `LlmQuestionRouterTest`(5, 스텁 기반), `RoutingAccuracyTest`(3, env-gated)

**설정 (`app.router.*`)**

| 키 | 기본값 | 의미 |
|---|---|---|
| `app.router.model` | `gpt-4o-mini` | 라우팅 모델 |
| `app.router.temperature` | `0.0` | 결정성 |
| `app.router.min-confidence` | `0.5` | 미만이면 CLARIFY 폴백 |
| `app.router.history-turns` | `2` | LLM에 넘기는 최근 메시지 수 |

**DoD ✅:** `./gradlew test` 그린(키 불필요) / `./gradlew routingCli` 로 질문 입력 → `route/confidence/reason` 출력 확인.

---

## Phase R1 — RESOURCE 조건추출 골격 ✅ 완료

> 자연어 → `ResourceQuery`(구조화된 조회 조건) 변환. Prometheus·카탈로그 없이 추출만.
> 커밋: `2e9908b`

- [x] `resource/domain`: `MetricPattern`(enum 6종), `ResourceQuery`(metric, sort, topN, window, project),
      `ResourceExtraction`(sealed: `Resolved`/`NeedsClarification`)
- [x] `resource/application`: `MetricQueryExtractor`(포트), `LlmMetricQueryExtractor`(본체),
      `ResourcePrompts`(system + few-shot 6개 + strict json_schema — metric enum은 카탈로그 키 목록)
- [x] `resource/interfaces`: `ResourceCli`(독립 main — `./gradlew resourceCli` 수동확인)
- [x] `AppProperties.Resource` + `app.resource.*` (extraction-model·temperature·min-confidence·default-window·default-top-n·prometheus.*)
- [x] `build.gradle.kts` `resourceCli` 태스크 + `bootRun/bootJar` mainClass 명시(다중 main() 대응)
- [x] 추출 로그 3줄: `extraction-raw`(question/model/latencyMs/json) · `extraction-resolved`(조건 전체) · `extraction-clarify`(되물음 메시지)
- [x] 테스트: `LlmMetricQueryExtractorTest`(13, 스텁 기반 — topN clamp·window 빈값·project null 등),
      `MetricExtractionAccuracyTest`(8, env-gated OPENAI_API_KEY)

**설정 (`app.resource.*`)**

| 키 | 기본값 | 의미 |
|---|---|---|
| `app.resource.extraction-model` | `gpt-4o-mini` | 조건추출 모델 |
| `app.resource.temperature` | `0.0` | 결정성 |
| `app.resource.min-confidence` | `0.5` | 미만이면 NeedsClarification |
| `app.resource.default-window` | `5m` | window 미지정 시 기본값 |
| `app.resource.default-top-n` | `5` | topN 미지정 시 기본값 |
| `app.resource.prometheus.base-url` | env `PROMETHEUS_URL` | Prometheus 엔드포인트 |
| `app.resource.prometheus.connect-timeout` | `3s` | HTTP 연결 타임아웃 |
| `app.resource.prometheus.read-timeout` | `10s` | HTTP 읽기 타임아웃 |

**DoD ✅:** `./gradlew test` 그린(키 불필요) / `OPENAI_API_KEY` 있으면 추출 정확도 8케이스 통과
/ `./gradlew resourceCli` 로 "cpu 높은 VM 알려줘" → `extraction-resolved` 로그 확인.

---

## Phase R2 — Metric Catalog + PromQlBuilder ✅ 완료

> `Resolved(ResourceQuery)` → 실제 PromQL 문자열 조립. Prometheus 실데이터 실측 완료 기반.
> 커밋: `9c328f9` (기본), `instanceName` 추가 이후 커밋

**확정 카탈로그 (라이브 실측 2026-06-23)**: groupBy = `domain`(전 메트릭 공통). info-join으로 instance_name·project_name 부착.

| enum 키 | pattern | rawMetric | 비고 |
|---|---|---|---|
| `INSTANCE_CPU` | `ratio_topk` | `libvirt_domain_info_cpu_time_seconds_total` | rate ÷ virtual_cpus × 100 (0~100%) |
| `INSTANCE_MEMORY` | `gauge_topk` | `libvirt_domain_memory_stats_used_percent` | 이미 % — 계산 불필요 |
| `INSTANCE_NETWORK_RX` | `counter_rate_topk` | `libvirt_domain_interface_stats_receive_bytes_total` | target_device들 sum |
| `INSTANCE_NETWORK_TX` | `counter_rate_topk` | `libvirt_domain_interface_stats_transmit_bytes_total` | target_device들 sum |
| `INSTANCE_DISK_READ` | `counter_rate_topk` | `libvirt_domain_block_stats_read_requests_total` | target_device들 sum |
| `INSTANCE_DISK_WRITE` | `counter_rate_topk` | `libvirt_domain_block_stats_write_requests_total` | target_device들 sum |

**PromQL 패턴 템플릿**

- 공통 enrich `{E}` = `* on(domain) group_left(instance_name, project_name) libvirt_domain_openstack_info[{filters}]`
  - `project` 필터 시: `libvirt_domain_openstack_info{project_name="prod"}`
  - `instanceName` 필터 시: `libvirt_domain_openstack_info{instance_name="web-01"}`
  - 복합 필터: `libvirt_domain_openstack_info{project_name="prod",instance_name="web-01"}`
- `counter_rate_topk` = `topk({topN}, sum by(domain)(rate({rawMetric}[{window}])) {E})`
- `gauge_topk` = `topk({topN}, {rawMetric} {E})`
- `ratio_topk` (CPU) = `topk({topN}, (sum by(domain)(rate({rawMetric}[{window}])) / on(domain) libvirt_domain_info_virtual_cpus * 100) {E})`

**ResourceQuery 필드**

| 필드 | 타입 | 기본값 | 의미 |
|---|---|---|---|
| `metric` | `MetricPattern` | — | 조회 지표 |
| `sort` | `Sort(DESC/ASC)` | `DESC` | topk/bottomk |
| `topN` | `Int` | `5` | 조회 개수 (1~20 clamp) |
| `window` | `String` | `5m` | rate 집계 윈도우 |
| `project` | `String?` | `null` | 프로젝트 이름 필터 |
| `instanceName` | `String?` | `null` | VM 인스턴스 이름 필터 |

- [x] `resource/domain`: `MetricCatalogEntry`, `PromPattern`, `PromQlBuilder`(object 순수함수)
- [x] `resource/application/MetricCatalog`(@Component): config 로딩, metric enum 목록 제공
- [x] `app.resource.catalog` → `application.yml`(6키) + `AppProperties.Resource.CatalogEntryConfig` 매핑
- [x] `ResourceQuery`에 `instanceName: String?` 추가 → info-join 셀렉터 복합 필터 지원
- [x] `ResourcePrompts`: `instanceName` 추출 규칙 + few-shot 2개 추가, JSON 스키마 업데이트

**DoD ✅:** `PromQlBuilderTest` 9케이스 그린 (CPU/Memory/Network/Disk × DESC·ASC·project·instanceName·복합필터).
`./gradlew test` 그린(키 불필요).

---

## Phase R3 — Prometheus 클라이언트 + 템플릿 답변 🔲 예정

> PromQL → Prometheus HTTP 호출 → 결과 → 템플릿 답변. `PROMETHEUS_URL` 도달성 선확인 필요.
> 선행: `curl -k https://10.255.40.10:11909/api/v1/query?query=up` 도달성·TLS 확인

- [ ] `resource/domain/MetricSample`(labels, value, unit) + `display()`
- [ ] `resource/application/PrometheusClient`(포트) + `resource/infrastructure/HttpPrometheusClient`:
      `RestClient` + `/api/v1/query` + Jackson 파싱 + `@Retry/@CircuitBreaker(name="prometheus")`
- [ ] `resource/application/ResourceAnswerTemplate`(object 순수함수): 결과 → 한국어 답변 + 출처. **LLM 무호출**
- [ ] `resource/application/ResourceService`(@Component): extract→catalog→build→query→template 오케스트레이션
- [ ] `ResourceCli` E2E 확장: 질문 → PromQL → Prometheus 결과 → 답변 출력
- [ ] `resilience4j.{retry,circuitbreaker}.instances.prometheus` `application.yml` 추가

**DoD:** `OPENAI_API_KEY=… PROMETHEUS_URL=… ./gradlew resourceCli`
→ "cpu 사용량 가장 높은 인스턴스는?" → 조립된 PromQL + 실제 결과 + 템플릿 답변(출처) 출력.
env-gated `PrometheusQueryTest`(`@EnabledIfEnvironmentVariable(PROMETHEUS_URL)`).

> **리스크**: 단순 쿼리는 즉시 응답, rate+조인 등 무거운 쿼리는 타임아웃 관찰 → Resilience4j `prometheus` 인스턴스 필수,
> HTTPS 내부 IP·사설 인증서 → `RestClient` TLS 신뢰 설정(테스트 한정).

---

## Phase R4 — 파이프라인 배선 ✅ 완료

> 라우터 + RESOURCE + Slack 히스토리를 실제 `DefaultChatService`에 모두 연결. DOC 경로 회귀 보존.
> 커밋: R4-a `7b5fffe`, R4-b `7025380`, R4-c 이후 커밋

### R4-a: 공통 타입 정리 + ChatCommand 히스토리 추가 ✅

- [x] `ConversationMessage`를 `routing/domain/` → `chat/domain/`으로 이동 (14개 파일 임포트 수정)
- [x] `chat/application/ChatCommand`에 `history: List<ConversationMessage> = emptyList()` 추가

### R4-b: DefaultChatService 라우터 분기 연결 ✅

- [x] `ResourceService` 인터페이스 추출, 구현체 `DefaultResourceService`로 분리
- [x] `DefaultChatService.handle`: 입력가드 직후 `router.route(history)` + `when(route)` 분기
      - `DOC` → 기존 임베딩→검색→생성 (그대로)
      - `RESOURCE` → `resourceService.handle(history)` → 템플릿 답변
      - `CLARIFY` → 되물음 응답 (유료호출 0)
- [x] 케이스별 routingCalls/embeddingCalls/extractionCalls/llmCalls 로그
- [x] `DefaultChatServiceTest`: DOC 회귀 4개 + RESOURCE 2개 + CLARIFY 1개 (총 7개 그린)

### R4-c: Slack 스레드 히스토리 수집 ✅

- [x] `SocketModeRunner`에 `AppProperties` 주입
- [x] 멘션 수신 시 `thread_ts`로 `ctx.client().conversationsReplies()` 호출
- [x] 봇 메시지 (`bot_id` 존재) → `Role.ASSISTANT`, 사용자 → `Role.USER` 변환
      멘션 태그(`<@U...>`) 제거 후 공백 정리
- [x] 루트 메시지(첫 멘션, `currentTs == threadTs`) → history 빈 목록 (단발성과 동일)
- [x] `app.router.history-turns - 1`개만 슬라이스 → `ChatCommand.history` 전달
      (DefaultChatService가 현재 질문을 추가해 총 history-turns개가 됨)
- [x] REST `ChatController` → `history = emptyList()` 유지 (변경 없음)

**DoD (사용자 검수 필요):**
- `./gradlew test` 그린 ✅
- Slack에서 "CPU 높은 VM" 멘션 → RESOURCE 분류 → Prometheus 결과 → 스레드 답변
- Slack에서 맥락 후속 질문("1번 상세 알려줘") → RESOURCE 분류 (히스토리 전달 확인)
- `POST /api/chat` DOC 질문 → 정상 RAG 답변 회귀 확인

---

## 테스트 전략

| 유형 | 파일 | 조건 | 내용 |
|---|---|---|---|
| 순수함수 | `PromQlBuilderTest`, `ResourceAnswerTemplateTest` | 항상 실행 | PromQL 문자열 일치, 답변 포맷 |
| 스텁 | `LlmQuestionRouterTest`, `LlmMetricQueryExtractorTest` | 항상 실행 | 파싱·폴백·입력 검증 |
| env-gated | `RoutingAccuracyTest`, `MetricExtractionAccuracyTest` | `OPENAI_API_KEY` | 실제 분류·추출 품질 |
| env-gated | `PrometheusQueryTest` | `PROMETHEUS_URL` | 실제 Prometheus 도달·파싱 |

```bash
# 기본 (항상 그린, 키 불필요)
./gradlew test

# 실제 OpenAI 품질 검증
export $(grep -v '^#' .env | xargs) && ./gradlew test

# 수동 CLI
export $(grep -v '^#' .env | xargs) && ./gradlew routingCli -q --console=plain
export $(grep -v '^#' .env | xargs) && ./gradlew resourceCli -q --console=plain
```
