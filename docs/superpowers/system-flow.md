# 시스템 플로우 & 아키텍처 (2차 목표 기준)

> 이 문서는 **지금까지 구현된 전체 시스템**의 플로우와 각 모듈의 역할을 한 곳에 정리한다.
> Phase 1~7 기반 문서: [`../architecture.md`](../architecture.md) · [`../requirements.md`](../requirements.md)
> 2차 개발 계획: [`../future/resource-prometheus-path.md`](../future/resource-prometheus-path.md)

---

## 1. 전체 시스템 플로우 (배선 완료 후 목표 상태)

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
         │  ③ QuestionRouter.route(history)         │  ← LLM 호출 #1
         │         │                                │
         │   ┌─────┼──────┐                         │
         │  DOC  RESOURCE CLARIFY                   │
         └────┼──────┼──────┼────────────────────────┘
              ▼      ▼      ▼
           RAG   Resource  되물음
          경로    경로      응답
```

> **현재 상태**: `DefaultChatService`는 항상 DOC 경로만 흐름 (라우터 미배선 — R4에서 연결 예정).

---

## 2. DOC 경로 (RAG 파이프라인) — Phase 1~7 완성

```
ChatCommand
    │
    ▼
임베딩 (OpenAI text-embedding-3-small, 1536d)   ← OpenAI 호출 #1
    │  질문 벡터 1회 계산, 이후 재사용 (불변식 2)
    ▼
documents 검색 (PgVectorStore, cosine, top-5)
    │
    ├── 0건 → "관련 문서 없음" (생성 0회, 불변식 3)
    │
    ▼
RetrievalPolicy (min-score 0.32 필터)
    │
    ▼
AnswerGenerationService                          ← OpenAI 호출 #2
    │  시스템 규칙 + top-5 청크 + 질문 → gpt-4o-mini
    ▼
