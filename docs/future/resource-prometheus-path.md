# 개발 계획 — RESOURCE(Prometheus) 경로 신규 모듈 〔핸드오프 (C)〕

> 작성 2026-06-23 · 상위 계획 [`../plan.md`](../plan.md) 형식을 따른다.
> 선행 맥락: 핸드오프 [`router-integration-and-prometheus.md`](router-integration-and-prometheus.md) · 2차 설계 그림(`2차_chatbot.pdf`).
> 기술 규약: [`../architecture.md`](../architecture.md) · 요구사항 불변식: [`../requirements.md`](../requirements.md).
> Phase R1 → R4 순차, 각 Phase는 **완료조건(DoD)** 충족 후 다음으로. 각 Phase는 자체 빌드·검수 가능한
> 최소 단위(`../process.md` 규약 — 개발→검수 사이클). 카탈로그 내용(R2)·Prometheus 도달성(R3)은 외부 의존 → 해당 Phase에서 확정.

---

## Context (왜 하는가)

2차 챗봇은 RAG(DOC) 전용 파이프라인을 인프라 실시간 지표 조회(**RESOURCE**)로 확장한다.
라우터(`routing/`)는 `DOC / RESOURCE / CLARIFY` 분류 모듈까지 **완성**됐으나 파이프라인에 **미연결**이고
(`DefaultChatService`는 항상 DOC), **RESOURCE 경로 자체는 전혀 없다(그린필드)**.

**핵심 원칙 (PDF가 핸드오프 §6 열린 질문에 답):** LLM은 PromQL을 직접 생성하지 않는다.
LLM은 카탈로그 등록 "조회 조건"만 추출하고, 서버가 Metric Catalog + PromQL Pattern Template으로
실제 쿼리를 조립한다 → 메트릭/라벨 환각 원천 차단(불변식 5).

**범위(확정):** PDF Step 3 — Prometheus 메트릭 TopN 한 갈래 + 최소 배선.
**제외(후속):** Step 2(자원 DB 상태조회), Step 4(DB+Prometheus 조합), Slack 히스토리(핸드오프 B). 자원 DB 스키마 무의존.

**확정 결정:** ① 답변 = 템플릿 포맷(**LLM 무호출**) ② 카탈로그는 사용자 엑셀(메트릭·라벨)로 채움 ③ 조건추출은
기존 `routing/application/LlmClient` seam 재사용(generic strict-json) ④ 히스토리 타입 `ConversationMessage` 재사용.

---

## 전체 설계도

### ① 파이프라인 흐름 (라우터 배선 후)

```
 [REST POST /api/chat]      [Slack @mention]
            \                    /
             ▼                  ▼
         ChatCommand(question, userId, history)
                      │
                      ▼
 ┌──────────────────────────────────────────────────┐
 │            DefaultChatService                       │
 │  ① RateLimitGuard(user별)  ──초과─▶ 안내 (유료호출 0) │
 │  ② InputGuard(금칙어+Moderation) ─차단─▶ 안내 (0)    │
 │  ③ QuestionRouter.route(history) ◀── LLM #1 라우팅   │
 │              │  route?                              │
 │      ┌───────┼─────────────┐                        │
 │     DOC   RESOURCE       CLARIFY                    │
 └──────┼───────┼─────────────┼────────────────────────┘
        ▼       ▼             ▼
   기존 RAG  ResourceService  되물음 응답
  임베딩→검색   (②확대)       (유료호출 0)
   →생성
```

### ② RESOURCE 경로 내부 (이번 작업의 본체)

