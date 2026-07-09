# [이슈] 챗봇 집계 응답을 웹 위젯으로 — 백엔드 구현

- **작성일**: 2026-07-09
- **담당**: 백엔드 (이 저장소를 처음 보는 분 기준으로 작성)
- **관련 설계(마스터)**: [`2026-07-09-chatbot-aggregation-widgets-design.md`](./2026-07-09-chatbot-aggregation-widgets-design.md)
- **상태**: 설계 확정 + **라이브 Prometheus 검증 완료** → 구현 대기
- **전제**: 이 문서는 위 설계를 **바꾸지 않는다**. 라이브 검증으로 확정된 사실(특히 쿼터 데이터 소스)만 반영해 "실제로 어떻게 만드는지"를 파일 단위로 풀어 쓴 것이다.

---

## 0. 우리가 지금 뭘 만들려는 건가 (사람이 읽는 설명)

> 이 절만 읽어도 "무엇을·왜" 만드는지 이해되도록 쓴다. 코드는 아래(6절)에 있다.

### 지금 벌어지는 일

우리 사내 챗봇(콘트라베이스)은 두 종류의 질문에 답한다.

1. **문서 질문**(DOC) — "이 API 어떻게 써요?" → 사내 문서를 검색해 답변 (기존 RAG)
2. **인프라 지표 질문**(RESOURCE) — "CPU 제일 높은 VM 알려줘" → OpenStack 위에서 돌아가는 Prometheus를 조회해 답변

지금 2번 질문의 답은 **전부 평문(줄글)** 이다. 예를 들어 이렇게 나온다:

```
CPU 사용률이 높은 인스턴스 (프로젝트: service-prod):
  1. web-prod-07 [service-prod] — 91.2%
  2. api-prod-02 [service-prod] — 87.0%
  3. batch-11 [data-platform] — 73.4%
```

내용은 맞지만, **집계(순위·비율·개수)는 줄글로 읽으면 느리다.** 어느 게 위험한지 한눈에 안 들어온다.

### 이 이슈가 끝나면 달라지는 것

같은 답을 **웹 채팅 위젯에서 표·막대·도넛·게이지·배너**로 보여준다. 사람이 훑기만 해도
"web-prod-07이 빨간색(임계 초과)이고 CPU 91%구나"가 바로 보인다. 구체적으로:

| 사용자가 물으면 | 지금 (평문) | 이 이슈 후 (웹 위젯) |
|---|---|---|
| "CPU 높은 VM" | 순위 줄글 | **막대 랭킹 표** — 값이 클수록 긴 막대, 임계 초과는 빨강 |
| "볼륨 몇 개야?" | "볼륨 342건입니다" | **큰 숫자 카드** — 342, 조건 라벨 |
| "인스턴스 상태 분포" | (지금 없음) | **도넛** — ACTIVE/SHUTOFF/ERROR 비율 |
| "프로젝트별 쿼터 사용량" | (지금 없음) | **게이지** — 프로젝트별 vCPU/메모리/디스크 사용/한도 |
| (자동) 위험 요약 | (없음) | **경고 배너** — "임계 초과 노드 2대: web-prod-07, api-prod-02" |
| (자동) 다음에 물어볼 것 | (없음) | **연관질문 칩** — "web-prod-07 메모리는?" 클릭 시 재질문 |

### 반드시 지키는 3가지 원칙 (설계 불변식)

1. **평문 답변은 그대로 항상 같이 준다.** 위젯은 시각적 보강일 뿐이다.
   Slack은 위젯을 못 그리므로 **평문을 그대로 쓴다**(Slack 경험은 1도 안 바뀐다).
   화면 낭독기(스크린리더)도 평문을 읽는다.
2. **위젯 때문에 유료 AI 호출을 늘리지 않는다.** 집계는 Prometheus/DB 쿼리로,
   위젯은 그 결과를 **모양만 바꾸는 순수 변환**으로, 연관질문 칩은 **규칙**으로 만든다. (LLM 0회)
3. **큰 구조를 안 바꾼다.** 기존 평문 템플릿(`ResourceAnswerTemplate`)은 그대로 두고,
   그 **옆에** 위젯 생성기(`WidgetBuilder`)를 추가한다. 응답에 `widgets` 필드를 하나 더 붙인다.

### 쉽게 (음식점 비유)

- 지금은 주방(백엔드)이 요리를 **글로 설명해서** 내보낸다. "감자 3개, 당근 2개…"
- 앞으로는 같은 요리를 **접시에 예쁘게 담아서**(위젯) 내보낸다. 글 설명도 접시 옆에 같이 둔다(평문).
- 주방과 홀(프론트)이 **"이 메뉴는 이런 접시에 나간다"(위젯 JSON 스키마)** 를 미리 약속한다.
  약속만 되면 주방·홀이 서로 안 기다리고 동시에 일한다.

---

## 1. 지금 코드는 어떻게 생겼나 (현재 상태 — 반드시 이해하고 시작)

파이프라인은 이미 다 돌아간다. **문제는 결과가 컨트롤러에 닿기 전에 전부 "평문 String"으로 뭉개진다**는 것이다.
위젯을 만들려면 이 "뭉개짐"을 풀어 **구조화된 데이터를 끝까지 흘려보내야** 한다. 아래가 그 경로다.

```
질문
 → DefaultChatService.handle()                     (chat/application/DefaultChatService.kt)
     → 라우팅 RESOURCE → handleResource()
         → resourceService.handle(history)          (resource/application/DefaultResourceService.kt)
             → 조건추출 → PromQL 조립 → Prometheus 조회 → List<MetricSample>   ← 여기까진 구조가 살아있음
             → ResourceAnswerTemplate.build(...)     → String 평문             ← 여기서 구조가 죽음
             → return ResourceService.Result(answer: String, ...)              ← String만 반환
         → return ChatResult(answer, sources=emptyList())                      ← 구조 버림
 → ChatController → ChatResponse(answer, sources)                              ← widgets 필드 자체가 없음
```

