# 챗봇 집계 시각화(위젯) 설계

- **작성일**: 2026-07-09 (개정 2026-07-09: 자체 비판 반영)
- **대상 저장소**: `contrabass-rag` (ragbot-server)
- **상태**: 설계 확정 → 구현 계획 대기
- **범위 방침**: 1차(Phase 1)로 확정, 대시보드·확장 위젯은 Phase 2로 분리

## 1. 배경 / 문제

현재 RAG 챗봇은 `POST /api/chat` → `ChatResponse { answer: String, sources: List<String> }`
**평문**만 반환한다. RESOURCE 경로(Prometheus TopN)와 INVENTORY 경로(cb_common 조회)의 답변은
`ResourceAnswerTemplate` / `InventoryAnswerTemplate`이 만든 **순수 텍스트**다. 예:

```
CPU 사용률이 높은 인스턴스 (프로젝트: service-prod):
  1. web-prod-07 [service-prod] — 91.2%
  2. api-prod-02 [service-prod] — 87.0%
```

집계 성격의 응답(순위, 사용률, 개수)은 표·바·카드로 보여줄 때 훨씬 빨리 읽힌다.
정적 `chat-widget`(웹 UI)에서 이 응답들을 **시각 위젯**으로 렌더링하는 것이 목표다.

## 2. 목표 / 비목표

**목표**
- RESOURCE·INVENTORY 응답을 웹 위젯으로 표현
- 실제 `chat-widget` 디자인 토큰(오렌지 `#f36c21` 런처, 블루 `#1f7bf2` 액센트, Inter, 라이트 테마, 플로팅 패널)에 100% 맞춤
- 임계치 색상 인코딩으로 "봐야 할 것"이 한눈에

**비목표**
- Slack 응답 변경 (Slack은 기존 평문 유지 — §9 근거 참고)
- 실시간 스트리밍 / 시계열 그래프 (Phase 2 후보)
- LLM 호출 추가 (위젯은 기존 조회 결과의 재표현, LLM 0회)
- 문서(DOC) 경로 변경 (기존 평문 + 출처칩 유지)

## 3. 범위 분리 (자체 비판 반영)

초안은 `resource_dashboard`를 "위젯 하나"처럼 다뤘으나, 실제로는 **새 요약 라우트 +
크로스소스 집계**라는 별도 덩어리다(§5.3). 정직하게 단계를 나눈다.

| Phase | 포함 | 이유 |
|---|---|---|
| **Phase 1 (이번)** | `metric_rank`, `inventory_count`, empty/error 상태 | 기존 조회 결과를 재표현만 하면 됨. 라우팅/집계 변경 0 |
| **Phase 2** | `resource_dashboard` (+ 요약 라우트·크로스소스 집계) | 새 의도 분류 + 고정 PromQL 세트 + cb_common·Prometheus 결합 필요 |
| **Backlog** | §8 확장 위젯 후보 | 반영 여부 미결정 |

## 4. 응답 계약 확장 (하위호환)

`ChatResponse`에 `widgets` 필드를 추가한다. 기본값 빈 리스트 → 기존 클라이언트·Slack 무영향.

```kotlin
data class ChatResponse(
    val answer: String,            // 기존 평문 — Slack 폴백 & 접근성 캡션으로 계속 사용
    val sources: List<String>,
    val widgets: List<Widget> = emptyList(),
)
```

`answer`(평문)는 **항상** 함께 반환한다. 웹 위젯은 시각적 보강일 뿐, 평문이 진실의 원본이자
Slack·스크린리더 폴백이다.

### 4.1 폴리모픽 직렬화 (초안 누락 → 명시)

`Widget`은 sealed interface, Jackson 폴리모픽 직렬화로 `type` 판별자를 낸다.

```kotlin
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = MetricRankWidget::class,     name = "metric_rank"),
    JsonSubTypes.Type(value = InventoryCountWidget::class,  name = "inventory_count"),
    // Phase 2: ResourceDashboardWidget "resource_dashboard"
)
sealed interface Widget { val type: String }
```

- 각 구현체가 `type` 값을 상수로 보유(`EXISTING_PROPERTY`) → 직렬화/역직렬화 대칭.
- **주의**: Spring AI가 쓰는 ObjectMapper가 아니라 **웹 응답용 Jackson**이 직렬화한다.
  전역 ObjectMapper 커스터마이징과 충돌하지 않도록 어노테이션 기반(전역 default typing 미사용).