```
 ResourceService.handle(history)
    │
    ▼
 ⓐ MetricQueryExtractor.extract(history) ◀── LLM #2 조건추출 (strict json_schema)
    │   └▶ ResourceExtraction
    │        ├ NeedsClarification ─▶ 되물음 (Prometheus 0, 생성 0)
    │        └ Resolved(ResourceQuery: metric, sort, topN, window, project)
    ▼
 ⓑ MetricCatalog.lookup(metric) ─▶ MetricCatalogEntry(pattern, rawMetric, groupBy, scale, unit)
    ▼
 ⓒ PromQlBuilder.build(query, entry) ─▶ PromQL 문자열   (순수함수 · pattern 템플릿)
    │   예(CPU)) topk(1, (sum by(domain)(rate(libvirt_domain_info_cpu_time_seconds_total[5m]))
    │              / on(domain) libvirt_domain_info_virtual_cpus * 100)
    │              * on(domain) group_left(instance_name, project_name) libvirt_domain_openstack_info)
    ▼
 ⓓ PrometheusClient.query(promql) ──HTTP GET /api/v1/query──▶ Prometheus
    │   └▶ List<MetricSample>(labels, value, unit)
    ▼
 ⓔ ResourceAnswerTemplate(query, samples) ─▶ ChatResult(answer, sources)   (LLM 무호출)
```

### ③ 계층 구조 (`com.okestro.ragbot.resource` — routing 4계층 미러)

```
 interfaces       ResourceCli                         ← ./gradlew resourceCli (수동확인)
 application       MetricQueryExtractor(port) · LlmMetricQueryExtractor · ResourcePrompts
                   MetricCatalog · PrometheusClient(port) · ResourceAnswerTemplate · ResourceService
 domain            ResourceQuery · MetricPattern(enum) · MetricCatalogEntry · MetricSample
                   ResourceExtraction(sealed) · PromQlBuilder(순수함수 object)
 infrastructure    HttpPrometheusClient (RestClient + Resilience4j 'prometheus')
 ── 재사용 ──      routing: LlmClient/LlmRequest/OpenAiRouterLlmClient · domain.ConversationMessage
```

