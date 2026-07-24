# 쿼터 복원 + 시계열 질문/라인그래프 + 질문 히스토리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 쿼터 기능 복원(완료) 위에 ① 시계열(추이) 질문을 RESOURCE 경로에 추가하고 ② `metric_line` 라인그래프 위젯으로 렌더링하며 ③ 웹 위젯이 대화 히스토리를 서버로 보내 후속질문이 맥락을 갖게 한다.

**Architecture:** 기존 추출기(LLM strict json_schema)에 `TREND` target을 추가하고, PrometheusClient에 `queryRange`를 더해 `/api/v1/query_range`로 시계열을 받는다. 위젯은 기존 계약(sealed Widget + d.ts + PlainNode 렌더러) 패턴 그대로 `metric_line`을 추가. 히스토리는 Slack 경로가 이미 쓰는 `ChatCommand.history` seam에 REST가 합류하는 것뿐이다.

**Tech Stack:** Kotlin/Spring Boot 3.5, JdbcTemplate, hand-rolled fakes(no mockk), 프론트는 no-lib PlainNode SVG + node:test.

## Global Constraints

- 임베딩/LLM 호출 불변식(CLAUDE.md 1~4) 유지 — 시계열 경로도 검색 성공/추출 성공 시에만 LLM 소비.
- 튜닝값(범위·포인트 수·최대 시리즈)은 `application.yml` (하드코딩 금지).
- 프론트: innerHTML 금지, 공격 문자열은 text로만 (xss.test.mjs 계약).
- 테스트: kotlin.test + 한국어 백틱 테스트명, Prometheus/LLM은 hand-rolled stub.
- 기존 스타일 준수: Widget은 `@JsonSubTypes` 등록, 렌더러는 `render/widgets/*.js` + dispatch BUILDERS 등록 + `widget-contract.d.ts` 동기화.

---

### Task 1: 쿼터 복원 + 실측 [완료]

`git revert 37d8d09` (커밋 95e3e0c). `./gradlew test` 전체 통과, node --test 77/77.
실측: Prometheus(10.255.40.10:11909) 1165개 메트릭 중 `openstack_{nova,cinder}_limits_*` 0개 →
API 응답 `{"answer":"...쿼터 정보를 찾지 못했습니다.","widgets":[{"type":"quota_gauge","empty":true}]}` (계약대로 동작).
**한계: exporter가 limits 메트릭을 수집해야 실데이터가 나온다 — 코드 밖 이슈.**

### Task 2: 시계열 백엔드 — TREND target + queryRange

**Files:**
- Modify: `resource/domain/ResourceExtraction.kt` — `TrendResolved(query: TrendQuery)` 추가
- Create: `resource/domain/TrendQuery.kt` — `(metric: MetricPattern, range: String = "1h", project: String? = null, instanceName: String? = null)`
- Modify: `resource/domain/PromQlBuilder.kt` — topk 래핑 없는 표현식 재사용을 위해 `buildExpression(query, entry)` 노출, `buildTrend(query: TrendQuery, entry)` 추가
- Create: `resource/domain/RangeSeries.kt` — `(labels: Map<String,String>, points: List<TimePoint>)`, `TimePoint(ts: Long, value: Double)`
- Modify: `resource/application/PrometheusClient.kt` — `fun queryRange(promql: String, start: Instant, end: Instant, step: Duration): List<RangeSeries>`
- Modify: `resource/infrastructure/HttpPrometheusClient.kt` — `/api/v1/query_range` GET (query,start,end,step), @Retry/@CircuitBreaker 동일
- Modify: `resource/application/LlmMetricQueryExtractor.kt` — RawExtraction에 `range` 필드, target `"TREND"` 분기
- Modify: `resource/application/ResourcePrompts.kt` — TREND 규칙 + few-shot + schema에 range
- Modify: `resource/application/DefaultResourceService.kt` — TrendResolved 분기: yml 설정(range 기본값·points·maxSeries)으로 start/end/step 계산 → queryRange → 답변 템플릿 + `WidgetBuilder.metricLine`
- Modify: `application.yml` — `app.resource.trend: {default-range: 1h, points: 60, max-series: 5}` + AppProperties 매핑
- Modify: `routing/application/RoutingPrompts.kt` — "추이" RESOURCE few-shot 1개
- Tests: `PromQlBuilderTest`(trend 표현식), `LlmMetricQueryExtractorTest`(TREND 추출), `DefaultResourceServiceTrendTest`(신규 — stub prometheus로 metric_line 생성·시리즈 상한·빈 결과)

