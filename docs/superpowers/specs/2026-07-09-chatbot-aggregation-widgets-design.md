# 챗봇 집계 시각화(위젯) 설계

- **작성일**: 2026-07-09
- **대상 저장소**: `contrabass-rag` (ragbot-server)
- **상태**: 설계 확정 → 구현 계획 대기

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
- RESOURCE·INVENTORY 응답을 웹 위젯(랭킹 바 / 미니 대시보드 / 카운트 카드)으로 표현
- 실제 `chat-widget` 디자인 토큰(오렌지 `#f36c21` 런처, 블루 `#1f7bf2` 액센트, Inter, 라이트 테마, 플로팅 패널)에 100% 맞춤
- 임계치 색상 인코딩(85%↑ crit / 70%↑ warn / 그 외 good)으로 "봐야 할 것"이 한눈에

**비목표**
- Slack 응답 변경 (Slack은 기존 평문 유지)
- 실시간 스트리밍 차트 / 시계열 그래프 (이번 범위는 스냅샷 집계)
- LLM 호출 추가 (위젯은 기존 조회 결과의 재표현, LLM 0회)
- 문서(DOC) 경로 변경 (기존 평문 + 출처칩 유지)

## 3. 응답 계약 확장 (하위호환)

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

### 3.1 위젯 스키마

`type` 판별자를 가진 3종. JSON 직렬화 시 `type` 필드로 구분한다.

```jsonc
// metric_rank — Prometheus TopN (ResourceQuery + List<MetricSample>)
{
  "type": "metric_rank",
  "title": "CPU 사용률이 높은 인스턴스",
  "window": "5m",
  "unit": "%",                    // %, B/s(→MB/s 변환), req/s
  "promql": "topk(5, ...)",       // 근거 표기(환각 방지 원칙과 동일 취지)
  "rows": [
    { "instanceName": "web-prod-07", "projectName": "service-prod",
      "value": 91.2, "display": "91.2%", "severity": "crit" }
  ]
}

// resource_dashboard — 요약 집계
{
  "type": "resource_dashboard",
  "window": "5m",
  "kpis": [
    { "label": "가동 인스턴스", "value": "128", "sub": "/ 140", "chip": "91% 정상", "chipLevel": "good" },
    { "label": "임계 초과 노드", "value": "2", "sub": "대", "chip": "CPU 85%↑", "chipLevel": "crit" }
  ],
  "metrics": [
    { "label": "CPU", "value": 72, "unit": "%", "severity": "warn" }
  ]
}

// inventory_count — cb_common COUNT 모드 (InventoryResult)
{
  "type": "inventory_count",
  "label": "볼륨",
  "total": 342,
  "condition": "상태=available · 프로젝트 전체"
}
```

`severity` / `chipLevel`은 백엔드에서 계산해 넣는다(임계치 규칙을 서버가 소유 → 프론트는 표현만).

## 4. 백엔드 설계 (surgical)

기존 텍스트 템플릿은 **건드리지 않는다**(Slack이 계속 쓴다). 위젯 생성기를 옆에 추가한다.

```
resource/application/
  ResourceAnswerTemplate.kt      (유지) — 평문, Slack용
  InventoryAnswerTemplate.kt     (유지) — 평문, Slack용
  WidgetBuilder.kt               (신규) — 순수함수, MetricSample/InventoryResult → Widget
resource/domain/
  Widget.kt                      (신규) — sealed interface + 3개 data class
chat/interfaces/
  ChatResponse.kt                (수정) — widgets 필드 추가
```

- `WidgetBuilder`는 `ResourceAnswerTemplate`과 **동일 입력**(`ResourceQuery`, `List<MetricSample>`
  / `InventoryResult`)을 받아 위젯을 만든다. LLM 0회, 순수함수.
- 임계치 색상 규칙(`sev(p)`)을 `WidgetBuilder`가 소유.
- `ResourceService.Result`(또는 반환 타입)에 `widget: Widget?` 추가 → `DefaultChatService`가
  `ChatResponse.widgets`로 전달. RESOURCE 결과 하나당 위젯 하나(요약 질의는 dashboard 하나).
- 단위 변환(B/s→MB/s 등)은 위젯 `display` 문자열 생성 시 기존 `formatBytes` 로직 재사용.

### 4.1 요약(dashboard) 질의 경로

"전체 자원 현황 요약" 류는 현재 단일 TopN 조회와 다르다. 이번 범위에서는:
- 조건추출(LLM ②)이 `metric=SUMMARY` 같은 요약 의도를 분류하면
- 여러 지표를 avg 집계하는 고정 PromQL 세트를 조립(LLM 0회) → `resource_dashboard` 위젯 생성
- (요약 라우팅이 현재 미지원이면, 구현 계획에서 최소 확장 범위를 별도 태스크로 분리)

## 5. 프론트엔드 설계

실제 `src/main/resources/static/chat-widget/`를 확장한다.

- `chat-widget.js`: `renderMessages`가 `message.widgets`를 순회하며 `type`별 렌더러 호출
  (`renderMetricRank` / `renderResourceDashboard` / `renderInventoryCount`).
  기존 `answer` 텍스트 버블은 위젯 위에 캡션으로 유지.
- `chat-widget.css`: 위젯 스타일 추가 (기존 CSS 변수 `--accent`/`--brand`/`--line` 재사용,
  `--good/--warn/--crit` 신규 추가).
- 후속질문 칩(드릴다운)은 2단계 확장으로 분리 — 이번 범위는 위젯 렌더까지.

브랜드/레이아웃 기준은 로컬 데모(`scratchpad/ragbot-demo/index.html`)가 시각적 참조.

## 6. 데이터 흐름

```
질문 → 라우터(DOC/RESOURCE/CLARIFY)
  └ RESOURCE → 조건추출(LLM) → PromQL/Inventory 조회
       ├ ResourceAnswerTemplate → answer(평문)      → Slack & 캡션
       └ WidgetBuilder          → widget(구조화)     → 웹 위젯
  → ChatResponse { answer, sources, widgets }
       ├ Slack: answer만 사용 (widgets 무시)
       └ 웹: answer 캡션 + widgets 렌더
```

## 7. 테스트 전략

- `WidgetBuilderTest` (단위, 순수함수) — 기존 `ResourceAnswerTemplateTest` 패턴 그대로
  - metric_rank: %/B/s 단위, severity 경계값(69/70/84/85), 빈 samples
  - inventory_count: COUNT 결과, 조건 문자열
  - resource_dashboard: KPI/metrics 매핑
- `ChatResponse` 직렬화 계약 테스트 — `widgets` 빈 배열 기본값, type 판별자 필드 존재
- 회귀: Slack 경로가 `answer`만 쓰는지 (widgets 추가가 Slack 무영향) 확인

## 8. 위험 / 결정사항

- **요약 대시보드 라우팅**: 현재 RESOURCE가 단일 TopN 중심. 요약 의도 분류·고정 PromQL 세트가
  없으면 이번 범위에서 `resource_dashboard`는 후속으로 미룰 수 있음 → 구현 계획에서 확정.
- **하위호환**: `widgets` 기본 빈 배열 + 기존 위젯 JS는 없던 필드 무시 → 배포 순서 무관.
- **접근성**: 위젯은 평문 `answer`를 항상 동반하므로 스크린리더·Slack 폴백 보장.
```
