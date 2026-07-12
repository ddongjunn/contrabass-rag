# 챗봇 위젯 렌더링 파이프라인 설계 (프론트엔드)

- **작성일**: 2026-07-12
- **대상 저장소**: `contrabass-rag` (ragbot-server) — `src/main/resources/static/chat-widget/`
- **역할**: 프론트엔드 (FE-A 렌더러·바인딩 + FE-B 디자인·비주얼 통합)
- **상태**: 설계 확정 → 구현 계획 대기
- **관련 설계(백엔드 마스터)**: [`2026-07-09-chatbot-aggregation-widgets-design.md`](./2026-07-09-chatbot-aggregation-widgets-design.md)
- **계약 원본**: [`docs/prototype/chatbot-widgets/widget-contract.d.ts`](../../prototype/chatbot-widgets/widget-contract.d.ts) · 목업 [`mock-responses.json`](../../prototype/chatbot-widgets/mock-responses.json)

## 1. 배경 / 문제

`POST /api/chat`은 이미 `ChatResponse { answer, sources, widgets, followups }` 계약으로 확정됐고,
백엔드 1a(`metric_rank`·`inventory_count`·`followups`)는 main에 구현 완료, 1b(나머지 4종 + `spark`)는
계약 확정 상태로 목업을 반환 중이다. 그러나 **실제 프론트 위젯**(`chat-widget.js`)은 `answer` 평문만
말풍선에 그리고 있고 `widgets`/`followups` 필드를 **전혀 처리하지 않는다**. 이 스펙은 그 렌더링
파이프라인을 만든다.

## 2. 목표 / 비목표

**목표**
- `ChatResponse.widgets`(6종) + `followups`를 웹 위젯으로 렌더
- XSS 안전(`textContent` 전용, `innerHTML` 조립 전면 금지)을 **구조적으로** 보장
- 토스뱅크 스타일 비주얼 + primary `#F76205` 기반 검증된 팔레트 적용
- 순수함수 유닛테스트로 렌더 로직·이스케이프 회귀 방지
- 외부 사이트 임베드에 대비한 CSS/DOM 격리(Shadow DOM) — 임베드 *구현*은 별도 스펙, 여기선 격리 경계만 확보

**비목표**
- 열림/닫힘 애니메이션·리사이즈·드래그 위치이동 (→ 별도 UX 스펙)
- 외부 실서비스 임베드 로더·CORS 설정 (→ 별도 임베드 스펙)
- 프레임워크(Preact/React) 도입·레포 분리 (아래 §11 근거로 보류)
- 백엔드 계약 변경 (`Widget.kt`·`ChatResponse`는 그대로 소비만)
- Phase 2 `resource_dashboard` 위젯 (백엔드 Phase 2 대기)
- 다크 테마 (기존 라이트 브랜드 유지)

## 3. 범위 — 위젯 6종 + 상태 + 칩

계약(`widget-contract.d.ts`)의 6종 전부를 이번 범위에 포함한다. 1b가 목업이어도 계약이 확정이고
`mock-responses.json`으로 선개발 가능하므로, 백엔드 1b 실집계가 붙는 대로 바로 실전 투입되게 한다.

| 위젯 | 폼 | 색상 역할 |
|---|---|---|
| `metric_rank` | 랭킹 막대(행별) + 옵션 `spark` | severity(%지표) 또는 액센트(B/s 등) |
| `inventory_count` | 숫자 히어로 카드 | 없음(잉크) |
| `quota_gauge` | 항목별 막대(used/quota) | severity, 무제한(`quota=null`)은 중립 |
| `project_usage_bar` | 프로젝트별 막대 | severity, 무제한은 중립 |
| `status_donut` | **도넛**(막대로 대체 금지) + 범례 | 세그먼트 `level`(good/warn/crit/muted) |
| `threshold_banner` | 메시지 배너 | level(보통 CRIT/WARN) |
| empty 상태 | 중립 상태 카드 | — |
| error/폴백 | 위젯 없음 → 텍스트 버블 | — |

- `spark`(range 시계열)는 **있으면 그리고 없으면 미표시**. 백엔드는 실경로에서 당분간 `null` 유지
  방침(가짜 추세선=환각 회피, 백엔드 이슈 §3)이라 실제로는 당분간 안 그려지지만 계약상 필드는 지원.