**Interfaces (Task 3이 소비):** `WidgetBuilder.metricLine(query: TrendQuery, series: List<RangeSeries>, promql: String, unit: String, maxSeries: Int): MetricLineWidget`

**Steps (TDD):**
- [ ] PromQlBuilderTest에 trend 표현식 실패 테스트 → 구현 → 통과
- [ ] LlmMetricQueryExtractorTest에 TREND raw JSON → TrendResolved 실패 테스트 → 구현 → 통과
- [ ] DefaultResourceServiceTrendTest (FixedExtractor + StubPrometheus.queryRange) → 서비스 분기 구현 → 통과
- [ ] HttpPrometheusClient queryRange 구현 (단위테스트는 파싱 로직 분리 시에만)
- [ ] `./gradlew test` 전체 → commit

### Task 3: metric_line 위젯 (계약 + 프론트)

**선행: dataviz 스킬 로드 후 차트 코드 작성.**

**Files:**
- Modify: `resource/domain/Widget.kt` — `MetricLineWidget(title, unit, range, promql, series: List<MetricLineSeries>, empty=false)`, `MetricLineSeries(name, projectName?, points: List<MetricLinePoint>)`, `MetricLinePoint(ts: Long, value: Double)`, @JsonSubTypes `metric_line` 등록
- Modify: `resource/application/WidgetBuilder.kt` — `metricLine(...)`: 마지막 값 기준 상위 maxSeries 시리즈 선택, name = instance_name ?: domain
- Create: `static/chat-widget/render/widgets/line-chart.js` — buildLineChart(widget): 손수 SVG polyline 멀티시리즈 + 범례 + 시각축 라벨(시작/끝) + y 최소/최대, empty 처리
- Modify: `static/chat-widget/render/dispatch.js` — `metric_line → buildLineChart`
- Modify: `static/chat-widget/chat-widget.css` — 라인 색 토큰(기존 seg-*/sev-* 토큰 재사용)
- Modify: `docs/prototype/chatbot-widgets/widget-contract.d.ts` — MetricLineWidget 추가
- Modify: `static/chat-widget/preview.html` — metric_line 목업 시나리오(실물 promql)
- Tests: `WidgetBuilderTest`(metricLine 시리즈 상한·빈), `test/line-chart.test.mjs`(구조·스케일·xss는 xss.test.mjs 빌더 목록에 추가)

**Steps (TDD):**
- [ ] WidgetBuilderTest 실패 테스트 → Widget/빌더 구현 → 통과
- [ ] line-chart.test.mjs 실패 테스트 → 렌더러 구현 → node --test 통과 (xss 포함)
- [ ] `./gradlew test` + node --test → commit

### Task 4: 질문 히스토리 REST 배선

**Files:**
- Modify: `chat/interfaces/ChatRequest.kt` — `history: List<ChatHistoryMessage> = emptyList()`, `ChatHistoryMessage(role: String, content: String)`
- Modify: `chat/interfaces/ChatController.kt` — history → ConversationMessage 매핑(role "assistant"→ASSISTANT, 그 외 USER), `takeLast(props.router.historyTurns - 1)` (Slack fetch와 동일 상한)
- Modify: `static/chat-widget/chat-widget.js` — sendQuestion 페이로드에 history 추가: `buildHistory(state.messages, limit)` 사용
- Create: `static/chat-widget/render/history.js` — `buildHistory(messages, limit)`: loading/error 제외, {role, content}만, 마지막 limit개 (테스트 가능한 순수 함수)
- Tests: `ChatControllerTest`(history 매핑·상한·이상 role), `test/history.test.mjs`
- E2E: 서버 재기동 후 ① "admin 프로젝트 CPU 사용률 TopN" → ② history 포함 "메모리는?" 류 후속질문이 CLARIFY로 새지 않는지 실측

**Steps (TDD):**
- [ ] ChatControllerTest 실패 테스트 → ChatRequest/Controller 구현 → 통과
- [ ] history.test.mjs 실패 테스트 → buildHistory + chat-widget.js 배선 → 통과
- [ ] 전체 테스트 + E2E 실측 → commit

### Task 5: 문서 갱신 + 마무리

- README(위젯 표에 metric_line·시계열 예시 질문, 동작 흐름), requirements.md 작업범위, phase2/plan.md, architecture.md 설정 표(trend 키), widget-contract.d.ts는 Task 3에서.
- 전체 테스트 재실행 → E2E 재확인 → commit.