관련 현재 시그니처(그대로 인용):

```kotlin
// chat/application/ChatResult.kt
data class ChatResult(val answer: String, val sources: List<String>)

// chat/interfaces/ChatResponse.kt   ← POST /api/chat 의 JSON DTO
data class ChatResponse(val answer: String, val sources: List<String>)

// resource/application/ResourceService.kt
interface ResourceService {
    data class Result(val answer: String, val needsClarification: Boolean = false)
    fun handle(history: List<ConversationMessage>): Result
}

// resource/domain/MetricSample.kt   ← 조회 결과 1행 (이 구조가 살아서 위젯까지 가야 함)
data class MetricSample(val instanceName: String, val value: Double, val unit: String, val projectName: String? = null)
```

> **핵심 파악**: `MetricSample`/`InventoryResult` 같은 **구조체는 이미 존재**하고 조회도 잘 된다.
> 우리가 할 일은 그걸 String으로 뭉개기 **전에** 옆에서 위젯으로도 변환해, 응답 끝까지 같이 흘려보내는 것.

이미 잘 갖춰진 것들(재사용):
- `PrometheusClient`(`/api/v1/query` instant) + `HttpPrometheusClient` — TLS 우회·Resilience4j 적용 완료.
- `PromQlBuilder` — `topk/bottomk` PromQL 조립(순수함수).
- INVENTORY(cb_common) 경로 — **코드 완성**되어 있으나 `app.resource.inventory.enabled=false`로 꺼져 있음.
- 정적 프론트 `src/main/resources/static/chat-widget/` — 채팅 버블 UI(HTML/CSS/JS).

아직 **없는 것**(이 이슈에서 새로 만듦):
- 응답의 `widgets` 필드, `Widget` 도메인 타입, `WidgetBuilder`.
- `query_range`(시계열) 호출 — 지금 클라이언트는 instant 조회만 한다. (스파크라인용, 1b)
- 쿼터/상태 집계 쿼리 — libvirt가 아니라 `openstack_*` 메트릭 계열(아래 4절, 라이브 확인됨).

---

## 2. 무엇을 만드나 — 위젯 목록 + 확정된 데이터 소스

설계의 Phase 1 세트를 그대로 따른다. **1a(재표현)** 를 먼저 완성해 배포하고, **1b(신규 집계)** 를 이어서 붙인다.

| # | 위젯 | 티어 | 데이터 소스 (라이브 검증 완료 — 7절) | 새 쿼리? |
|---|---|---|---|---|
| 1 | `metric_rank` (TopN 랭킹) | 1a | 기존 `PromQlBuilder` topk + `MetricSample` | 아니오 |
| 2 | `inventory_count` (개수 카드) | 1a | cb_common(코드 있음, `enabled=true` 필요) | 아니오 |
| 3 | 연관질문 칩 `followups` | 1a | `ResourceQuery` + top 결과로 **규칙 생성** | 아니오 |
| 4 | `threshold_banner` (경고 배너) | 1b | 기존 libvirt에 `count(식 > 임계)` **자작** | 예(자작) |
| 5 | `quota_gauge` (쿼터 게이지) | 1b | **Prometheus openstack-exporter** `openstack_nova/cinder_limits_*` | 예 |
| 6 | `status_donut` (상태 도넛) | 1b | **Prometheus** `count by(status)(openstack_nova_server_status)` | 예 |
| 7 | `metric_rank` 스파크라인 | 1b | Prometheus **range 쿼리**(`/api/v1/query_range`) — 클라이언트 신규 | 예 |
| 8 | `project_usage_bar` (프로젝트 바) | 1b | **쿼터 사용률로 재정의** (5·6번 소스 재사용) | 예 |

> **설계 대비 정정 2건(라이브 검증 반영, 틀 변경 아님)**
> - **쿼터 소스**: 설계 미결정이던 "cb_common에 쿼터가 있나?" → **답: 없음. 쿼터는 Prometheus openstack-exporter에 있다.** 게다가 `tenant` 라벨에 프로젝트 **이름**이 이미 붙어 있어 별도 이름 조인 불필요.
> - **`project_usage_bar`**: 원설계는 "프로젝트별 사용률 `by(project)`"였으나, 참조 백엔드에도 라이브에도 **프로젝트당 사용률 단일 시계열이 없다.** 위젯 모양(JSON 스키마)은 그대로 두고 **데이터만 "프로젝트별 쿼터 사용률(used/max)"로 재정의**한다. → 5·6번의 소스를 그대로 재사용.

---

## 3. FE ↔ BE 인터페이스 (계약) — 먼저 얼린다

주방(BE)과 홀(FE)의 약속. **이 JSON이 곧 API 규격**이고, 이게 정해지면 BE·FE가 병렬로 간다.

### 3.1 응답 계약 (BE가 내보내는 것)

`ChatResponse`에 필드 2개를 추가한다. 둘 다 **기본값이 빈 배열** → 기존 클라이언트·Slack은 무시하므로 완전 하위호환.

```kotlin
data class ChatResponse(
    val answer: String,                          // (기존) 평문 — Slack 폴백 & 접근성 캡션. 항상 존재
    val sources: List<String>,                   // (기존)
    val widgets: List<Widget> = emptyList(),     // (신규) 시각 위젯. 없으면 빈 배열
    val followups: List<String> = emptyList(),   // (신규) 연관질문 칩. 없으면 빈 배열
)
```

### 3.2 위젯 JSON 스키마 (마스터 계약 — 설계 §13 그대로)