- `schemaVersion` 필드는 두지 않는다(YAGNI). 필요 시 새 `type`으로 추가.

### 4.2 위젯 스키마 (Phase 1)

```jsonc
// metric_rank — Prometheus TopN (ResourceQuery + List<MetricSample>)
{
  "type": "metric_rank",
  "title": "CPU 사용률이 높은 인스턴스",
  "window": "5m",
  "unit": "%",                    // %, B/s, req/s (원단위; display는 변환·포맷 완료 문자열)
  "promql": "topk(5, ...)",       // 근거 표기(환각 방지 원칙과 동일 취지)
  "rows": [
    { "instanceName": "web-prod-07", "projectName": "service-prod",
      "value": 91.2, "display": "91.2%", "severity": "crit" }  // severity: good|warn|crit
  ]
}

// inventory_count — cb_common COUNT 모드 (InventoryResult)
{ "type": "inventory_count", "label": "볼륨", "total": 342, "condition": "상태=available · 프로젝트 전체" }

// 결과 0건 / 조회 실패 (§6)
{ "type": "metric_rank", "title": "...", "unit": "%", "rows": [], "empty": true }
```

- `severity`/색상 레벨은 **백엔드가 계산**해서 넣는다(임계치 규칙을 서버가 소유). 프론트는 표현만.
- `display`는 백엔드에서 포맷 완료된 문자열(§5.2 공유 포맷터). 프론트는 재포맷하지 않는다.

## 5. 백엔드 설계 (surgical)

기존 텍스트 템플릿은 **건드리지 않는다**(Slack이 계속 쓴다). 위젯 생성기를 옆에 추가한다.

```
resource/application/
  ResourceAnswerTemplate.kt      (유지) — 평문, Slack용
  InventoryAnswerTemplate.kt     (유지) — 평문, Slack용
  MetricValueFormatter.kt        (신규) — %, B/s→MB/s 등 단일 포맷터 (§5.2)
  WidgetBuilder.kt               (신규) — 순수함수, MetricSample/InventoryResult → Widget
resource/domain/
  Widget.kt                      (신규) — sealed interface + 구현체 + Severity
chat/interfaces/
  ChatResponse.kt                (수정) — widgets 필드 추가
```

### 5.1 임계치 소유 (초안: 매직넘버 85/70 → config)

색상 임계치를 하드코딩하지 않고 설정으로 소유한다.

```yaml
ragbot:
  resource:
    severity:
      warn-percent: 70
      crit-percent: 85
```

- `%` 지표에만 severity 적용. `B/s`·`req/s`는 절대 순위만(severity 없음, 색상 = 액센트).
- 규칙은 `WidgetBuilder`가 config를 주입받아 적용. 프론트는 서버가 준 `severity`만 신뢰.

### 5.2 포맷 단일화 (초안: answer/widget 숫자 드리프트 위험 → 공유)

Slack 평문(`ResourceAnswerTemplate`)과 웹 위젯(`WidgetBuilder`)이 **같은 데이터를 각자
포맷**하면 반올림이 갈릴 수 있다("91.2%" vs "91%"). `MetricValueFormatter`로 단일화하고
양쪽이 이를 호출한다. 기존 `ResourceAnswerTemplate.formatValue/formatBytes`는 이 포맷터로 위임.

### 5.3 Phase 2: resource_dashboard (설계 방향만 명시)

이번 범위 아님. 구현 시 필요한 것을 남겨둔다:
- 요약 의도 분류(라우터 or 조건추출에 `SUMMARY` 추가)
- 고정 PromQL 세트(node-level `avg by(resource)` CPU/메모리/디스크/네트워크) — LLM 0회
- KPI 크로스소스 집계: 인스턴스 수(cb_common) + 임계 초과 노드 수(Prometheus count)
- 하나의 `resource_dashboard` 위젯으로 조립

## 6. 상태 처리 (초안 누락)

- **결과 0건**: 위젯을 `rows: []` + `empty: true`로 반환하고, `answer`는 기존 "해당 인스턴스를
  찾을 수 없습니다" 유지. 프론트는 빈 상태 카드 렌더.
- **조회 실패**(Prometheus 타임아웃 등, Resilience4j fallback): **위젯 없이** 평문 에러만.
  프론트는 위젯 없으면 기존 텍스트 버블로 렌더(자연 폴백).