- `metric_trend`(단일 지표 큰 시계열)·`metric_gauge`(반원 게이지)는 계약 6종 밖의 갤러리 후보 →
  **이번 범위 아님**. 계약에 없는 타입을 미리 만들지 않는다.

## 4. 아키텍처 — 순수 변환과 DOM 마운트 분리

핵심은 **"위젯 객체 → 순수 트리"**와 **"트리 → 실제 DOM"**을 분리하는 것. XSS 안전성이 취향이 아니라
구조로 보장된다.

```
widget(JSON) ──build*(widget)──▶ PlainNode(트리) ──mount(node)──▶ 실제 DOM
                순수함수                              공용 1곳
              (테스트 대상)                         (textContent 전용)
```

### 4.1 PlainNode

DOM에 붙지 않은 순수 객체. `build*` 함수의 입출력이자 유닛테스트의 검증 대상.

```js
// PlainNode = {
//   tag: "div",              // 필수
//   ns: "svg",               // 선택 — SVG 네임스페이스가 필요할 때만
//   className: "rk-track",   // 선택
//   text: "91.2%",           // 선택 — 있으면 textContent로 설정 (children과 배타)
//   attrs: { role, "aria-label", style, ... },  // 선택 — 값은 문자열
//   children: [ PlainNode, ... ],               // 선택
// }
```

### 4.2 mount()

**DOM을 만드는 유일한 함수.** 코드베이스 전체에서 `innerHTML`은 이 함수도 포함해 아무 데서도 안 쓴다.

```js
function mount(node) {
  const el = node.ns === "svg"
    ? document.createElementNS("http://www.w3.org/2000/svg", node.tag)
    : document.createElement(node.tag);
  if (node.className) el.setAttribute("class", node.className);
  if (node.attrs) for (const [k, v] of Object.entries(node.attrs)) el.setAttribute(k, v);
  if (node.text != null) el.textContent = node.text;      // ← XSS 차단 지점
  if (node.children) for (const c of node.children) el.append(mount(c));
  return el;
}
```

- `attrs.style`은 문자열로 전달(예: `"width:91%"`). 폭 등 수치는 `build*`가 계산해 문자열로 넣는다.
- **불변식**: 사용자·백엔드 데이터(instanceName, projectName, display, title, detail 등)는 전부
  `node.text`로만 들어간다. `attrs`에는 값에서 파생된 안전한 수치/토큰만(폭 %, severity 클래스).

### 4.3 디스패치

```js
const BUILDERS = {
  metric_rank: buildMetricRank,
  inventory_count: buildInventoryCount,
  quota_gauge: buildQuotaGauge,
  project_usage_bar: buildProjectUsageBar,
  status_donut: buildStatusDonut,
  threshold_banner: buildThresholdBanner,
};

function buildWidget(widget) {
  const builder = BUILDERS[widget.type];
  if (!builder) return null;                 // 모르는 type(미래 위젯)은 조용히 스킵
  if (widget.empty) return buildEmptyState(widget);
  return builder(widget);
}
```

- 알 수 없는 `type`은 `null` → 렌더 스킵(구버전 프론트가 배포돼 있어도 캡션은 남고 안 깨짐).
- `empty: true`인 위젯은 타입 무관하게 공용 빈 상태 카드로.

### 4.4 Shadow DOM 격리

위젯(및 챗 패널)의 루트를 **Shadow DOM** 안에 마운트한다. 이유는 외부 사이트 임베드 시
호스트 페이지 CSS가 위젯에 새거나(그 반대로) 클래스명이 충돌하는 것을 막기 위함(Intercom·Crisp 등
임베드형 위젯의 표준 방식). 스타일은 shadow root 안에 `<style>`로 주입한다. 임베드 로더 자체는
별도 스펙이지만, 격리 경계는 지금 세우지 않으면 나중에 되돌리기 비싸므로 이번에 확보한다.

> 리스크: Shadow DOM 안에서는 전역 CSS·폰트 상속이 끊긴다. 폰트·CSS 변수(팔레트)는 shadow root
> `<style>`에 자족적으로 정의한다. 접근성(aria-live 등)은 shadow 안에서도 동작하나 스크린리더 호환은
> 구현 시 실제 검증(§9).

## 5. 파일 구조 (번들러 없음, 브라우저 네이티브 ES 모듈)