`Widget`은 sealed interface + Jackson 폴리모픽 직렬화. `type` 문자열로 프론트가 렌더러를 고른다.

```jsonc
// 1) metric_rank — TopN 랭킹
{ "type": "metric_rank", "title": "CPU 사용률이 높은 인스턴스", "unit": "%", "window": "5m",
  "promql": "topk(5, ...)", "empty": false,
  "rows": [
    { "instanceName": "web-prod-07", "projectName": "service-prod",
      "value": 91.2, "display": "91.2%", "severity": "CRIT",   // severity: GOOD|WARN|CRIT|null
      "spark": [58,61,60,67,72,80,85,91] }                     // 1b: 없으면 생략
  ]}

// 2) inventory_count — 개수 카드
{ "type": "inventory_count", "label": "볼륨", "total": 342, "condition": "상태=available · 프로젝트 전체" }

// 4) threshold_banner — 경고 배너
{ "type": "threshold_banner", "level": "CRIT", "title": "임계 초과 노드 2대",
  "detail": "CPU 85%↑ : web-prod-07, api-prod-02", "count": 2 }

// 5) quota_gauge — 쿼터 게이지(복수). ⚠ 무제한(-1) 처리 아래 6.5 참고
{ "type": "quota_gauge",
  "items": [
    { "resource": "vCPU", "used": 820, "quota": 1000, "ratio": 0.82, "display": "820 / 1000", "severity": "WARN" },
    { "resource": "vCPU(무제한)", "used": 8, "quota": null, "ratio": null, "display": "8 / 무제한", "severity": null }
  ]}

// 6) status_donut — 상태 분포 도넛
{ "type": "status_donut", "label": "인스턴스", "total": 140,
  "segments": [ { "status": "ACTIVE", "count": 128, "level": "good" },
                { "status": "SHUTOFF", "count": 9, "level": "muted" },
                { "status": "ERROR", "count": 3, "level": "crit" } ] }

// 8) project_usage_bar — 프로젝트별 쿼터 사용률 바 (재정의)
{ "type": "project_usage_bar", "metric": "vCPU", "unit": "%",
  "rows": [ { "projectName": "service-prod", "value": 82.0, "display": "82%", "severity": "WARN" } ] }

// 결과 0건
{ "type": "metric_rank", "title": "...", "unit": "%", "window": "5m", "promql": "...", "empty": true, "rows": [] }

// 조회 실패 / DOC / CLARIFY → widgets 없음(평문 폴백)
// { "answer": "...", "sources": [], "widgets": [] }
```

- `severity`(색상 레벨)와 `display`(포맷 완료 문자열)는 **백엔드가 계산해서 넣는다.** 프론트는 표현만.
- 임계치는 하드코딩 금지 → `application.yml`에서 소유(아래 6.1).

### 3.3 프론트가 이걸 어떻게 먹나 (연동 지점 — FE 담당이지만 계약 이해용)

지금 프론트 `chat-widget.js`는 메시지 하나를 `{ id, role, content, sources }`로 들고,
`renderMessages()`에서 **`bubble.textContent = message.content`** 로 평문만 그린다. 위젯을 붙이는 지점은 두 곳:

1. 응답 파싱(`sendQuestion`): `payload.widgets` / `payload.followups`를 메시지 객체에 실어둔다.
2. 렌더(`renderMessages`): 캡션(평문) 아래에 **`type`별 렌더러**(`renderMetricRank` 등)를 호출해 위젯 DOM을 붙이고, 그 아래 `followups` 칩을 그린다.

**FE 불변식(XSS)**: 위젯 값(`instanceName`·`projectName` 등은 DB/Prometheus 라벨 출처)은 반드시
`textContent`/DOM API로만 삽입한다. `innerHTML` 문자열 조립 금지.

### 3.4 역할 분담 (설계 RnR — 변경 없음)

- **BE**: `Widget` 타입·직렬화, `WidgetBuilder`, `MetricValueFormatter`, severity config, 파이프라인 연결, empty/error, Slack 무영향 회귀. **이 이슈의 범위.**
- **FE**: `chat-widget.js` 타입 디스패치 렌더러 + `chat-widget.css` 스타일 + 접근성(aria). (별도 진행)
- 계약(3.1·3.2)은 **BE가 먼저 확정**해 목업 JSON으로 FE가 선개발.

---

## 4~6. 백엔드 작업 상세 (파일 단위, 현재 → 목표)

> 순서 권장: **6.1 공통 배선 → 6.2 metric_rank → 6.3 inventory_count → 6.4 followups**(여기까지 1a, 배포 가능)
> **→ 6.5 1b 위젯**(threshold_banner → status_donut → quota_gauge → sparkline → project_usage_bar).

### 6.1 공통 배선 (1a 토대 — 이거 없이는 위젯 하나도 못 나감)

**신규 파일**
- `resource/domain/Widget.kt` — `Severity` enum + `Widget` sealed interface + 구현체들 + `@JsonTypeInfo`/`@JsonSubTypes`. (스키마는 3.2 / 설계 §13)
- `resource/application/WidgetBuilder.kt` — **순수함수**. `MetricSample`/`InventoryResult` → `Widget`. severity config 주입.
- `resource/application/MetricValueFormatter.kt` — `%`, `B/s→MB/s`, `req/s` 포맷 단일화(아래 설명).

**수정 파일 (구조를 String으로 뭉개지 말고 끝까지 전달)**