- **row cap**: `metric_rank`는 `ResourceQuery.topN`(기본 5)로 이미 상한. 표 형태(Phase 2/backlog)
  도입 시 상한·"N건 중 M" 표기 필수.

## 7. 프론트엔드 설계

실제 `src/main/resources/static/chat-widget/`를 확장한다.

- `chat-widget.js`: `renderMessages`가 `message.widgets`를 순회하며 `type`별 렌더러 호출
  (`renderMetricRank` / `renderInventoryCount`). 기존 `answer` 텍스트는 위젯 위 캡션으로 유지.
- **XSS 방지(초안 누락)**: 위젯 값(`instanceName`·`projectName` 등은 DB/Prometheus label 출처)은
  반드시 `textContent`/DOM API로 삽입. `innerHTML` 문자열 조립 금지(데모는 프로토타입이라 예외).
- `chat-widget.css`: 위젯 스타일 추가(기존 변수 재사용 + `--good/--warn/--crit` 신규).
- **접근성**: 바/게이지에 `role="img"` + `aria-label`(예: "CPU 91%, 임계치 초과") 부여.
- 후속질문 칩(드릴다운)은 Phase 2.

브랜드/레이아웃 참조: 정적 프로토타입 `docs/prototype/chatbot-widgets/index.html`(대화 데모),
`gallery.html`(위젯 후보 갤러리). 보는 법·주의는 해당 폴더 `README.md` 참고.

## 8. 확장 위젯 후보 (미결정 — 다음 결정 대기)

반영 여부는 후속 결정. 채택 시 각 항목을 Phase 2 스코프로 편입한다.

| 후보 | 근거 | 데이터 출처 |
|---|---|---|
| 임계 초과 배너(대시보드 상단 콜아웃) | 운영자 첫 시선 | Prometheus count |
| 프로젝트별 사용량 바(group by project) | "어느 프로젝트가 자원 먹나" 빈발 | Prometheus by(project) |
| 상태 분포 도넛(ACTIVE/SHUTOFF/ERROR) | 인벤토리 건전성 한눈에 | cb_common group by status |
| 쿼터/용량 게이지(used vs quota) | "자원 현황"의 핵심 | cb_common quota |
| rank 행 내 스파크라인 | 순위+추세 결합 | Prometheus range query |
| inventory_table(LIST 모드) | 목록 질의 표현 | cb_common LIST |

**의도적 제외**: heatmap(노드×지표, 과함), 실시간 time-range 셀렉터(스냅샷 비목표와 충돌).

## 9. 데이터 흐름

```
질문 → 라우터(DOC/RESOURCE/CLARIFY)
  └ RESOURCE → 조건추출(LLM) → PromQL/Inventory 조회
       ├ ResourceAnswerTemplate → answer(평문)      → Slack & 캡션   (MetricValueFormatter 공유)
       └ WidgetBuilder          → widget(구조화)     → 웹 위젯        (MetricValueFormatter 공유)
  → ChatResponse { answer, sources, widgets }
       ├ Slack: answer만 사용 (widgets 무시)
       └ 웹: answer 캡션 + widgets 렌더 (없으면 텍스트 폴백)
```

## 10. 테스트 전략

- `WidgetBuilderTest`(단위, 순수함수) — 기존 `ResourceAnswerTemplateTest` 패턴
  - metric_rank: %/B/s 단위, **severity 경계값(69/70/84/85)**, 빈 samples(empty)
  - inventory_count: COUNT 결과, 조건 문자열
- `MetricValueFormatterTest` — answer/widget이 **동일 문자열**을 내는지(드리프트 회귀 방지)
- `ChatResponse` 직렬화 계약 테스트 — `widgets` 빈 배열 기본값, `type` 판별자 값 검증(round-trip)
- **프론트 렌더러 테스트(초안 누락)** — 렌더러가 `textContent`로 이스케이프하는지(XSS 회귀)
- 회귀: Slack 경로가 `answer`만 쓰는지(widgets 추가가 Slack 무영향)

## 11. 위험 / 결정사항

- **Phase 2 대시보드 범위**: 새 라우트+집계 필요 → 별도 스펙/계획으로 분리 권장.
- **하위호환**: `widgets` 기본 빈 배열 + 기존 위젯 JS는 없던 필드 무시 → 배포 순서 무관.
- **캐싱**: 답변 캐시 도입 시 위젯도 `ChatResponse` 일부로 함께 캐시(별도 처리 불필요).
- **Slack 위젯화(비목표 근거)**: Slack Block Kit로 표/바 일부 가능하나, 유지비 대비 효용 낮아
  이번엔 평문 폴백 유지. 필요 시 별도 과제.