**비용:** 질문당 유료 호출 = 라우팅(LLM#1) + 조건추출(LLM#2) = **2회**. CLARIFY/조건누락/미지원 메트릭은
추출에서 끊겨 Prometheus·생성 0회(불변식 4). Prometheus 조회·템플릿 답변은 무료.

---

## 아키텍처 규약 (기존 프로젝트 정합)

> `architecture.md`의 규약을 그대로 이어받는다. 새 약속만 기록.

- **패키지**: `com.okestro.ragbot.resource` — `routing/` 4계층 미러(동일 규약). `chat/` 과 `routing/` 은 별도 최상위 모듈로 공존.
- **`AppProperties.Resource`**: `common.config.AppProperties`의 nested inner class로 추가. 신규 의존성 없음(기존 `@ConfigurationPropertiesScan` 범위).
- **TimeLimiter 불필요**: `plan.md` Phase 5-c-2 결정과 동일하게 **`spring.http.client.read/connect-timeout`(RestClient 레벨 타임아웃)으로 대체**. `resilience4j`에는 `retry`+`circuitbreaker`만 추가. RestClient 빈은 `resource/infrastructure/HttpPrometheusClient`가 직접 구성.
- **`ConversationMessage` 의존성 방향**: R4에서 `chat.application.ChatCommand`에 `history: List<ConversationMessage>`를 추가하면 `chat` 모듈이 `routing/domain/`을 참조하는 의존성이 생긴다. **R4 착수 전 해결책 결정 필요** — 선택지: ① `ConversationMessage`를 `chat/domain/`으로 이동 후 `routing`이 재참조(권장 — chat이 오케스트레이터) ② 현 위치 유지 후 `chat → routing` 단방향 의존 허용.
- **불변식 준수 체크**: ① 임베딩 재사용(불변식 2) — RESOURCE 경로는 임베딩 불필요(구조적 배제 ✅) ② LLM 무호출 답변(불변식 3 보완) — 템플릿 포맷으로 생성 0회 ③ 조건 미충족 시 Prometheus 0회(불변식 4 준수) ④ 환각 방지(불변식 5) — LLM이 PromQL 미생성, 카탈로그+패턴 서버 조립.

---

## 튜닝 단일화 (`app.resource.*` 신규 — 하드코딩 금지, 불변식 7)

| 키                                                         | 의미                  | 초기값                   | 비고                                                                                     |
| ---------------------------------------------------------- | --------------------- | ------------------------ | ---------------------------------------------------------------------------------------- |
| `app.resource.extraction-model`                            | 조건추출 모델         | `gpt-4o-mini`            | 작고 빠른 모델                                                                           |
| `app.resource.temperature`                                 | 결정성                | `0.0`                    |                                                                                          |
| `app.resource.min-confidence`                              | 미만/누락 → 되물음    | `0.5`                    | 라우터 관례 동일                                                                         |
| `app.resource.default-window`                              | window 미지정 시      | `5m`                     |                                                                                          |
| `app.resource.default-top-n`                               | topN 미지정 시        | `5`                      |                                                                                          |
| `app.resource.prometheus.base-url`                         | Prometheus 엔드포인트 | env `PROMETHEUS_URL`     | 예 `https://10.255.40.10:11909` (환경 의존→env)                                          |
| `app.resource.prometheus.connect-timeout`                  | HTTP 연결 타임아웃    | `3s`                     | TimeLimiter 대신 RestClient 레벨 제한(architecture.md 5-c-2 결정 동일)                   |
| `app.resource.prometheus.read-timeout`                     | HTTP 읽기 타임아웃    | `10s`                    | 무거운 rate+조인 쿼리 관찰 → 10 s 여유 부여                                              |
| `app.resource.catalog`                                     | Metric Catalog        | (아래 확정 6종)          | metric키→(pattern,rawMetric,groupBy,scale,unit) Map                                      |
| `app.resource.label-normalization`                         | 라벨 표준화           | (최소)                   | 라이브 libvirt는 `domain` 통일 → 변종 매핑 거의 불필요. 이름·프로젝트는 info-join이 담당 |
| `resilience4j.{retry,circuitbreaker}.instances.prometheus` | 회복탄력성            | (`openai` 인스턴스 복제) | TimeLimiter 제외(HTTP 타임아웃으로 대체)                                                 |

저장 위치: `AppProperties.Resource` nested(@ConfigurationProperties, 신규 의존성 0). 분량 커지면 별도 `metric-catalog.yml` 분리(차후).

---

## Phase R1 — 조건추출 골격 (외부 I/O = OpenAI 1종)

> resource 모듈 뼈대 + 자연어→조회조건(strict json_schema). Prometheus·카탈로그 없이 추출만. 엑셀 무의존.

- [ ] `resource/domain`: `ResourceQuery`(metric,sort,topN,window,project?), `MetricPattern`(enum 5종),
      `ResourceExtraction`(sealed: `Resolved`/`NeedsClarification`). routing `RouteDecision`/`GuardDecision` 미러.
- [ ] `resource/application`: `MetricQueryExtractor`(port) + `LlmMetricQueryExtractor`(본체) +
      `ResourcePrompts`(system+few-shot+strict 스키마, **metric enum은 카탈로그 키 목록**). `LlmClient` seam 재사용.
- [ ] 누락/저신뢰/미지원 metric → `NeedsClarification`(되물음 메시지, 이후 유료호출 0). routing `RoutingPolicy` 관례.
- [ ] `interfaces/ResourceCli`(stub 단계 — 추출 결과만 출력) + `build.gradle.kts` `resourceCli` 태스크(`routingCli` 복제).

**DoD(R1):** 빌드/단위테스트 그린(키 불필요). 스텁(`StubLlmClient` 재사용) 기반 — 정상 JSON→`ResourceQuery` 매핑,
누락→`NeedsClarification`, 깨진 JSON→fallback, `lastRequest`로 입력 검증.
(검증: `./gradlew test` 그린. env-gated 추출정확도 테스트는 `OPENAI_API_KEY` 있을 때 — 사용자 검수.)

---

## Phase R2 — Metric Catalog + PromQlBuilder

> 조회조건 → 실제 PromQL 조립. **라이브 Prometheus 실측(2026-06-23)으로 카탈로그 확정.**
> 엑셀 라벨 목록은 라이브와 불일치 — 라이브 `contrabass_exporter`의 libvirt 메트릭은 전부 **`domain`만으로 식별**되고,
> 사람이름·프로젝트는 **`libvirt_domain_openstack_info`에만** 존재(info-join으로 부착). 확정 결정: ① CPU = vCPU 정규화 사용률, ② info-join 이름표기.

**확정 카탈로그(초기 PoC):** groupBy = `domain`(전 메트릭 공통). 모든 결과에 info-join으로 instance_name·project_name 부착.

| 논리 키             | pattern             | rawMetric                                    | 비고                                 |
| ------------------- | ------------------- | -------------------------------------------- | ------------------------------------ |
| INSTANCE_CPU        | `ratio_topk`        | `libvirt_domain_info_cpu_time_seconds_total` | rate ÷ `virtual_cpus` × 100 (0~100%) |
| INSTANCE_MEMORY     | `gauge_topk`        | `libvirt_domain_memory_stats_used_percent`   | 이미 % — 계산 불필요                 |
| INSTANCE_NETWORK_RX | `counter_rate_topk` | `..._interface_stats_receive_bytes_total`    | target_device들 sum                  |
| INSTANCE_NETWORK_TX | `counter_rate_topk` | `..._interface_stats_transmit_bytes_total`   | target_device들 sum                  |
| INSTANCE_DISK_READ  | `counter_rate_topk` | `..._block_stats_read_requests_total`        | target_device들 sum                  |
| INSTANCE_DISK_WRITE | `counter_rate_topk` | `..._block_stats_write_requests_total`       | target_device들 sum                  |

**PromQL Pattern 템플릿(공통 info-join enrich 포함):**

- 공통 enrich `{E}` = `* on(domain) group_left(instance_name, project_name) libvirt_domain_openstack_info`
- `counter_rate_topk` = `topk({topN}, sum by(domain)(rate({rawMetric}[{window}])) {E})`
- `gauge_topk` = `topk({topN}, {rawMetric} {E})`
- `ratio_topk`(CPU) = `topk({topN}, (sum by(domain)(rate({rawMetric}[{window}])) / on(domain) libvirt_domain_info_virtual_cpus * 100) {E})`

작업:

- [ ] `resource/domain`: `MetricCatalogEntry`(pattern,rawMetric,unit) + `PromQlBuilder`(object 순수함수, 위 3패턴 + 공통 enrich).
- [ ] `resource/application/MetricCatalog`(@Component): config 로딩, metric enum 목록 제공(R1 스키마가 참조).
- [ ] `app.resource.catalog` → `application.yml`(위 6키) + `AppProperties.Resource` 매핑. 라벨 정규화는 최소(domain 통일).

**DoD(R2):** `(ResourceQuery, MetricCatalogEntry) → 기대 PromQL 문자열` 순수함수 단위테스트(키 불필요, 항상 그린).
CPU 키 → 위 `ratio_topk` PromQL과 **정확히 일치**. 실제 값 동작은 R3 라이브 검증에서 확인.

---

## Phase R3 — Prometheus 클라이언트 + 템플릿 답변 〔Prometheus 도달성 의존〕

> PromQL을 실제 Prometheus에 던지고 결과를 템플릿 답변으로. `https://10.255.40.10:11909` 도달성 선확인.

- [ ] `resource/domain/MetricSample`(labels,value,unit) + `display()`(routing `Source.display()` 미러).
- [ ] `resource/application/PrometheusClient`(port) + `infrastructure/HttpPrometheusClient`(@Component):
      `RestClient` + `/api/v1/query` + Jackson 파싱 + `@Retry/@CircuitBreaker(name="prometheus")`. `OpenAiChatClient` 미러.
- [ ] `application/ResourceAnswerTemplate`(object 순수함수): 결과행 → 한국어 답변 + 출처(metric/window/대상수). **LLM 무호출**.
- [ ] `application/ResourceService`(@Component): extract→catalog→build→query→template 오케스트레이션. `DefaultChatService` 미러.
- [ ] `ResourceCli`를 E2E로 확장(질문→PromQL→결과→답변 출력).
- [ ] `resilience4j.{retry,circuitbreaker}.instances.prometheus`(`openai` 복제) `application.yml` 추가.

**DoD(R3):** `OPENAI_API_KEY=… PROMETHEUS_URL=… ./gradlew resourceCli` → "cpu 사용량 가장 높은 인스턴스는?"
→ 조립된 PromQL + 실제 Prometheus 결과 + 템플릿 답변(출처) 출력. env-gated `PrometheusQueryTest`(`@EnabledIfEnvironmentVariable`).
(선행: `curl -k https://10.255.40.10:11909/api/v1/query?query=up` 도달성·TLS 확인 — 사용자 검수.)

---

## Phase R4 — 파이프라인 배선 (최소 A)

> 라우터+RESOURCE를 실제 챗 파이프라인에 연결. DOC 경로 회귀 보존.

- [ ] **선행(착수 전)**: `ConversationMessage`를 `routing/domain/` → `chat/domain/`으로 이동 후 `routing`이 재참조하도록 패키지 정리(의존성 방향 규약 — 아키텍처 규약 섹션 참고).
- [ ] `chat/application/ChatCommand`에 `history: List<ConversationMessage> = emptyList()` 추가. REST는 빈 히스토리(단발성, 허용).
- [ ] `DefaultChatService.handle`: 입력가드 직후 `router.route(history)` + `when(route)` 분기 —
      `DOC`→기존(그대로), `RESOURCE`→`ResourceService`, `CLARIFY`→되물음(유료호출 0).
- [ ] 케이스별 호출수 로깅(`routingCalls/extractionCalls/llmCalls`) — 기존 관례.

**DoD(R4):** DOC 경로 회귀(`DefaultChatServiceTest`) 그린 + RESOURCE 질문이 REST→챗 응답으로 흐름.
(검증: `./gradlew test` 그린. 런타임 `POST /api/chat` RESOURCE 질문→답변, DOC 질문 정상 — 사용자 검수.)

---

## 테스트 전략 (기존 3분할 관례 — `src/test/kotlin/com/okestro/ragbot/resource/`)

- **순수함수**(`PromQlBuilderTest`, `ResourceAnswerTemplateTest`): `kotlin.test`, 키 불필요, 항상 그린.
- **스텁**(`LlmMetricQueryExtractorTest`): `StubLlmClient` 재사용, `lastRequest` 입력검증, `AppProperties(resource=…)` 주입.
- **env-gated 통합**: `MetricExtractionAccuracyTest`(`OPENAI_API_KEY`), `PrometheusQueryTest`(`PROMETHEUS_URL`). CI 기본 skip.

## 열린 의존성 / 리스크

1. **카탈로그 확정됨**(2026-06-23 라이브 실측). 신규 메트릭 추가 시 위 표에 행 추가.
2. **Prometheus 신뢰성/성능** → 실측: 도달은 되나 단순 쿼리는 즉시 응답, **rate+조인 등 무거운 쿼리는 타임아웃 관찰**(엔드포인트/프록시 부하 추정). HTTPS 내부 IP·사설 인증서. → R3에서 Resilience4j `prometheus` 타임아웃·서킷 **필수**, 무거운 조인 쿼리 성능 재검증, `RestClient` TLS 신뢰 설정(테스트 한정).
3. **REST 맥락 한계** → REST는 스레드 없어 맥락 후속질문 불가(허용). Slack 히스토리는 범위 밖(핸드오프 B).

## 검증 (E2E)

1. `./gradlew test` — 순수함수·스텁 그린(키 없이).
2. `OPENAI_API_KEY=… ./gradlew test` — 추출정확도 통과.
3. `curl -k https://10.255.40.10:11909/api/v1/query?query=up` — 도달성 확인.
4. `OPENAI_API_KEY=… PROMETHEUS_URL=… ./gradlew resourceCli` — "cpu 가장 높은 인스턴스" → PromQL+결과+답변.
5. 배선 후 REST `POST /api/chat` RESOURCE 질문 → 응답 + DOC 회귀 그린.