```kotlin
// chat/application/ChatResult.kt   — 필드 2개 추가
data class ChatResult(
    val answer: String,
    val sources: List<String>,
    val widgets: List<Widget> = emptyList(),
    val followups: List<String> = emptyList(),
)

// resource/application/ResourceService.kt — Result 에 widgets/followups 추가
data class Result(
    val answer: String,
    val needsClarification: Boolean = false,
    val widgets: List<Widget> = emptyList(),
    val followups: List<String> = emptyList(),
)

// resource/application/DefaultResourceService.kt — Resolved 분기에서 answer와 '함께' 위젯 생성
val samples = prometheus.query(promql, entry.unit)
val answer  = ResourceAnswerTemplate.build(query, samples)           // (기존) Slack·캡션용 평문
val widget  = WidgetBuilder.metricRank(query, entry, samples, promql) // (신규) 웹 위젯
val chips   = FollowupBuilder.forResource(query, samples)             // (신규) 규칙 칩
return ResourceService.Result(answer, widgets = listOf(widget), followups = chips)
// InventoryResolved 분기도 동일하게 WidgetBuilder.inventoryCount(...) 추가

// chat/application/DefaultChatService.kt (handleResource) — 버리지 말고 전달
val result = resourceService.handle(history)
return ChatResult(result.answer, sources = emptyList(),
                  widgets = result.widgets, followups = result.followups)

// chat/interfaces/ChatResponse.kt — 3.1 대로 필드 추가
// chat/interfaces/ChatController.kt — 매핑에 widgets/followups 추가
return ChatResponse(result.answer, result.sources, result.widgets, result.followups)
```

**포맷 단일화(중요)**: 지금 숫자 포맷은 `ResourceAnswerTemplate.formatValue/formatBytes`에 있다.
평문과 위젯이 **각자 반올림하면 "91.2%" vs "91%"** 로 갈릴 수 있다. `MetricValueFormatter`로 하나만 두고
`ResourceAnswerTemplate`이 그걸 **위임 호출**하게 바꾼다(로직 이동, 동작 동일). `WidgetBuilder`도 같은 걸 부른다.

**severity config (하드코딩 금지, 불변식 7)** — `application.yml`의 `app.resource` 아래 추가:

```yaml
app:
  resource:
    severity:
      warn-percent: 70
      crit-percent: 85
```
→ `AppProperties.Resource`에 `val severity: Severity = Severity()` (`data class Severity(val warnPercent: Int = 70, val critPercent: Int = 85)`) 추가.
`%` 지표에만 severity를 매긴다. `B/s`·`req/s`는 severity=null(색상=액센트).

### 6.2 `metric_rank` (1a)

- 입력: 이미 있는 `ResourceQuery` + `List<MetricSample>` + 조립된 `promql`.
- `WidgetBuilder.metricRank(...)`가 각 `MetricSample`을 `MetricRankRow`로 변환:
  `display`=포맷터 결과, `severity`=(`%` 지표면 warn/crit 경계로 GOOD/WARN/CRIT, 아니면 null).
- 결과 0건이면 `rows=[]`, `empty=true`. 평문 answer는 기존 "찾을 수 없습니다" 유지.

### 6.3 `inventory_count` (1a)

- INVENTORY 경로는 코드가 이미 있고 꺼져 있다. `RESOURCE_INVENTORY_ENABLED=true`(+ cb_common 접속 env)로 켠다.
- `InventoryResult`(kind/total/appliedFilters) → `InventoryCountWidget(label, total, condition)`.
- 켤 수 없는 환경(로컬/CI)에서는 리포지토리 빈이 없어 기존처럼 안내 문구만 나가고 위젯은 붙지 않는다(폴백 유지).

### 6.4 연관질문 칩 `followups` (1a, LLM 0회)

`FollowupBuilder`(순수함수, 규칙). 이미 추출한 `ResourceQuery`와 top 결과로 **최대 3개** 생성:

| 규칙 | 재료 | 예시 칩 |
|---|---|---|
| top 인스턴스 + 지표 전환 | `rows[0].instanceName` | "web-prod-07 메모리는?" |
| 프로젝트 필터 추가(미적용 시) | `rows[0].projectName` | "service-prod만 보기" |
| 다른 지표로 전환 | metric 목록 | "네트워크 송신량 TopN" |

RESOURCE·INVENTORY 경로에만. DOC/CLARIFY는 빈 배열.

### 6.5 Phase 1b 위젯 (신규 집계 — 각 위젯 LLM 0회 유지)

각 위젯은 **새 쿼리/소스**가 필요하다. 7절 라이브 검증으로 소스는 전부 확인됨.

#### (4) `threshold_banner` — 자작 PromQL
- 참조 백엔드엔 이런 쿼리가 없다(그쪽 `threshold/`는 룰 저장 CRUD뿐). **우리가 만든다.**
- 우리가 이미 쓰는 libvirt CPU 식에 임계 비교: `count(<cpu 사용률 식> > {crit-percent})` + 초과 인스턴스명 나열.
- 임계값은 6.1 severity config 재사용.

#### (6) `status_donut` — Prometheus 상태 카운트
- **라이브 확인**: `openstack_nova_server_status`가 `status` 라벨(ACTIVE/SHUTOFF/ERROR…)을 달고 있다.
- 쿼리: `count by(status)(openstack_nova_server_status)` → 상태별 개수. cb_common 불필요.
- `StatusDonutWidget(label, total, segments[{status, count, level}])`. level 매핑: ACTIVE=good, SHUTOFF/PAUSED=muted, ERROR=crit.

#### (5) `quota_gauge` — openstack-exporter 쿼터
- **라이브 확인**: 아래 메트릭이 존재하고, 라벨에 `tenant`(프로젝트 이름) + `tenant_id`(UUID)가 붙어 있다.
  | 자원 | max(한도) | used(사용) |
  |---|---|---|
  | vCPU | `openstack_nova_limits_vcpus_max` | `openstack_nova_limits_vcpus_used` |
  | 메모리 | `openstack_nova_limits_memory_max` | `openstack_nova_limits_memory_used` |
  | 디스크 | `openstack_cinder_limits_volume_max_gb` | `openstack_cinder_limits_volume_used_gb` |