```
chat-widget/
  index.html            (기존 — <script type="module"> 로 진입)
  chat-widget.js        (기존 개편 — fetch/state/폼/Shadow 마운트, dispatch 호출)
  chat-widget.css       (기존 개편 — 팔레트 토큰 + 위젯 스타일; shadow root에 주입)
  render/
    dom.js              (신규 — PlainNode 타입 주석 + mount())
    dispatch.js         (신규 — BUILDERS 매핑 + buildWidget/buildEmptyState)
    format.js           (신규 — 폭% 등 표시 보조. display는 백엔드 값 그대로 사용)
    widgets/
      metric-rank.js  inventory-count.js  quota-gauge.js
      project-usage-bar.js  status-donut.js  threshold-banner.js
  test/
    *.test.mjs          (신규 — node --test 대상)
  package.json          (신규 최소 — { "type": "module", "scripts": { "test": "node --test" } })
```

- `package.json`은 테스트 스크립트와 ESM 선언만. 빌드·의존성 없음.
- `build*` 모듈은 DOM API를 import하지 않는다(순수) → jsdom 없이 테스트 가능.

## 6. 데이터 흐름 (렌더 순서)

```
응답 수신(chat-widget.js)
  → 어시스턴트 행 생성: [아바타] + [본문 컬럼]
      본문 컬럼:
        1. 캡션 = answer 평문 (말풍선 없음, 배경 위 텍스트)   ← 항상
        2. widgets 순회: buildWidget(w) → mount → 카드로 append
        3. followups 칩: 있으면 렌더, 클릭 시 해당 문자열 재질문
  → widgets 비었으면(폴백/DOC/에러): 캡션을 기존 텍스트 버블로 (자연 폴백)
```