- **접근성**: 위젯은 평문 `answer`를 항상 동반 + 위젯 자체 `aria-label`로 이중 보장.

## 12. 역할 분담 (RnR)

4명을 **백엔드 2 / 프론트 2**로 나눈다. 별도 QA 없이 각자 자기 영역의 테스트를 소유한다.

### 쉽게 (음식점 비유)
- 백엔드 2명 = **주방**(데이터를 만드는 쪽), 프론트 2명 = **홀**(손님에게 보여주는 쪽)
- 각 팀은 "규격을 정하고 만드는 사람"과 "그걸 받아 흐름/배치를 담당하는 사람"으로 나뉜다.
- **"계약"** = 주방과 홀이 미리 "이 메뉴는 이런 상차림으로 나간다"를 약속해두는 것.
  약속만 되면 서로 안 기다리고 동시에 일한다. (지금 프로토타입 데모가 그 "상차림 견본")

### 백엔드 2명

| 역할 | 하는 일 | 산출물 |
|---|---|---|
| **BE-A · 계약 & 도메인** (크리티컬 패스) | 위젯이 "무엇인지"와 "어떻게 만들어/직렬화되는지" 소유 | `Widget` sealed + `@JsonTypeInfo` 직렬화, `WidgetBuilder`, `MetricValueFormatter`(+기존 템플릿 위임 리팩터), severity config · **테스트**: WidgetBuilder·Formatter 단위, 직렬화 round-trip |
| **BE-B · 파이프라인 & 집계** (+릴리스) | 위젯 입력 생산·출력 전달, P2 집계 | `ResourceService`/`DefaultChatService` 연결, empty/error 처리, Slack 무영향 회귀, 통합·`deploy.sh` · **P2**: 요약 라우트 + 크로스소스(Prometheus+cb_common) 집계·고정 PromQL |

### 프론트 2명

| 역할 | 하는 일 | 산출물 |
|---|---|---|
| **FE-A · 렌더러 & 데이터 바인딩** (로직) | 위젯 JSON을 "동작"으로 (데이터를 제자리에) | `chat-widget.js` 타입 디스패치, `renderMetricRank`/`renderInventoryCount`, empty/error + 텍스트 폴백, XSS-safe DOM(`textContent`), 접근성(aria) · **테스트**: 렌더러·이스케이프 |
| **FE-B · 디자인 & 비주얼** (룩앤필) | "어떻게 보이나"만 담당 (예쁘게 꾸밈) | `chat-widget.css` 위젯 스타일, "브랜드 색 + 아티팩트 느낌"(여백·라운드·부드러운 그림자·그라데이션 차트/게이지 SVG), 갤러리 유지, 반응형 |

### 두 개의 계약(먼저 얼릴 것) — 1일차 확정
1. **위젯 JSON 스키마** (BE-A ↔ FE-A) = §4.2를 마스터 계약으로 확정 → 프론트는 목업 JSON으로 선개발
2. **위젯 DOM/클래스 구조** (FE-A ↔ FE-B) = 렌더러가 뽑는 HTML 구조·클래스명 합의 → FE-B는 CSS 선개발

### 착수 순서 / 병렬성
- **Day 1**: BE-A 스키마 확정 · FE 2명 DOM 계약 확정
- **P1 병렬**: BE-A(빌더/직렬화) ‖ BE-B(파이프라인/폴백) ‖ FE-A(렌더러) ‖ FE-B(비주얼)
- **P2**(대시보드): BE-B 주도(집계) + FE-A/FE-B 대시보드 위젯 추가
- **릴리스 코디네이터**: BE-B (머지 전 계약 테스트 + Slack 무영향 회귀 게이트)

## 13. 데이터 타입 정의 (계약 원본, Phase 1)

BE-A↔FE-A의 **마스터 계약**. 이 타입/JSON이 곧 API 규격이다.

### 13.1 Kotlin 타입 (`resource/domain/Widget.kt`)