- 프로젝트별로 max/used를 `tenant`로 묶어 `used`, `quota=max`, `ratio=used/max`, `available=max-used` 계산.
  참조 백엔드 `ProjectQuotaAdapter`와 동일 접근.
- **메모리 단위 = MB (확정, 라이브 검증)**: `openstack_nova_limits_memory_max`가 `51200`(=50GB), `..._used`가 `8192`(=8GB)로 관측됨 → 원단위 **MB**. 표기는 가독성 위해 `≥1024MB`면 GB로 환산(참조 `/1024`와 동일). vCPU는 개수, 디스크는 GB 그대로.
- **⚠ 무제한(-1) 처리 필수**: 라이브에서 일부 테넌트의 `max=-1`(OpenStack의 무제한)이 관측됨.
  `max<=0`이면 `quota=null`, `ratio=null`, severity=null, `display="{used} / 무제한"`로 낸다(0으로 나누기 금지).
- 이름 표기는 `tenant` 라벨을 그대로 쓴다(참조처럼 `openstack_identity_project_info` 조인 **불필요**).

#### (7) `metric_rank` 스파크라인 — range 클라이언트 신규 (이 이슈의 유일한 인프라 추가)
- 지금 `HttpPrometheusClient`는 instant(`/api/v1/query`)만 한다. **`/api/v1/query_range`를 추가**해야 한다.
- 참조 백엔드에 검증된 패턴이 있다: `PrometheusClient.getRange(url, query, start,end,step)` (POST form) — 예 12h 창 / 5m step.
- 작업: `PrometheusClient`에 `queryRange(promql, window, step): List<MetricSeries>` 추가 → 각 행에 `spark: List<Double>` 부착.
- **기본 창 = 5m (확정), step = 30s.** 비용 주의: 행마다 range 조회는 무겁다. topN(기본 5)만, Resilience4j `prometheus` 인스턴스 재사용.
- **이 위젯은 가장 나중.** 없어도 `metric_rank`는 `spark` 생략으로 정상 동작한다.

#### (8) `project_usage_bar` — 쿼터 사용률로 재정의
- "프로젝트별 실사용률" 단일 소스는 없음(참조·라이브 모두). → **프로젝트별 쿼터 사용률(used/max)** 로 낸다.
- (5)의 쿼터 메트릭을 `tenant`별로 묶어 `value=used/max*100`(%). **무제한(-1) 테넌트는 제외하지 말고 "무제한"으로 표기(확정)** — value/severity=null, 바는 비우고 라벨만 "무제한".
- 위젯 JSON 모양(`ProjectUsageBarWidget`)은 설계 그대로. **데이터 의미만 재정의**(위 정정 박스).

---

## 7. 라이브 Prometheus 검증 결과 (증거 — 재조사 불필요)

- 대상: `PROMETHEUS_URL = https://10.255.40.10:11909` (self-signed, `-k`). 방식: `GET /api/v1/query?query=...`.
- `up` → `success` (도달 OK).
- `openstack_nova_limits_vcpus_max` / `..._used` → 존재. 라벨 `{tenant="AUTOTEST", tenant_id="a405...", ...}`, 값 예 `"20"`, **일부 `"-1"`(무제한)**.
- `openstack_nova_limits_memory_max`, `openstack_cinder_limits_volume_max_gb` / `..._used_gb` → 존재(동일 `tenant` 라벨).
- `openstack_nova_server_status` → 존재. 라벨 `{status="ACTIVE", name="...", tenant_id="...", id="...", hypervisor_hostname="..."}`.
- 결론: **1b 위젯의 데이터 소스가 모두 실재**. 남은 실작업은 (a) `query_range` 클라이언트 추가(스파크라인), (b) `-1` 무제한 처리.

---

## 8. 하지 말 것 (불변식 — 어기면 리뷰 반려)

1. **평문 `answer`를 없애지 말 것.** 항상 함께 반환(Slack·접근성 폴백).
2. **유료 LLM 호출을 늘리지 말 것.** 위젯·칩·집계 전부 LLM 0회.
3. **기존 평문 템플릿을 갈아엎지 말 것.** 옆에 `WidgetBuilder`를 **추가**만.
4. **Slack 경로 무영향.** Slack은 `answer`만 쓴다(widgets 무시).
   > **현황(2026-07-09)**: Slack 봇은 현재 미가동. 그래도 `answer`(평문)를 항상 반환하는 원칙은
   > 유지한다 — 웹 캡션·스크린리더 폴백·향후 Slack 재가동 대비. 다만 **"Slack 실환경 회귀"는 현재
   > N/A**이고, 대신 `DefaultChatServiceTest`에서 "위젯 추가가 answer 문자열을 바꾸지 않음"만 단위로 보장한다.
5. **임계치·모델·top-k는 `application.yml`.** 하드코딩 금지(불변식 7).
6. **프론트에서 재포맷·재계산 금지.** severity·display는 서버가 계산해 준 값만 신뢰.
7. **위젯 값 삽입은 DOM/textContent만.** `innerHTML` 문자열 조립 금지(XSS).
8. **범위 고정.** 대시보드(`resource_dashboard`)·heatmap·실시간 셀렉터는 Phase 2/제외. 끌어오지 말 것.

---

## 9. 완료 기준 (DoD) + 테스트

**1a DoD**
- `POST /api/chat`에 "CPU 높은 VM" → 응답에 `metric_rank` 위젯 + 평문 answer 동시 존재.
- "볼륨 몇 개야?"(인벤토리 enable 환경) → `inventory_count` 위젯.
- RESOURCE 응답에 `followups` 최대 3개.
- Slack 회귀: 같은 질문이 Slack에선 기존 평문 그대로(위젯 무영향).