- **레이아웃 결정**: 캡션은 말풍선 없이 평문, 위젯은 그 아래 **독립 카드**. (백엔드 §7의 "캡션+위젯
  한 카드 병합"을 프론트 판단으로 오버라이드 — 실제 AI 답변처럼 평문/위젯이 분리돼 더 읽기 쉬움.)
- `sources`(DOC 경로)는 기존대로 캡션 아래 출처칩.
- `followups` 칩 클릭 = 그 문자열을 새 질문으로 전송(기존 `sendQuestion` 재사용).

## 7. 비주얼 / 디자인 계약

토스뱅크 스타일: 흰 배경 + 라운드 + 부드러운 그림자, **카드 테두리 없음**. 값 숫자는 굵은 잉크색,
severity 색은 막대·칩·아이콘에만(텍스트에 데이터 색 금지 — dataviz 원칙).

### 7.1 팔레트 토큰 (OKLCH로 primary 대역에 맞춰 계산·검증)

```css
--primary:      #F76205;  --primary-soft: #fff1e8;   /* 런처·아바타·액센트 */
--good:  #1eb85c;  --good-soft: #e7f8ef;  --good-ink: #0f8043;
--warn:  #f0b429;  --warn-soft: #fdf6e3;  --warn-ink: #9a7400;  /* H=82, primary와 분리 */
--crit:  #f03e3e;  --crit-soft: #fdecec;  --crit-ink: #c62828;
--info:  #3182f6;  --info-soft: #e8f2ff;  --info-ink: #1b64da;  /* 토스 블루 */
--ink:   #191f28;  --ink2: #4e5968;  --muted: #8b95a1;
--line:  #f2f4f6;  --bg: #f9fafb;
```

- severity 막대 색은 `--good/--warn/--crit`. 값 텍스트는 항상 `--ink`(굵게).
- 칩/배지: 연한 틴트 배경(`*-soft`) + 진한 잉크 텍스트(`*-ink`). 색을 텍스트에 직접 안 입힘.
- **relief 규칙**: 값이 굵은 잉크 숫자로 항상 표시되므로, 막대 색은 대비를 억지로 낮추지 않고
  primary와 명도·채도를 맞춘 선명한 색을 쓴다(dataviz relief: 라벨이 값을 이미 전달).

### 7.2 위젯별 규칙

- `status_donut`: **도넛 유지**(막대 대체 금지). SVG `stroke-linecap` **round 금지**(형태 뭉개짐) —
  세그먼트 사이 작은 갭. 옆에 범례(색점 + 이름 + 값).
- `threshold_banner`/메시지: 토스식 배너 — `*-soft` 배경 + 색 아이콘 원(`!`/`i`) + 굵은 제목 +
  회색 부연.
- empty/error: 중립 회색 상태 카드(라운드 아이콘 박스 + 메시지 + 힌트).
- `quota_gauge`/`project_usage_bar` 무제한(`quota`/`value == null`): 중립 회색 막대 + "무제한" 텍스트,
  severity 없음.
- `severity`/`display`는 **백엔드 값만 신뢰**. 프론트는 재계산·재포맷 금지(계약 불변식).

## 8. 상태 / 에러 처리

- **empty**(`widget.empty === true` 또는 `rows: []`): 공용 빈 상태 카드. `answer`는 백엔드 문구 유지.
- **위젯 없음**(`widgets: []` — DOC·CLARIFY·조회 실패): 캡션을 기존 텍스트 버블로 렌더(자연 폴백).
- **네트워크/HTTP 에러**: 기존 `chat-widget.js`의 에러 버블 유지.
- **모르는 type**: 스킵(§4.3). 캡션·다른 위젯은 정상 렌더.

## 9. 테스트 전략 (Node 내장 `node --test`, 외부 의존성 0)

- **build* 순수함수 유닛테스트** — 각 위젯 빌더가 주어진 widget 객체에 대해 기대하는 PlainNode 트리를
  내는지. `mock-responses.json`의 시나리오를 fixture로 사용.
  - `metric_rank`: %/B/s 단위 분기, severity별 막대 색 클래스, `spark` 유무, empty
  - `quota_gauge`/`project_usage_bar`: 무제한(null) 처리
  - `status_donut`: 세그먼트 dasharray 계산, level→색
  - `threshold_banner`: level→배너 클래스
- **XSS 회귀 테스트** — 악의적 문자열(`<img src=x onerror=...>`, `"><script>`)을 instanceName/
  title/detail 등에 넣었을 때, 결과 PlainNode의 `text`에 원문이 그대로 담기고 `attrs`에는 안 들어가는지
  (mount가 textContent로 넣으므로 실행 불가). `innerHTML` 미사용을 소스 grep으로도 확인.
- **디스패치 테스트** — 모르는 type → null(스킵), empty → 빈 상태.
- **수동 브라우저 검증** — `/verify`로 실제 렌더·Shadow DOM 격리·접근성(aria-live) 확인.

## 10. 접근성

- 어시스턴트 답변에 아바타 + `aria-live="polite"`(기존 유지).
- 막대·게이지·도넛·배너 아이콘 등 **마크는 데코**(`aria-hidden="true"`) — 값·이름·범례가 이미 가시
  텍스트로 렌더되므로 스크린리더는 그 텍스트로 읽는다. 사용자 문자열을 attribute(aria-label 등)에
  넣지 않아 §9(값은 text에만)와도 정합.
- 위젯은 항상 평문 `answer`를 동반 → 스크린리더는 평문으로도 완전한 정보 접근.
- Shadow DOM 안 aria 동작은 구현 시 실측 검증.

## 11. 결정 / 리스크

- **바닐라 JS 유지 · 이 레포 · 프레임워크·레포 분리 보류**(2026-07-12 확정). 근거: 지금 스코프(6종
  렌더 + XSS + followups)는 바닐라로 충분하고, 리사이즈·드래그·임베드도 표준 DOM/Shadow DOM으로 가능
  (오히려 임베드는 바닐라+Shadow DOM이 업계 표준). A안이 정적 파일 결합도를 낮게 유지하므로 훗날 진짜
  분리가 필요해질 때 비용이 0에 가깝다 → 지금 분리는 실익 없는 인프라 비용만 발생.
- **Shadow DOM 리스크**: 전역 CSS/폰트 상속 단절 → shadow root 안에 자족적 스타일 주입으로 대응.
  임베드 로더는 별도 스펙이지만 격리 경계는 이번에 확보(되돌리기 비용 회피).
- **1b 목업 의존**: 백엔드 1b 실집계 전까지 `mock-responses.json`으로 선개발. 계약이 확정이라 안전.
- **백엔드 §7 레이아웃 오버라이드**: "캡션+위젯 한 카드" → "캡션 평문 + 분리 카드"로 프론트 판단 변경.
  API 계약(`answer`/`widgets` 분리)엔 영향 없음.

## 12. 후속 (별도 스펙)

1. **UX/애니메이션** — 열림/닫힘 효과, 창 리사이즈, 드래그 위치이동.
2. **외부 임베드** — script 로더 배포, `window.CONTRABASS_CHAT_API_BASE`/`apiBase` 활용, CORS 설정,
   Shadow DOM 위에 얹는 임베드 진입점.