ChatResult(answer, sources: [title #chunk])
```

### DOC 경로 파일 맵

| 계층 | 파일 | 역할 |
|---|---|---|
| interfaces | `chat/interfaces/ChatController.kt` | `POST /api/chat` 수신 |
| interfaces | `slack/interfaces/SocketModeRunner.kt` | Slack @mention 수신·ack |
| application | `chat/application/DefaultChatService.kt` | 파이프라인 오케스트레이터 |
| application | `embedding/application/EmbeddingService.kt` | 임베딩 포트 |
| application | `retrieval/application/DocumentSearchService.kt` | 검색 포트 |
| application | `generation/application/AnswerGenerationService.kt` | 생성 포트 |
| application | `guard/application/InputGuard.kt` · `RateLimitGuard.kt` | 입력 검증·레이트리밋 |
| domain | `retrieval/domain/RetrievalPolicy.kt` | min-score 필터 순수함수 |
| infrastructure | `embedding/infrastructure/OpenAiEmbeddingClient.kt` | OpenAI 임베딩 구현 |
| infrastructure | `retrieval/infrastructure/PgVectorDocumentSearch.kt` | pgvector 검색 구현 |
| infrastructure | `generation/infrastructure/OpenAiChatClient.kt` | gpt-4o-mini 생성 구현 |
| infrastructure | `guard/infrastructure/OpenAiModerationClient.kt` | 모더레이션 구현 |
| infrastructure | `slack/infrastructure/SlackResponder.kt` | Slack 응답 게시 |

---

## 3. 질문 라우터 (routing/) — 완성, 미배선

사용자 질문을 `DOC / RESOURCE / CLARIFY` 중 하나로 분류하는 독립 모듈.
파이프라인에는 R4에서 연결된다.

### 플로우

```
List<ConversationMessage>(최근 2턴)
    │
    ▼
LlmQuestionRouter
    │
    ├── 프롬프트 구성 (RoutingPrompts.SYSTEM + few-shot 5개 + 대화 히스토리)
    │
    ▼
LlmClient.complete()                             ← OpenAI 호출 (gpt-4o-mini, strict json_schema)
    │
    ▼
JSON 파싱 → RouteDecision(route, confidence, reason)
    │
    ├── 파싱 실패 or confidence < 0.5 → CLARIFY 폴백 (RoutingPolicy)
    │
    ▼
RouteDecision 반환 + 로그(route·confidence·reason·latencyMs)
```

### 설계 결정

- **LLM 기반 분류**: 규칙 기반으론 "1번 인스턴스 상세 알려줘" 같은 맥락 의존 질문 처리 불가 → LLM 1회 사용
- **최근 2턴 입력**: 직전 어시스턴트 응답을 같이 보내 맥락 후속 질문을 올바르게 분류
- **BOTH 제거**: 실사용 불필요로 제거 (커밋 `77fdfbb`) → DOC / RESOURCE / CLARIFY 3개
- **strict json_schema**: Spring AI 1.1.7 지원 확인 후 사용. 파싱 가드(→ CLARIFY)는 안전망으로 유지
- **미배선 이유**: RESOURCE 경로(resource 모듈) 자체가 없는 상태에서 배선 불가 → R4에서 동시 연결

### 파일 맵

| 계층 | 파일 | 역할 |
|---|---|---|
| domain | `routing/domain/Route.kt` | enum: DOC / RESOURCE / CLARIFY |
| domain | `routing/domain/RouteDecision.kt` | (route, confidence, reason) |
| domain | `routing/domain/ConversationMessage.kt` | (role: USER/ASSISTANT, content) |
| domain | `routing/domain/RoutingPolicy.kt` | confidence < min → CLARIFY 폴백 순수함수 |
| application | `routing/application/QuestionRouter.kt` | 포트 인터페이스 |
| application | `routing/application/LlmQuestionRouter.kt` | 본체: 프롬프트→호출→파싱→폴백→로깅 |
| application | `routing/application/LlmClient.kt` | LLM 호출 seam (원시 JSON 문자열 반환) |
| application | `routing/application/RoutingPrompts.kt` | 시스템 지시문 + few-shot + strict 스키마 |
| infrastructure | `routing/infrastructure/OpenAiRouterLlmClient.kt` | Spring AI ChatClient + Resilience4j |
| interfaces | `routing/interfaces/RoutingCli.kt` | 수동 확인용 CLI (`./gradlew routingCli`) |

### 설정 (`app.router.*`)

| 키 | 기본값 | 의미 |
|---|---|---|
| `app.router.model` | `gpt-4o-mini` | 라우팅 모델 |
| `app.router.temperature` | `0.0` | 결정성 |
| `app.router.min-confidence` | `0.5` | 미만이면 CLARIFY |
| `app.router.history-turns` | `2` | LLM에 넘기는 최근 메시지 수 |

---

## 4. RESOURCE 경로 (resource/) — R1 완성, R2~R4 예정

### R1 — 조건 추출 (완성)

자연어 질문에서 "어떤 지표를, 어떻게 조회할지"를 구조화된 `ResourceQuery`로 변환한다.
Prometheus 호출은 없고 추출만 한다.

```
List<ConversationMessage>
    │
    ▼
LlmMetricQueryExtractor
    │
    ├── 프롬프트 구성 (ResourcePrompts.SYSTEM + few-shot 6개 + strict json_schema)
    │   └── 스키마에 MetricPattern enum 목록 포함 (INSTANCE_CPU 등 6종)
    │
    ▼
LlmClient.complete()                             ← OpenAI 호출 (gpt-4o-mini, strict json_schema)
    │
    ▼
JSON 파싱 → RawExtraction(clarificationNeeded, metric, sort, topN, window, project, confidence)
    │
    ├── clarificationNeeded=true → NeedsClarification(되물음 메시지)
    ├── confidence < 0.5        → NeedsClarification
    ├── 알 수 없는 metric 값    → NeedsClarification
    │
    ▼
Resolved(ResourceQuery)
    ├── topN: clamp(1~20)
    ├── window: 빈값이면 defaultWindow(5m)
    └── project: 빈문자열이면 null

로그 3줄:
  extraction-raw     question / model / latencyMs / raw json
  extraction-resolved question / metric / sort / topN / window / project / confidence
  extraction-clarify  question / confidence / message
```

### R1 파일 맵

| 계층 | 파일 | 역할 |
|---|---|---|
| domain | `resource/domain/MetricPattern.kt` | 지원 지표 enum (6종) |
| domain | `resource/domain/ResourceQuery.kt` | 조회 조건 (metric, sort, topN, window, project) |
| domain | `resource/domain/ResourceExtraction.kt` | sealed: Resolved(query) / NeedsClarification(msg) |
| application | `resource/application/MetricQueryExtractor.kt` | 추출기 포트 인터페이스 |
| application | `resource/application/LlmMetricQueryExtractor.kt` | 본체: 프롬프트→호출→파싱→폴백→로깅 |
| application | `resource/application/ResourcePrompts.kt` | 시스템 지시문 + few-shot + strict 스키마 생성 |
| interfaces | `resource/interfaces/ResourceCli.kt` | 수동 확인용 CLI (`./gradlew resourceCli`) |

### 설정 (`app.resource.*`)

| 키 | 기본값 | 의미 |
|---|---|---|
| `app.resource.extraction-model` | `gpt-4o-mini` | 조건추출 모델 |
| `app.resource.temperature` | `0.0` | 결정성 |
| `app.resource.min-confidence` | `0.5` | 미만이면 NeedsClarification |
| `app.resource.default-window` | `5m` | window 미지정 시 기본값 |
| `app.resource.default-top-n` | `5` | topN 미지정 시 기본값 |
| `app.resource.prometheus.base-url` | env `PROMETHEUS_URL` | Prometheus 엔드포인트 |

---

### R2~R4 — 예정 (플로우 목표 상태)

```
[R1 완료 이후]
Resolved(ResourceQuery)
    │
    ▼  R2
MetricCatalog.lookup(metric)
    └── MetricCatalogEntry(pattern, rawMetric, groupBy, unit)
    │
    ▼  R2
PromQlBuilder.build(query, entry)              ← 순수함수, LLM 무호출
    └── PromQL 문자열
    │   예(CPU): topk(5, (sum by(domain)(rate(libvirt_domain_info_cpu_time_seconds_total[5m]))
    │              / on(domain) libvirt_domain_info_virtual_cpus * 100)
    │              * on(domain) group_left(instance_name,project_name) libvirt_domain_openstack_info)
    │
    ▼  R3
PrometheusClient.query(promql)                 ← HTTP GET /api/v1/query
    └── List<MetricSample>(labels, value, unit)
    │
    ▼  R3
ResourceAnswerTemplate(query, samples)         ← 템플릿 답변, LLM 무호출
    └── ChatResult(answer, sources)
    │
    ▼  R4
DefaultChatService에 배선
    (router.route() → RESOURCE → ResourceService)
```

**비용 요약 (R4 완료 후)**: 질문당 유료 LLM 호출 최대 2회 — 라우팅 #1 + 조건추출 #2.
Prometheus 조회·템플릿 답변은 LLM 무호출 (불변식 3·5 준수).

---

## 5. 지원 지표 목록 (MetricPattern / R2 카탈로그)

| enum 키 | 의미 | PromQL 패턴 |
|---|---|---|
| `INSTANCE_CPU` | VM CPU 사용률 (%) | `ratio_topk` |
| `INSTANCE_MEMORY` | VM 메모리 사용률 (%) | `gauge_topk` |
| `INSTANCE_NETWORK_RX` | 네트워크 수신량 | `counter_rate_topk` |
| `INSTANCE_NETWORK_TX` | 네트워크 송신량 | `counter_rate_topk` |
| `INSTANCE_DISK_READ` | 디스크 읽기량 | `counter_rate_topk` |
| `INSTANCE_DISK_WRITE` | 디스크 쓰기량 | `counter_rate_topk` |

---

## 6. 개발 진척도

| 모듈 | 상태 | 비고 |
|---|---|---|
| DOC 파이프라인 (Phase 1~7) | ✅ 완성 | 임베딩→검색→생성·가드·Slack |
| 시맨틱 캐시 (Phase 4) | ⏸ 고도화 보류 | 1차 미구현, seam만 유지 |
| `routing/` 질문 라우터 | ✅ 완성, 미배선 | R4에서 파이프라인 연결 |
| `resource/` R1 조건추출 | ✅ 완성 | CLI 테스트 가능 |
| `resource/` R2 카탈로그·PromQlBuilder | 🔲 예정 | |
| `resource/` R3 Prometheus 클라이언트 | 🔲 예정 | `PROMETHEUS_URL` 필요 |
| `resource/` R4 파이프라인 배선 | 🔲 예정 | 라우터+RESOURCE 동시 연결 |
| Slack 히스토리 (핸드오프 B) | 🔲 예정 | Slack 스레드 → ConversationMessage |

---

## 7. LLM 호출 비용 요약

| 시나리오 | 임베딩 | 라우팅 | 추출 | 생성 | 합계 |
|---|---|---|---|---|---|
| 레이트리밋 초과 | 0 | 0 | 0 | 0 | **0** |
| 유해 입력 차단 | 0 | 0 | 0 | 0 | **0** |
| DOC, 검색 0건 | 1 | 1 | 0 | 0 | **2** |
| DOC, 검색 성공 | 1 | 1 | 0 | 1 | **3** |
| RESOURCE, 조건 모호 | 0 | 1 | 1 | 0 | **2** |
| RESOURCE, 조건 추출 성공 | 0 | 1 | 1 | 0 | **2** |
| CLARIFY (되물음) | 0 | 1 | 0 | 0 | **1** |

> 라우팅 전 차단(레이트리밋·유해입력)이면 LLM 0회. RESOURCE 경로는 임베딩 불필요 (구조적 배제).

---

## 8. 테스트 실행 방법

```bash
# 단위·스텁 테스트 (항상 실행, API 키 불필요)
./gradlew test

# 라우터 분류 정확도 + 추출 정확도 (실제 OpenAI)
export $(grep -v '^#' .env | xargs) && ./gradlew test

# 라우터 수동 테스트
export $(grep -v '^#' .env | xargs) && ./gradlew routingCli -q --console=plain

# 조건 추출 수동 테스트
export $(grep -v '^#' .env | xargs) && ./gradlew resourceCli -q --console=plain

# 서버 실행 (Slack 비활성, REST만)
export $(grep -v '^#' .env | xargs) && SLACK_APP_TOKEN= SLACK_BOT_TOKEN= ./gradlew bootRun
```