**1b DoD**
- `quota_gauge`/`status_donut`이 라이브 Prometheus 실값으로 렌더(무제한 `-1` 케이스 정상 표기).
- `threshold_banner`가 임계 초과 시 노출.
- `metric_rank` 스파크라인은 range 조회 성공 시 `spark` 부착, 실패 시 생략해도 위젯 정상.

**테스트(설계 §10 준수)**
- `WidgetBuilderTest`(순수함수): metric_rank의 severity 경계값(69/70/84/85), 빈 samples(empty), 단위별 display.
- `MetricValueFormatterTest`: 평문·위젯이 **같은 문자열**을 내는지(드리프트 회귀).
- `ChatResponse` 직렬화 계약 테스트: `widgets` 기본 빈 배열, `type` 판별자 round-trip.
- `quota_gauge`: `max=-1` → ratio/severity null·"무제한" 표기 단위 테스트.
- 회귀: Slack 경로가 `answer`만 사용하는지.
- env-gated: `PROMETHEUS_URL` 있을 때 openstack_ 메트릭 파싱 실측(옵션).

---

## 10. 결정 완료 (2026-07-09)

- [x] **메모리 쿼터 단위 = MB** (라이브: max=51200=50GB, used=8192=8GB). 표기는 `≥1024MB`면 GB 환산.
- [x] **스파크라인 range 기본 = 5m, step 30s.**
- [x] **무제한(-1) = "무제한" 표기** (제외 아님). `quota_gauge`·`project_usage_bar` 모두 value/ratio/severity=null.
- [x] **Phase 2(`resource_dashboard`)는 별도 이슈로 분리** → 아래 "관련 이슈" 참고.
- [x] **연관질문 저장 방식 = 미저장(요청마다 규칙 생성)** — DB·txt 어디에도 넣지 않는다. 상세 11.5·11.8.

### 자주 나오는 질문 (신규 담당자용)