```kotlin
enum class Severity { GOOD, WARN, CRIT }

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = MetricRankWidget::class,    name = "metric_rank"),
    JsonSubTypes.Type(value = InventoryCountWidget::class, name = "inventory_count"),
    // Phase 2: JsonSubTypes.Type(ResourceDashboardWidget::class, name = "resource_dashboard")
)
sealed interface Widget { val type: String }

/** Prometheus TopN 랭킹. ResourceQuery + List<MetricSample> → 변환. */
data class MetricRankWidget(
    val title: String,          // "CPU 사용률이 높은 인스턴스"
    val unit: String,           // "%", "B/s", "req/s" (원단위)
    val window: String,         // "5m"
    val promql: String,         // 근거 표기(환각 방지)
    val rows: List<MetricRankRow>,
    val empty: Boolean = false, // 결과 0건
) : Widget { override val type = "metric_rank" }

data class MetricRankRow(
    val instanceName: String,
    val projectName: String?,   // 없을 수 있음
    val value: Double,          // 정렬·바 길이 계산용 원단위 값
    val display: String,        // 포맷 완료 문자열 "91.2%", "410 MB/s" (MetricValueFormatter)
    val severity: Severity?,    // % 지표만 채움; B/s·req/s는 null(색상=액센트)
)

/** cb_common COUNT 결과. InventoryResult → 변환. */
data class InventoryCountWidget(
    val label: String,          // "볼륨", "인스턴스", "스냅샷"
    val total: Int,
    val condition: String?,     // "상태=available · 프로젝트 전체" (없으면 null)
) : Widget { override val type = "inventory_count" }
```

### 13.2 응답 계약 (`chat/interfaces/ChatResponse.kt`)

```kotlin
data class ChatResponse(
    val answer: String,
    val sources: List<String>,
    val widgets: List<Widget> = emptyList(),
)
```

### 13.3 샘플 `POST /api/chat` 응답 (직렬화 결과)

```jsonc
// "CPU 제일 높은 인스턴스 5개"
{
  "answer": "CPU 사용률이 높은 인스턴스 (프로젝트: service-prod):\n  1. web-prod-07 [service-prod] — 91.2% ...",
  "sources": [],
  "widgets": [
    {
      "type": "metric_rank",
      "title": "CPU 사용률이 높은 인스턴스",
      "unit": "%",
      "window": "5m",
      "promql": "topk(5, ...cpu...)",
      "empty": false,
      "rows": [
        { "instanceName": "web-prod-07", "projectName": "service-prod", "value": 91.2, "display": "91.2%", "severity": "CRIT" },
        { "instanceName": "api-prod-02", "projectName": "service-prod", "value": 87.0, "display": "87.0%", "severity": "CRIT" },
        { "instanceName": "batch-11",    "projectName": "data-platform", "value": 73.4, "display": "73.4%", "severity": "WARN" },
        { "instanceName": "cache-03",    "projectName": "service-prod", "value": 61.9, "display": "61.9%", "severity": "GOOD" }
      ]
    }
  ]
}

// "볼륨 몇 개야?"
{
  "answer": "볼륨 342건입니다 (조건: status=available):\n...",
  "sources": [],
  "widgets": [
    { "type": "inventory_count", "label": "볼륨", "total": 342, "condition": "상태=available · 프로젝트 전체" }
  ]
}

// 결과 0건 (empty)
{
  "answer": "현재 조건에 해당하는 인스턴스를 찾을 수 없습니다.",
  "sources": [],
  "widgets": [
    { "type": "metric_rank", "title": "CPU 사용률이 높은 인스턴스", "unit": "%", "window": "5m", "promql": "topk(5, ...)", "empty": true, "rows": [] }
  ]
}

// 조회 실패 / DOC / CLARIFY → widgets 없음(평문 폴백)
{ "answer": "지표를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.", "sources": [], "widgets": [] }
```

### 13.4 Phase 2 예고 (`resource_dashboard`, 확정 아님)

```jsonc
{
  "type": "resource_dashboard",
  "window": "5m",
  "kpis": [
    { "label": "가동 인스턴스", "value": "128", "sub": "/ 140", "chip": "91% 정상", "chipLevel": "GOOD" },
    { "label": "임계 초과 노드", "value": "2", "sub": "대", "chip": "CPU 85%↑", "chipLevel": "CRIT" }
  ],
  "metrics": [
    { "label": "CPU", "value": 72, "unit": "%", "severity": "WARN" },
    { "label": "메모리", "value": 64, "unit": "%", "severity": "GOOD" }
  ]
}
```