- **Q. DOC인지 RESOURCE인지 누가 구분하나?** → **기존 `QuestionRouter`(LLM #1)가 자동 분류**한다. 이 이슈에서 라우팅은 **건드리지 않는다**. 위젯은 라우터가 이미 RESOURCE로 보낸 뒤의 결과를 변환할 뿐. (파이프라인: `DefaultChatService` → `router.route()` → RESOURCE면 `resourceService.handle()`)
- **Q. 연관질문(followups)은 DB에 저장하나 txt로 두나?** → **둘 다 아니다. 저장하지 않는다.** 요청마다 `FollowupBuilder`(순수 규칙)가 그 자리에서 만든다(11.8 근거). 정적 txt 목록도, 질의로그 테이블도 만들지 않는다.

### 관련 이슈

- **Phase 2 — `resource_dashboard`(요약 대시보드 위젯)**: 별도 이슈로 분리. 스펙: [`2026-07-09-widgets-phase2-dashboard-issue.md`](./2026-07-09-widgets-phase2-dashboard-issue.md)

---

## 11. 구현 AI 가이드 — 코드·테스트·템플릿 스켈레톤

> 이 절은 "복붙 시작점"이다. 기존 코드 스타일(순수함수 `object`, `data class`, `JdbcTemplate`, 생성자 주입)을 따른다. 패키지 경로는 `com.okestro.ragbot.*`.

### 11.1 도메인 타입 — `resource/domain/Widget.kt` (신규)

```kotlin
package com.okestro.ragbot.resource.domain

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

enum class Severity { GOOD, WARN, CRIT }

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(MetricRankWidget::class,     name = "metric_rank"),
    JsonSubTypes.Type(InventoryCountWidget::class,  name = "inventory_count"),
    JsonSubTypes.Type(ThresholdBannerWidget::class, name = "threshold_banner"),
    JsonSubTypes.Type(QuotaGaugeWidget::class,      name = "quota_gauge"),
    JsonSubTypes.Type(StatusDonutWidget::class,     name = "status_donut"),
    JsonSubTypes.Type(ProjectUsageBarWidget::class, name = "project_usage_bar"),
)
sealed interface Widget { val type: String }

data class MetricRankWidget(
    val title: String, val unit: String, val window: String, val promql: String,
    val rows: List<MetricRankRow>, val empty: Boolean = false,
) : Widget { override val type = "metric_rank" }

data class MetricRankRow(
    val instanceName: String, val projectName: String?,
    val value: Double, val display: String, val severity: Severity?,
    val spark: List<Double>? = null,   // 1b: range 조회 성공 시만
)

data class InventoryCountWidget(val label: String, val total: Int, val condition: String?)
    : Widget { override val type = "inventory_count" }

data class ThresholdBannerWidget(val level: Severity, val title: String, val detail: String?, val count: Int)
    : Widget { override val type = "threshold_banner" }

data class QuotaGaugeWidget(val items: List<QuotaItem>) : Widget { override val type = "quota_gauge" }
data class QuotaItem(
    val resource: String, val used: Double,
    val quota: Double?,      // null = 무제한(-1)
    val ratio: Double?,      // null = 무제한
    val display: String,     // "820 / 1000"  |  "8 / 무제한"
    val severity: Severity?, // 무제한이면 null
)

data class StatusDonutWidget(val label: String, val total: Int, val segments: List<StatusSegment>)
    : Widget { override val type = "status_donut" }
data class StatusSegment(val status: String, val count: Int, val level: String) // good|warn|crit|muted

data class ProjectUsageBarWidget(val metric: String, val unit: String, val rows: List<ProjectUsageRow>)
    : Widget { override val type = "project_usage_bar" }
data class ProjectUsageRow(val projectName: String, val value: Double?, val display: String, val severity: Severity?)
```

### 11.2 포맷 단일화 — `resource/application/MetricValueFormatter.kt` (신규)

기존 `ResourceAnswerTemplate.formatValue/formatBytes` 로직을 여기로 옮기고 템플릿이 위임한다(동작 동일 — 회귀 테스트로 보장).

```kotlin
object MetricValueFormatter {
    fun format(value: Double, unit: String): String = when (unit) {
        "%"     -> "%.1f%%".format(value)
        "B/s"   -> bytesPerSec(value)
        "req/s" -> "%.1f req/s".format(value)
        "MB"    -> memoryMb(value)                 // 쿼터 메모리(라이브: MB 확정)
        else    -> "%.2f %s".format(value, unit)
    }
    private fun memoryMb(mb: Double): String =     // 51200MB→"50 GB", 8192MB→"8 GB"
        if (mb >= 1024) "%.0f GB".format(mb / 1024) else "%.0f MB".format(mb)
    private fun bytesPerSec(v: Double): String = /* 기존 formatBytes 로직 그대로 이동 */
}
```

### 11.3 위젯 빌더 — `resource/application/WidgetBuilder.kt` (신규 순수 object)

```kotlin
object WidgetBuilder {

    fun metricRank(q: ResourceQuery, e: MetricCatalogEntry, samples: List<MetricSample>,
                   promql: String, sev: SeverityConfig): MetricRankWidget {
        if (samples.isEmpty())
            return MetricRankWidget(titleOf(q), e.unit, q.window, promql, emptyList(), empty = true)
        val rows = samples.map { s ->
            MetricRankRow(s.instanceName, s.projectName, s.value,
                MetricValueFormatter.format(s.value, s.unit),
                severityFor(s.value, s.unit, sev))
        }
        return MetricRankWidget(titleOf(q), e.unit, q.window, promql, rows)
    }

    // % 지표만 severity, 아니면 null
    fun severityFor(value: Double, unit: String, c: SeverityConfig): Severity? =
        if (unit != "%") null
        else when {
            value >= c.critPercent -> Severity.CRIT
            value >= c.warnPercent -> Severity.WARN
            else                   -> Severity.GOOD
        }

    // -1 → quota=null, ratio=null, "N / 무제한", severity=null
    fun quotaGauge(raws: List<QuotaRaw>, sev: SeverityConfig): QuotaGaugeWidget { /* ... */ }
    fun statusDonut(label: String, counts: Map<String, Int>): StatusDonutWidget { /* level 매핑 */ }
    fun inventoryCount(r: InventoryResult): InventoryCountWidget
    fun projectUsageBar(raws: List<QuotaRaw>, sev: SeverityConfig): ProjectUsageBarWidget // used/max*100, 무제한 표기
    fun thresholdBanner(overNames: List<String>, critPercent: Int): ThresholdBannerWidget?
}
```

**severity 경계 규칙(테스트가 검증)**: `value < warn`→GOOD, `warn ≤ value < crit`→WARN, `value ≥ crit`→CRIT.
기본 warn=70/crit=85 → **69→GOOD, 70→WARN, 84→WARN, 85→CRIT.**

### 11.4 range 조회 — `PrometheusClient.queryRange` (신규 메서드, 스파크라인용)

```kotlin
// PrometheusClient.kt (포트) — instant 옆에 추가
fun queryRange(promql: String, window: String = "5m", step: String = "30s"): List<MetricSeries>
// domain: data class MetricSeries(val instanceName: String, val points: List<Double>)

// HttpPrometheusClient — 참조 검증 패턴: POST form /api/v1/query_range (start,end,step epoch/sec)
//   기본 window=5m, step=30s(확정). topN(기본 5)행만. @Retry/@CircuitBreaker(name="prometheus") 재사용.
//   실패/빈결과 → emptyList. 스파크라인은 옵션이므로 없으면 metric_rank가 spark 생략으로 정상 동작.
```

### 11.5 연관질문 칩 — `resource/application/FollowupBuilder.kt` (신규 순수 규칙, LLM 0회)

```kotlin
object FollowupBuilder {
    // 요청마다 그 자리에서 생성. 저장/영속화 없음(11.8).
    fun forResource(q: ResourceQuery, samples: List<MetricSample>): List<String> {
        val chips = mutableListOf<String>()
        samples.firstOrNull()?.let { top ->
            chips += "${top.instanceName} 메모리는?"                       // 지표 전환
            if (q.project == null) top.projectName?.let { chips += "$it 만 보기" }  // 프로젝트 필터
        }
        chips += "네트워크 송신량 TopN"                                     // 다른 지표
        return chips.distinct().take(3)
    }
    fun forInventory(kind: InventoryKind): List<String> = /* "스냅샷은 몇 개야?" 등 */ emptyList()
}
```

### 11.6 설정 — `application.yml` + `AppProperties`

```yaml
app:
  resource:
    severity:            # 신규 (하드코딩 금지, 불변식 7)
      warn-percent: 70
      crit-percent: 85
```
```kotlin
// AppProperties.Resource 안에 추가
val severity: Severity = Severity()
data class Severity(val warnPercent: Int = 70, val critPercent: Int = 85)
```
`WidgetBuilder`는 이 config(`SeverityConfig`)를 인자로 받는다. `%` 지표에만 적용.

### 11.7 어떻게 테스트하나 (실행 + 케이스)

실행: `./gradlew test` — **키 없이 항상 그린**이어야 함. 실 Prometheus는 env-gated(옵션).

| 테스트 파일(신규) | 검증 |
|---|---|
| `WidgetBuilderTest` | severity 경계(69/70/84/85), 빈 samples→`empty=true`, 단위별 display, 무제한(-1)→quota/ratio/severity=null |
| `MetricValueFormatterTest` | 평문·위젯 **동일 문자열**(드리프트 회귀). "91.2%", "50 GB"(51200MB), "8 GB"(8192MB) |
| `ChatResponseSerializationTest` | `widgets`/`followups` 기본 빈배열, `type` 판별자 **round-trip**(직렬화→역직렬화 동일 객체) |
| `FollowupBuilderTest` | 최대 3개, 프로젝트 이미 적용 시 "만 보기" 미생성, 중복 제거 |
| `DefaultChatServiceTest`(기존 확장) | 위젯 추가가 **answer 문자열을 안 바꿈**(Slack 무영향 대체 보장), DOC 경로 회귀 |

경계값·무제한 테스트 예:
```kotlin
@Test fun `severity 경계 - 85는 CRIT, 84는 WARN`() {
    val c = SeverityConfig(warnPercent = 70, critPercent = 85)
    assertEquals(Severity.CRIT, WidgetBuilder.severityFor(85.0, "%", c))
    assertEquals(Severity.WARN, WidgetBuilder.severityFor(84.0, "%", c))
    assertNull(WidgetBuilder.severityFor(85.0, "B/s", c))   // %만 severity
}
@Test fun `무제한 쿼터는 quota-ratio null과 무제한 표기`() {
    val w = WidgetBuilder.quotaGauge(listOf(QuotaRaw("vCPU", used = 8.0, max = -1.0)), SeverityConfig())
    val it = w.items[0]
    assertNull(it.quota); assertNull(it.ratio); assertNull(it.severity)
    assertTrue(it.display.contains("무제한"))
}
```

### 11.8 연관질문 저장 방식 — **설계 결정: 미저장(요청마다 규칙 생성)**

> 질문: "연관질문을 DB에 넣을지, 그냥 txt로 갖고 있을지?" → **둘 다 아니다.**

| 후보 | 채택? | 이유 |
|---|---|---|
| **A. 요청마다 규칙 생성(FollowupBuilder)** | ✅ **채택** | 칩은 top 결과(`rows[0]`)에 의존 → **매 응답 내용이 달라짐**. 미리 저장할 대상이 아님. 상태 없음 = 버그·정합성 문제 없음. LLM 0회. |
| B. DB 테이블에 저장 | ❌ | 저장할 안정적 실체가 없음(질문마다 재계산). 불변식 6("질의로그/캐시 테이블은 고도화")과 충돌 — 1차 범위 아님. 스키마·마이그레이션·소유권 비용만 추가. |
| C. 정적 txt/리소스 파일 | ❌ | 칩은 동적(인스턴스·프로젝트 이름 삽입)이라 정적 목록으로 표현 불가. 하드코딩은 불변식 7 위반 소지. |

**결정 근거 정리**
- 연관질문은 **파생 데이터(derived)** 다. 원본은 `ResourceQuery` + top 결과이고, 둘 다 요청 처리 중 이미 메모리에 있다.
- 저장하면 원본과 어긋날 위험(스테일)만 생기고 이득 없음 → **순수함수로 그 자리에서 계산**이 가장 단순(불변식: 단순성 우선).
- 응답 DTO(`ChatResponse.followups: List<String>`)로만 나가고 끝. 서버·DB에 잔존물 없음.
- (향후 고도화) 클릭 통계가 필요해지면 그때 **클릭 이벤트**만 로깅하는 별도 과제로. 지금은 범위 밖.

### 11.9 위젯 최종 모양 (직렬화 결과 — FE가 실제로 받는 것)

```jsonc
// "CPU 제일 높은 인스턴스" 응답 전체
{
  "answer": "CPU 사용률이 높은 인스턴스 (프로젝트: service-prod):\n  1. web-prod-07 ...",
  "sources": [],
  "widgets": [
    { "type": "metric_rank", "title": "CPU 사용률이 높은 인스턴스", "unit": "%", "window": "5m",
      "promql": "topk(5, ...)", "empty": false,
      "rows": [
        { "instanceName": "web-prod-07", "projectName": "service-prod", "value": 91.2, "display": "91.2%", "severity": "CRIT" },
        { "instanceName": "cache-03",    "projectName": "service-prod", "value": 61.9, "display": "61.9%", "severity": "GOOD" }
      ]}
  ],
  "followups": ["web-prod-07 메모리는?", "service-prod 만 보기", "네트워크 송신량 TopN"]
}
```

### 11.10 파일 변경 요약 (체크리스트)

| 파일 | 신규/수정 | 내용 |
|---|---|---|
| `resource/domain/Widget.kt` | 신규 | 위젯 sealed 타입 + Jackson |
| `resource/application/MetricValueFormatter.kt` | 신규 | 포맷 단일화 |
| `resource/application/WidgetBuilder.kt` | 신규 | 순수 변환 |
| `resource/application/FollowupBuilder.kt` | 신규 | 규칙 칩 |
| `resource/application/ResourceAnswerTemplate.kt` | 수정 | 포맷터 위임 |
| `resource/application/PrometheusClient.kt` (+Http) | 수정 | `queryRange` 추가(1b) |
| `resource/application/ResourceService.kt` | 수정 | `Result`에 widgets/followups |
| `resource/application/DefaultResourceService.kt` | 수정 | answer와 함께 위젯·칩 생성 |
| `chat/application/ChatResult.kt` | 수정 | widgets/followups 필드 |
| `chat/application/DefaultChatService.kt` | 수정 | handleResource 전달 |
| `chat/interfaces/ChatResponse.kt` | 수정 | widgets/followups 필드 |
| `chat/interfaces/ChatController.kt` | 수정 | 매핑 |
| `common/config/AppProperties.kt` + `application.yml` | 수정 | `app.resource.severity` |
| `**Test.kt` (5종) | 신규 | 11.7 |
