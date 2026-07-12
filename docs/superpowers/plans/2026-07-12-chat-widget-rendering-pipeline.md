# 챗봇 위젯 렌더링 파이프라인 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /api/chat` 응답의 `widgets`(6종) + `followups`를 웹 챗 위젯에 렌더링한다. 순수 변환(build*)과 DOM 마운트(mount)를 분리해 XSS를 구조적으로 차단하고, Shadow DOM으로 격리한다.

**Architecture:** `widget(JSON) → build*(순수함수) → PlainNode 트리 → mount() → DOM`. 각 위젯 타입별 빌더는 DOM API를 import하지 않는 순수함수라 jsdom 없이 `node --test`로 검증한다. `mount()`만 `document.createElement`/`textContent`로 실제 DOM을 만들며, `innerHTML`은 코드베이스 어디에도 쓰지 않는다.

**Tech Stack:** 바닐라 JS(브라우저 네이티브 ES 모듈, 번들러 없음), Node 내장 test runner(`node --test`, 외부 의존성 0), Shadow DOM, SVG.

**대상 디렉터리:** `src/main/resources/static/chat-widget/`
**브랜치:** `docs/chat-widget-rendering-spec` (현재 브랜치에서 계속)
**스펙:** `docs/superpowers/specs/2026-07-12-chat-widget-rendering-pipeline-design.md`

---

## File Structure

```
src/main/resources/static/chat-widget/
  index.html              (수정 — <script type="module">, Shadow 호스트 + <template> 크롬)
  chat-widget.js          (수정 — Shadow 마운트, fetch/state/폼, 메시지 렌더에 위젯 배선)
  chat-widget.css         (수정 — 팔레트 토큰 + 위젯 스타일, shadow root에 로드)
  package.json            (신규 — { "type": "module", "scripts": { "test": "node --test" } })
  render/
    dom.js                (신규 — h() 헬퍼, mount())
    format.js             (신규 — clampPct(), barWidth())
    dispatch.js           (신규 — BUILDERS 매핑, buildWidget(), buildEmptyState())
    widgets/
      metric-rank.js
      inventory-count.js
      quota-gauge.js
      project-usage-bar.js
      status-donut.js
      threshold-banner.js
  test/
    dom.test.mjs
    format.test.mjs
    metric-rank.test.mjs
    inventory-count.test.mjs
    quota-gauge.test.mjs
    project-usage-bar.test.mjs
    status-donut.test.mjs
    threshold-banner.test.mjs
    dispatch.test.mjs
    xss.test.mjs
```

**책임 분리:** `dom.js` = 트리→DOM 변환(유일한 DOM 접점). `widgets/*.js` = 위젯별 JSON→PlainNode 순수함수. `dispatch.js` = 타입 라우팅. `chat-widget.js` = 네트워크/상태/UI 셸. 각 파일 하나의 책임.

**팔레트 토큰(스펙 §7.1 — 확정):**
```
--primary #F76205  --primary-soft #fff1e8
--good #1eb85c  --good-soft #e7f8ef  --good-ink #0f8043
--warn #f0b429  --warn-soft #fdf6e3  --warn-ink #9a7400
--crit #f03e3e  --crit-soft #fdecec  --crit-ink #c62828
--info #3182f6  --info-soft #e8f2ff  --info-ink #1b64da
--ink #191f28  --ink2 #4e5968  --muted #8b95a1  --line #f2f4f6  --bg #f9fafb
```

---

## Task 1: 테스트 하네스 셋업

**Files:**
- Create: `src/main/resources/static/chat-widget/package.json`
- Create: `src/main/resources/static/chat-widget/render/dom.js` (스텁)
- Create: `src/main/resources/static/chat-widget/test/dom.test.mjs`

- [ ] **Step 1: package.json 작성**

Create `src/main/resources/static/chat-widget/package.json`:

```json
{
  "name": "contrabass-chat-widget",
  "private": true,
  "type": "module",
  "scripts": {
    "test": "node --test"
  }
}
```

- [ ] **Step 2: 스모크 테스트 작성 (실패 확인용)**

Create `src/main/resources/static/chat-widget/render/dom.js` with a stub:

```js
export function h() {
  throw new Error("not implemented");
}
```

Create `src/main/resources/static/chat-widget/test/dom.test.mjs`:

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { h } from "../render/dom.js";

test("h is a function", () => {
  assert.equal(typeof h, "function");
});
```

- [ ] **Step 3: 테스트 실행 (통과 확인 — 하네스 동작 검증)**

Run: `cd src/main/resources/static/chat-widget && node --test`
Expected: 1 test passing (`h is a function`). 하네스가 동작함을 확인.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/chat-widget/package.json \
        src/main/resources/static/chat-widget/render/dom.js \
        src/main/resources/static/chat-widget/test/dom.test.mjs
git commit -m "test: chat-widget node --test 하네스 셋업"
```

---

## Task 2: dom.js — h() 헬퍼 + mount()

**Files:**
- Modify: `src/main/resources/static/chat-widget/render/dom.js`
- Modify: `src/main/resources/static/chat-widget/test/dom.test.mjs`

- [ ] **Step 1: h() 테스트 작성**

Replace `test/dom.test.mjs` 전체:

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { h, mount } from "../render/dom.js";

test("h builds a PlainNode with tag only", () => {
  assert.deepEqual(h("div"), { tag: "div" });
});

test("h includes className, text, attrs, ns", () => {
  const node = h("i", { className: "bar", text: "91%", attrs: { style: "width:91%" }, ns: "svg" });
  assert.deepEqual(node, { tag: "i", ns: "svg", className: "bar", text: "91%", attrs: { style: "width:91%" } });
});

test("h coerces text to string", () => {
  assert.equal(h("span", { text: 342 }).text, "342");
});

test("h nests children only when non-empty", () => {
  assert.equal(h("div", {}, []).children, undefined);
  const parent = h("div", {}, [h("span")]);
  assert.deepEqual(parent.children, [{ tag: "span" }]);
});
```

- [ ] **Step 2: 테스트 실행 (실패 확인)**

Run: `cd src/main/resources/static/chat-widget && node --test test/dom.test.mjs`
Expected: FAIL ("not implemented" 또는 mount import 에러)

- [ ] **Step 3: h() 구현**

Replace `render/dom.js` 전체:

```js
const SVG_NS = "http://www.w3.org/2000/svg";

// PlainNode = { tag, ns?, className?, text?, attrs?, children? }
export function h(tag, opts = {}, children = []) {
  const node = { tag };
  if (opts.ns) node.ns = opts.ns;
  if (opts.className) node.className = opts.className;
  if (opts.text != null) node.text = String(opts.text);
  if (opts.attrs) node.attrs = opts.attrs;
  if (children && children.length) node.children = children;
  return node;
}

// 실제 DOM을 만드는 유일한 함수. innerHTML은 절대 쓰지 않는다.
export function mount(node, doc = globalThis.document) {
  const el = node.ns === "svg"
    ? doc.createElementNS(SVG_NS, node.tag)
    : doc.createElement(node.tag);
  if (node.className) el.setAttribute("class", node.className);
  if (node.attrs) {
    for (const [k, v] of Object.entries(node.attrs)) el.setAttribute(k, String(v));
  }
  if (node.text != null) el.textContent = node.text;
  if (node.children) {
    for (const c of node.children) el.append(mount(c, doc));
  }
  return el;
}
```

- [ ] **Step 4: mount() 테스트 작성 (가짜 document로 jsdom 없이 검증)**

Append to `test/dom.test.mjs`:

```js
// 최소 가짜 DOM — 실제 브라우저 API의 형태만 흉내
function fakeDoc() {
  const make = (tag, ns) => ({
    tag, ns, attrs: {}, text: null, kids: [],
    setAttribute(k, v) { this.attrs[k] = v; },
    set textContent(v) { this.text = v; },
    append(...cs) { this.kids.push(...cs); },
  });
  return {
    createElement: (tag) => make(tag, null),
    createElementNS: (ns, tag) => make(tag, ns),
  };
}

test("mount puts text into textContent (never markup)", () => {
  const el = mount(h("span", { text: "<img src=x>" }), fakeDoc());
  assert.equal(el.text, "<img src=x>");   // 문자열 그대로, 실행 불가
  assert.equal(el.tag, "span");
});

test("mount sets attrs via setAttribute and nests children", () => {
  const el = mount(h("div", { className: "card", attrs: { role: "img" } }, [h("i", { text: "x" })]), fakeDoc());
  assert.equal(el.attrs.class, "card");
  assert.equal(el.attrs.role, "img");
  assert.equal(el.kids.length, 1);
  assert.equal(el.kids[0].text, "x");
});

test("mount uses createElementNS for svg nodes", () => {
  const el = mount(h("circle", { ns: "svg" }), fakeDoc());
  assert.equal(el.ns, "http://www.w3.org/2000/svg");
});
```

- [ ] **Step 5: 테스트 실행 (통과 확인)**

Run: `cd src/main/resources/static/chat-widget && node --test test/dom.test.mjs`
Expected: 7 tests passing

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/chat-widget/render/dom.js \
        src/main/resources/static/chat-widget/test/dom.test.mjs
git commit -m "feat: PlainNode h() 헬퍼 + mount() (textContent 전용)"
```

---

## Task 2b: format.js — 폭 계산 헬퍼

**Files:**
- Create: `src/main/resources/static/chat-widget/render/format.js`
- Create: `src/main/resources/static/chat-widget/test/format.test.mjs`

- [ ] **Step 1: 테스트 작성**

Create `test/format.test.mjs`:

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { clampPct, barWidth } from "../render/format.js";

test("clampPct clamps to 0..100", () => {
  assert.equal(clampPct(-5), 0);
  assert.equal(clampPct(150), 100);
  assert.equal(clampPct(42), 42);
});

test("clampPct returns 0 for null/NaN", () => {
  assert.equal(clampPct(null), 0);
  assert.equal(clampPct(NaN), 0);
});

test("barWidth scales value against max", () => {
  assert.equal(barWidth(50, 100), 50);
  assert.equal(barWidth(91.2, 91.2), 100);
});

test("barWidth returns 0 when max is 0 or missing", () => {
  assert.equal(barWidth(5, 0), 0);
  assert.equal(barWidth(5, null), 0);
});
```

- [ ] **Step 2: 테스트 실행 (실패 확인)**

Run: `cd src/main/resources/static/chat-widget && node --test test/format.test.mjs`
Expected: FAIL (module not found)

- [ ] **Step 3: 구현**

Create `render/format.js`:

```js
export function clampPct(n) {
  if (n == null || Number.isNaN(n)) return 0;
  return Math.max(0, Math.min(100, n));
}

export function barWidth(value, max) {
  if (!(max > 0)) return 0;
  return clampPct((value / max) * 100);
}
```

- [ ] **Step 4: 테스트 실행 (통과 확인)**

Run: `cd src/main/resources/static/chat-widget && node --test test/format.test.mjs`
Expected: 4 tests passing

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/chat-widget/render/format.js \
        src/main/resources/static/chat-widget/test/format.test.mjs
git commit -m "feat: format 헬퍼 clampPct/barWidth"
```

---

## Task 3: metric_rank 빌더 (+ spark)

**Files:**
- Create: `src/main/resources/static/chat-widget/render/widgets/metric-rank.js`
- Create: `src/main/resources/static/chat-widget/test/metric-rank.test.mjs`

빌더 규칙(스펙 §7.2): 값(`display`)은 잉크색 텍스트, 막대 색만 severity. `%` 지표는 severity→색, 그 외(B/s 등)는 severity=null→액센트. `spark` 있으면 미니 라인, 없으면 미표시.

- [ ] **Step 1: 기본 랭킹 테스트 작성**

Create `test/metric-rank.test.mjs`:

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { buildMetricRank } from "../render/widgets/metric-rank.js";

// PlainNode 트리에서 className으로 첫 노드 찾기 (테스트 헬퍼)
function find(node, className) {
  if (node.className && node.className.split(" ").includes(className)) return node;
  for (const c of node.children || []) {
    const hit = find(c, className);
    if (hit) return hit;
  }
  return null;
}
function findAll(node, className, acc = []) {
  if (node.className && node.className.split(" ").includes(className)) acc.push(node);
  for (const c of node.children || []) findAll(c, className, acc);
  return acc;
}

const cpu = {
  type: "metric_rank", title: "CPU 사용률이 높은 인스턴스", unit: "%", window: "5m",
  promql: "topk(5, ...)", empty: false,
  rows: [
    { instanceName: "web-prod-07", projectName: "service-prod", value: 91.2, display: "91.2%", severity: "CRIT" },
    { instanceName: "batch-11", projectName: "data-platform", value: 73.4, display: "73.4%", severity: "WARN" },
    { instanceName: "cache-03", projectName: "service-prod", value: 61.9, display: "61.9%", severity: "GOOD" },
  ],
};

test("metric_rank renders one .rk row per data row", () => {
  const node = buildMetricRank(cpu);
  assert.equal(findAll(node, "rk").length, 3);
});

test("metric_rank puts display value in text, name in text", () => {
  const node = buildMetricRank(cpu);
  const vals = findAll(node, "rk-val").map((n) => n.text);
  assert.deepEqual(vals, ["91.2%", "73.4%", "61.9%"]);
  const names = findAll(node, "rk-nm").map((n) => n.text);
  assert.deepEqual(names, ["web-prod-07", "batch-11", "cache-03"]);
});

test("metric_rank colors bar by severity class for % unit", () => {
  const node = buildMetricRank(cpu);
  const fills = findAll(node, "rk-track").map((t) => t.children[0].className);
  assert.deepEqual(fills, ["sev-crit", "sev-warn", "sev-good"]);
});

test("metric_rank bar width is relative to max value", () => {
  const node = buildMetricRank(cpu);
  const first = findAll(node, "rk-track")[0].children[0];
  assert.equal(first.attrs.style, "width:100%"); // 91.2 is the max
});

test("metric_rank uses accent when severity is null (non-% unit)", () => {
  const net = {
    type: "metric_rank", title: "네트워크 송신량", unit: "B/s", window: "5m", promql: "topk(...)", empty: false,
    rows: [{ instanceName: "gw-01", projectName: "network", value: 430182400, display: "410.25 MB/s", severity: null }],
  };
  const fill = find(buildMetricRank(net), "rk-track").children[0];
  assert.equal(fill.className, "sev-accent");
});

test("metric_rank omits project span when projectName is null", () => {
  const noProj = { ...cpu, rows: [{ instanceName: "x", projectName: null, value: 5, display: "5%", severity: "GOOD" }] };
  assert.equal(find(buildMetricRank(noProj), "rk-prj"), null);
});
```

- [ ] **Step 2: 테스트 실행 (실패 확인)**

Run: `cd src/main/resources/static/chat-widget && node --test test/metric-rank.test.mjs`
Expected: FAIL (module not found)

- [ ] **Step 3: 빌더 구현**

Create `render/widgets/metric-rank.js`:

```js
import { h } from "../dom.js";
import { barWidth } from "../format.js";

const SEV_CLASS = { GOOD: "sev-good", WARN: "sev-warn", CRIT: "sev-crit" };
function sevClass(sev) {
  return SEV_CLASS[sev] || "sev-accent";
}

function sparkNode(spark) {
  const w = 96, hgt = 26;
  const max = spark.reduce((m, v) => Math.max(m, v), 0) || 1;
  const min = spark.reduce((m, v) => Math.min(m, v), spark[0]);
  const span = max - min || 1;
  const step = spark.length > 1 ? w / (spark.length - 1) : w;
  const pts = spark
    .map((v, i) => `${(i * step).toFixed(1)},${(hgt - ((v - min) / span) * hgt).toFixed(1)}`)
    .join(" ");
  return h("svg", { ns: "svg", className: "rk-spark", attrs: { viewBox: `0 0 ${w} ${hgt}`, preserveAspectRatio: "none", "aria-hidden": "true" } }, [
    h("polyline", { ns: "svg", attrs: { points: pts, fill: "none", "stroke-width": "2", "stroke-linecap": "round", "stroke-linejoin": "round" } }),
  ]);
}

export function buildMetricRank(w) {
  const max = w.rows.reduce((m, r) => Math.max(m, r.value), 0);
  const rows = w.rows.map((r) => {
    const width = Math.max(2, barWidth(r.value, max));
    const nameChildren = [h("span", { className: "rk-nm", text: r.instanceName })];
    if (r.projectName) nameChildren.push(h("span", { className: "rk-prj", text: r.projectName }));
    const rowChildren = [
      h("div", { className: "rk-top" }, [
        h("span", {}, nameChildren),
        h("span", { className: "rk-val", text: r.display }),
      ]),
      h("div", { className: "rk-track", attrs: { role: "img", "aria-label": `${r.instanceName} ${r.display}` } }, [
        h("i", { className: sevClass(r.severity), attrs: { style: `width:${width.toFixed(0)}%` } }),
      ]),
    ];
    if (Array.isArray(r.spark) && r.spark.length > 1) rowChildren.push(sparkNode(r.spark));
    return h("div", { className: "rk" }, rowChildren);
  });
  return h("div", { className: "card widget" }, [
    h("div", { className: "eyebrow" }, [
      h("span", { text: w.title }),
      h("span", { text: `최근 ${w.window}` }),
    ]),
    ...rows,
    h("div", { className: "wfoot" }, [h("span", { className: "prom", text: w.promql })]),
  ]);
}
```

- [ ] **Step 4: 테스트 실행 (통과 확인)**

Run: `cd src/main/resources/static/chat-widget && node --test test/metric-rank.test.mjs`
Expected: 6 tests passing

- [ ] **Step 5: spark 테스트 추가**

Append to `test/metric-rank.test.mjs`:

```js
test("metric_rank renders spark polyline when spark present", () => {
  const withSpark = {
    ...cpu,
    rows: [{ instanceName: "web-prod-07", projectName: "service-prod", value: 91.2, display: "91.2%", severity: "CRIT", spark: [58, 61, 67, 80, 91] }],
  };
  assert.notEqual(find(buildMetricRank(withSpark), "rk-spark"), null);
});

test("metric_rank omits spark when absent", () => {
  assert.equal(find(buildMetricRank(cpu), "rk-spark"), null);
});
```

- [ ] **Step 6: 테스트 실행 (통과 확인 — 구현은 Step 3에 이미 포함)**

Run: `cd src/main/resources/static/chat-widget && node --test test/metric-rank.test.mjs`
Expected: 8 tests passing

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/static/chat-widget/render/widgets/metric-rank.js \
        src/main/resources/static/chat-widget/test/metric-rank.test.mjs
git commit -m "feat: metric_rank 위젯 빌더 (severity 색·spark)"
```

---

## Task 4: inventory_count 빌더

**Files:**
- Create: `src/main/resources/static/chat-widget/render/widgets/inventory-count.js`
- Create: `src/main/resources/static/chat-widget/test/inventory-count.test.mjs`

- [ ] **Step 1: 테스트 작성**

Create `test/inventory-count.test.mjs`:

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { buildInventoryCount } from "../render/widgets/inventory-count.js";

function find(node, cls) {
  if (node.className && node.className.split(" ").includes(cls)) return node;
  for (const c of node.children || []) { const h = find(c, cls); if (h) return h; }
  return null;
}

const w = { type: "inventory_count", label: "볼륨", total: 342, condition: "상태=available · 프로젝트 전체" };

test("inventory_count shows total as hero text", () => {
  assert.equal(find(buildInventoryCount(w), "hero").text, "342");
});

test("inventory_count shows label and condition", () => {
  const node = buildInventoryCount(w);
  assert.equal(find(node, "count-lbl").text, "볼륨");
  assert.equal(find(node, "count-cond").text, "상태=available · 프로젝트 전체");
});

test("inventory_count omits condition node when null", () => {
  assert.equal(find(buildInventoryCount({ ...w, condition: null }), "count-cond"), null);
});
```

- [ ] **Step 2: 테스트 실행 (실패 확인)**

Run: `cd src/main/resources/static/chat-widget && node --test test/inventory-count.test.mjs`
Expected: FAIL (module not found)

- [ ] **Step 3: 구현**

Create `render/widgets/inventory-count.js`:

```js
import { h } from "../dom.js";

export function buildInventoryCount(w) {
  const children = [
    h("div", { className: "hero", text: String(w.total) }),
    h("div", { className: "count-lbl", text: w.label }),
  ];
  if (w.condition) children.push(h("div", { className: "count-cond", text: w.condition }));
  return h("div", { className: "card widget count" }, children);
}
```

- [ ] **Step 4: 테스트 실행 (통과 확인)**

Run: `cd src/main/resources/static/chat-widget && node --test test/inventory-count.test.mjs`
Expected: 3 tests passing

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/chat-widget/render/widgets/inventory-count.js \
        src/main/resources/static/chat-widget/test/inventory-count.test.mjs
git commit -m "feat: inventory_count 위젯 빌더"
```

---

## Task 5: quota_gauge 빌더

**Files:**
- Create: `src/main/resources/static/chat-widget/render/widgets/quota-gauge.js`
- Create: `src/main/resources/static/chat-widget/test/quota-gauge.test.mjs`

규칙: `ratio`로 막대 폭. `quota == null`(무제한)은 중립 회색(`sev-muted`) + severity 없음.

- [ ] **Step 1: 테스트 작성**

Create `test/quota-gauge.test.mjs`:

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { buildQuotaGauge } from "../render/widgets/quota-gauge.js";

function findAll(node, cls, acc = []) {
  if (node.className && node.className.split(" ").includes(cls)) acc.push(node);
  for (const c of node.children || []) findAll(c, cls, acc);
  return acc;
}

const w = {
  type: "quota_gauge",
  items: [
    { resource: "vCPU", used: 820, quota: 1000, ratio: 0.82, display: "820 / 1000", severity: "WARN" },
    { resource: "디스크", used: 6100, quota: 10000, ratio: 0.61, display: "6100 / 10000 GB", severity: "GOOD" },
  ],
};

test("quota_gauge renders one row per item", () => {
  assert.equal(findAll(buildQuotaGauge(w), "rk").length, 2);
});

test("quota_gauge width from ratio, color from severity", () => {
  const node = buildQuotaGauge(w);
  const fills = findAll(node, "rk-track").map((t) => t.children[0]);
  assert.equal(fills[0].attrs.style, "width:82%");
  assert.equal(fills[0].className, "sev-warn");
  assert.equal(fills[1].className, "sev-good");
});

test("quota_gauge unlimited (quota null) is muted, no severity color", () => {
  const unlimited = { type: "quota_gauge", items: [{ resource: "vCPU", used: 8, quota: null, ratio: null, display: "8 / 무제한", severity: null }] };
  const node = buildQuotaGauge(unlimited);
  const fill = findAll(node, "rk-track")[0].children[0];
  assert.equal(fill.className, "sev-muted");
  assert.equal(findAll(node, "rk-val")[0].text, "8 / 무제한");
});
```

- [ ] **Step 2: 테스트 실행 (실패 확인)**

Run: `cd src/main/resources/static/chat-widget && node --test test/quota-gauge.test.mjs`
Expected: FAIL (module not found)

- [ ] **Step 3: 구현**

Create `render/widgets/quota-gauge.js`:

```js
import { h } from "../dom.js";
import { clampPct } from "../format.js";

const SEV_CLASS = { GOOD: "sev-good", WARN: "sev-warn", CRIT: "sev-crit" };

function gaugeRow(item) {
  const unlimited = item.quota == null || item.ratio == null;
  const cls = unlimited ? "sev-muted" : (SEV_CLASS[item.severity] || "sev-accent");
  const width = unlimited ? 100 : clampPct(item.ratio * 100);
  return h("div", { className: "rk" }, [
    h("div", { className: "rk-top" }, [
      h("span", { className: "rk-nm", text: item.resource }),
      h("span", { className: "rk-val", text: item.display }),
    ]),
    h("div", { className: "rk-track", attrs: { role: "img", "aria-label": `${item.resource} ${item.display}` } }, [
      h("i", { className: cls, attrs: { style: `width:${width.toFixed(0)}%` } }),
    ]),
  ]);
}

export function buildQuotaGauge(w) {
  return h("div", { className: "card widget" }, [
    h("div", { className: "eyebrow" }, [h("span", { text: "쿼터 사용량" })]),
    ...w.items.map(gaugeRow),
  ]);
}
```

- [ ] **Step 4: 테스트 실행 (통과 확인)**

Run: `cd src/main/resources/static/chat-widget && node --test test/quota-gauge.test.mjs`
Expected: 3 tests passing

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/chat-widget/render/widgets/quota-gauge.js \
        src/main/resources/static/chat-widget/test/quota-gauge.test.mjs
git commit -m "feat: quota_gauge 위젯 빌더 (무제한 처리)"
```

---

## Task 6: project_usage_bar 빌더

**Files:**
- Create: `src/main/resources/static/chat-widget/render/widgets/project-usage-bar.js`
- Create: `src/main/resources/static/chat-widget/test/project-usage-bar.test.mjs`

규칙: `value`(0~100 %)로 막대 폭. `value == null`(무제한)은 중립.

- [ ] **Step 1: 테스트 작성**

Create `test/project-usage-bar.test.mjs`:

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { buildProjectUsageBar } from "../render/widgets/project-usage-bar.js";

function findAll(node, cls, acc = []) {
  if (node.className && node.className.split(" ").includes(cls)) acc.push(node);
  for (const c of node.children || []) findAll(c, cls, acc);
  return acc;
}

const w = {
  type: "project_usage_bar", metric: "vCPU", unit: "%",
  rows: [
    { projectName: "service-prod", value: 82.0, display: "82%", severity: "WARN" },
    { projectName: "AUTOTEST", value: null, display: "무제한", severity: null },
  ],
};

test("project_usage_bar renders one row per project", () => {
  assert.equal(findAll(buildProjectUsageBar(w), "rk").length, 2);
});

test("project_usage_bar width from value, severity color", () => {
  const fill = findAll(buildProjectUsageBar(w), "rk-track")[0].children[0];
  assert.equal(fill.attrs.style, "width:82%");
  assert.equal(fill.className, "sev-warn");
});

test("project_usage_bar unlimited (value null) is muted full bar", () => {
  const fill = findAll(buildProjectUsageBar(w), "rk-track")[1].children[0];
  assert.equal(fill.className, "sev-muted");
  assert.equal(findAll(buildProjectUsageBar(w), "rk-val")[1].text, "무제한");
});
```

- [ ] **Step 2: 테스트 실행 (실패 확인)**

Run: `cd src/main/resources/static/chat-widget && node --test test/project-usage-bar.test.mjs`
Expected: FAIL (module not found)

- [ ] **Step 3: 구현**

Create `render/widgets/project-usage-bar.js`:

```js
import { h } from "../dom.js";
import { clampPct } from "../format.js";

const SEV_CLASS = { GOOD: "sev-good", WARN: "sev-warn", CRIT: "sev-crit" };

function usageRow(row) {
  const unlimited = row.value == null;
  const cls = unlimited ? "sev-muted" : (SEV_CLASS[row.severity] || "sev-accent");
  const width = unlimited ? 100 : clampPct(row.value);
  return h("div", { className: "rk" }, [
    h("div", { className: "rk-top" }, [
      h("span", { className: "rk-nm", text: row.projectName }),
      h("span", { className: "rk-val", text: row.display }),
    ]),
    h("div", { className: "rk-track", attrs: { role: "img", "aria-label": `${row.projectName} ${row.display}` } }, [
      h("i", { className: cls, attrs: { style: `width:${width.toFixed(0)}%` } }),
    ]),
  ]);
}

export function buildProjectUsageBar(w) {
  return h("div", { className: "card widget" }, [
    h("div", { className: "eyebrow" }, [h("span", { text: `프로젝트별 ${w.metric} 사용률` })]),
    ...w.rows.map(usageRow),
  ]);
}
```

- [ ] **Step 4: 테스트 실행 (통과 확인)**

Run: `cd src/main/resources/static/chat-widget && node --test test/project-usage-bar.test.mjs`
Expected: 3 tests passing

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/chat-widget/render/widgets/project-usage-bar.js \
        src/main/resources/static/chat-widget/test/project-usage-bar.test.mjs
git commit -m "feat: project_usage_bar 위젯 빌더"
```

---

## Task 7: status_donut 빌더

**Files:**
- Create: `src/main/resources/static/chat-widget/render/widgets/status-donut.js`
- Create: `src/main/resources/static/chat-widget/test/status-donut.test.mjs`

규칙(스펙 §7.2): **도넛 유지**(막대 금지), `stroke-linecap` round 금지. 세그먼트별 dasharray = count/total × 원둘레. level→색 클래스. 옆에 범례.

- [ ] **Step 1: 테스트 작성**

Create `test/status-donut.test.mjs`:

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { buildStatusDonut, DONUT_CIRC } from "../render/widgets/status-donut.js";

function findAll(node, cls, acc = []) {
  if (node.className && node.className.split(" ").includes(cls)) acc.push(node);
  for (const c of node.children || []) findAll(c, cls, acc);
  return acc;
}

const w = {
  type: "status_donut", label: "인스턴스", total: 140,
  segments: [
    { status: "ACTIVE", count: 128, level: "good" },
    { status: "SHUTOFF", count: 9, level: "muted" },
    { status: "ERROR", count: 3, level: "crit" },
  ],
};

test("status_donut renders one legend row per segment", () => {
  const rows = findAll(buildStatusDonut(w), "lg");
  assert.equal(rows.length, 3);
});

test("status_donut legend shows status and count", () => {
  const node = buildStatusDonut(w);
  assert.deepEqual(findAll(node, "lg-name").map((n) => n.text), ["ACTIVE", "SHUTOFF", "ERROR"]);
  assert.deepEqual(findAll(node, "lg-val").map((n) => n.text), ["128", "9", "3"]);
});

test("status_donut segment arc dasharray is count/total of circumference", () => {
  const node = buildStatusDonut(w);
  const arcs = findAll(node, "donut-seg");
  const activeDash = (128 / 140) * DONUT_CIRC;
  assert.ok(arcs[0].attrs["stroke-dasharray"].startsWith(activeDash.toFixed(2)));
});

test("status_donut never uses round linecap", () => {
  const node = buildStatusDonut(w);
  for (const arc of findAll(node, "donut-seg")) {
    assert.notEqual(arc.attrs["stroke-linecap"], "round");
  }
});

test("status_donut center shows total", () => {
  const node = buildStatusDonut(w);
  const texts = findAll(node, "donut-total");
  assert.equal(texts[0].text, "140");
});
```

- [ ] **Step 2: 테스트 실행 (실패 확인)**

Run: `cd src/main/resources/static/chat-widget && node --test test/status-donut.test.mjs`
Expected: FAIL (module not found)

- [ ] **Step 3: 구현**

Create `render/widgets/status-donut.js`:

```js
import { h } from "../dom.js";

const R = 15.5;
export const DONUT_CIRC = 2 * Math.PI * R; // ~97.39

const LEVEL_CLASS = { good: "seg-good", warn: "seg-warn", crit: "seg-crit", muted: "seg-muted" };
function levelClass(level) {
  return LEVEL_CLASS[level] || "seg-muted";
}

function arc(seg, total, offset) {
  const dash = total > 0 ? (seg.count / total) * DONUT_CIRC : 0;
  return h("circle", {
    ns: "svg",
    className: `donut-seg ${levelClass(seg.level)}`,
    attrs: {
      cx: "21", cy: "21", r: String(R), fill: "none", "stroke-width": "7",
      "stroke-dasharray": `${dash.toFixed(2)} ${(DONUT_CIRC - dash).toFixed(2)}`,
      "stroke-dashoffset": `${(-offset).toFixed(2)}`,
      transform: "rotate(-90 21 21)",
    },
  });
}

export function buildStatusDonut(w) {
  const arcs = [];
  let offset = 0;
  for (const seg of w.segments) {
    arcs.push(arc(seg, w.total, offset));
    offset += w.total > 0 ? (seg.count / w.total) * DONUT_CIRC : 0;
  }
  const svg = h("svg", { ns: "svg", className: "donut", attrs: { viewBox: "0 0 42 42", width: "112", height: "112", role: "img", "aria-label": `${w.label} 상태 분포, 총 ${w.total}` } }, [
    h("circle", { ns: "svg", attrs: { cx: "21", cy: "21", r: String(R), fill: "none", "stroke-width": "7", stroke: "var(--line)" } }),
    ...arcs,
    h("text", { ns: "svg", className: "donut-total", text: String(w.total), attrs: { x: "21", y: "20.5", "text-anchor": "middle", "font-size": "8", "font-weight": "800" } }),
    h("text", { ns: "svg", text: w.label, attrs: { x: "21", y: "26", "text-anchor": "middle", "font-size": "3.2", fill: "var(--muted)" } }),
  ]);
  const legend = h("div", { className: "legend" }, w.segments.map((seg) =>
    h("div", { className: "lg" }, [
      h("i", { className: `lg-dot ${levelClass(seg.level)}` }),
      h("span", { className: "lg-name", text: seg.status }),
      h("span", { className: "lg-val", text: String(seg.count) }),
    ])
  ));
  return h("div", { className: "card widget" }, [
    h("div", { className: "eyebrow" }, [h("span", { text: `${w.label} 상태 분포` }), h("span", { text: `총 ${w.total}` })]),
    h("div", { className: "donut-wrap" }, [svg, legend]),
  ]);
}
```

- [ ] **Step 4: 테스트 실행 (통과 확인)**

Run: `cd src/main/resources/static/chat-widget && node --test test/status-donut.test.mjs`
Expected: 5 tests passing

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/chat-widget/render/widgets/status-donut.js \
        src/main/resources/static/chat-widget/test/status-donut.test.mjs
git commit -m "feat: status_donut 위젯 빌더 (도넛 유지·round 캡 금지)"
```

---

## Task 8: threshold_banner 빌더

**Files:**
- Create: `src/main/resources/static/chat-widget/render/widgets/threshold-banner.js`
- Create: `src/main/resources/static/chat-widget/test/threshold-banner.test.mjs`

규칙: level(CRIT/WARN/GOOD)→배너 클래스. 아이콘 원 + 제목 + 부연.

- [ ] **Step 1: 테스트 작성**

Create `test/threshold-banner.test.mjs`:

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { buildThresholdBanner } from "../render/widgets/threshold-banner.js";

function find(node, cls) {
  if (node.className && node.className.split(" ").includes(cls)) return node;
  for (const c of node.children || []) { const h = find(c, cls); if (h) return h; }
  return null;
}

const w = { type: "threshold_banner", level: "CRIT", title: "임계 초과 노드 2대", detail: "CPU 85%↑ : web-prod-07, api-prod-02", count: 2 };

test("threshold_banner uses crit class for CRIT level", () => {
  assert.ok(buildThresholdBanner(w).className.split(" ").includes("crit"));
});

test("threshold_banner shows title and detail as text", () => {
  const node = buildThresholdBanner(w);
  assert.equal(find(node, "msg-t").text, "임계 초과 노드 2대");
  assert.equal(find(node, "msg-d").text, "CPU 85%↑ : web-prod-07, api-prod-02");
});

test("threshold_banner uses warn class for WARN level", () => {
  assert.ok(buildThresholdBanner({ ...w, level: "WARN" }).className.split(" ").includes("warn"));
});

test("threshold_banner omits detail node when null", () => {
  assert.equal(find(buildThresholdBanner({ ...w, detail: null }), "msg-d"), null);
});
```

- [ ] **Step 2: 테스트 실행 (실패 확인)**

Run: `cd src/main/resources/static/chat-widget && node --test test/threshold-banner.test.mjs`
Expected: FAIL (module not found)

- [ ] **Step 3: 구현**

Create `render/widgets/threshold-banner.js`:

```js
import { h } from "../dom.js";

const LEVEL_CLASS = { CRIT: "crit", WARN: "warn", GOOD: "info" };
const LEVEL_ICON = { CRIT: "!", WARN: "!", GOOD: "i" };

export function buildThresholdBanner(w) {
  const level = LEVEL_CLASS[w.level] || "info";
  const body = [h("div", { className: "msg-t", text: w.title })];
  if (w.detail) body.push(h("div", { className: "msg-d", text: w.detail }));
  return h("div", { className: `msg ${level}`, attrs: { role: "img", "aria-label": w.title } }, [
    h("div", { className: "msg-ic", text: LEVEL_ICON[w.level] || "i" }),
    h("div", {}, body),
  ]);
}
```

- [ ] **Step 4: 테스트 실행 (통과 확인)**

Run: `cd src/main/resources/static/chat-widget && node --test test/threshold-banner.test.mjs`
Expected: 4 tests passing

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/chat-widget/render/widgets/threshold-banner.js \
        src/main/resources/static/chat-widget/test/threshold-banner.test.mjs
git commit -m "feat: threshold_banner 위젯 빌더"
```

---

## Task 9: dispatch.js — 타입 라우팅 + empty 상태

**Files:**
- Create: `src/main/resources/static/chat-widget/render/dispatch.js`
- Create: `src/main/resources/static/chat-widget/test/dispatch.test.mjs`

규칙(스펙 §4.3, §8): 타입→빌더 매핑. 모르는 type은 `null`(스킵). `empty:true`는 공용 빈 상태 카드.

- [ ] **Step 1: 테스트 작성**

Create `test/dispatch.test.mjs`:

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { buildWidget } from "../render/dispatch.js";

function find(node, cls) {
  if (node.className && node.className.split(" ").includes(cls)) return node;
  for (const c of node.children || []) { const h = find(c, cls); if (h) return h; }
  return null;
}

test("buildWidget routes known types", () => {
  const node = buildWidget({ type: "inventory_count", label: "볼륨", total: 342, condition: null });
  assert.equal(find(node, "hero").text, "342");
});

test("buildWidget returns null for unknown type", () => {
  assert.equal(buildWidget({ type: "resource_dashboard", foo: 1 }), null);
});

test("buildWidget returns empty-state card when empty:true", () => {
  const node = buildWidget({ type: "metric_rank", title: "CPU 사용률이 높은 인스턴스", unit: "%", window: "5m", promql: "topk(...)", empty: true, rows: [] });
  assert.notEqual(find(node, "state"), null);
  assert.equal(find(node, "state-msg").text.length > 0, true);
});
```

- [ ] **Step 2: 테스트 실행 (실패 확인)**

Run: `cd src/main/resources/static/chat-widget && node --test test/dispatch.test.mjs`
Expected: FAIL (module not found)

- [ ] **Step 3: 구현**

Create `render/dispatch.js`:

```js
import { h } from "./dom.js";
import { buildMetricRank } from "./widgets/metric-rank.js";
import { buildInventoryCount } from "./widgets/inventory-count.js";
import { buildQuotaGauge } from "./widgets/quota-gauge.js";
import { buildProjectUsageBar } from "./widgets/project-usage-bar.js";
import { buildStatusDonut } from "./widgets/status-donut.js";
import { buildThresholdBanner } from "./widgets/threshold-banner.js";

const BUILDERS = {
  metric_rank: buildMetricRank,
  inventory_count: buildInventoryCount,
  quota_gauge: buildQuotaGauge,
  project_usage_bar: buildProjectUsageBar,
  status_donut: buildStatusDonut,
  threshold_banner: buildThresholdBanner,
};

export function buildEmptyState(widget) {
  return h("div", { className: "card widget" }, [
    h("div", { className: "state" }, [
      h("div", { className: "state-ic", text: "🔍" }),
      h("div", { className: "state-msg", text: "조건에 맞는 결과가 없어요" }),
      h("div", { className: "state-hint", text: widget && widget.title ? widget.title : "" }),
    ]),
  ]);
}

export function buildWidget(widget) {
  const builder = BUILDERS[widget.type];
  if (!builder) return null;            // 모르는 type은 스킵 (하위호환)
  if (widget.empty) return buildEmptyState(widget);
  return builder(widget);
}
```

- [ ] **Step 4: 테스트 실행 (통과 확인)**

Run: `cd src/main/resources/static/chat-widget && node --test test/dispatch.test.mjs`
Expected: 3 tests passing

- [ ] **Step 5: 전체 테스트 실행 (회귀 확인)**

Run: `cd src/main/resources/static/chat-widget && node --test`
Expected: 모든 테스트 파일 통과 (dom, format, metric-rank, inventory-count, quota-gauge, project-usage-bar, status-donut, threshold-banner, dispatch)

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/chat-widget/render/dispatch.js \
        src/main/resources/static/chat-widget/test/dispatch.test.mjs
git commit -m "feat: 위젯 디스패치 + empty 상태 (모르는 type 스킵)"
```

---

## Task 10: XSS 회귀 테스트

**Files:**
- Create: `src/main/resources/static/chat-widget/test/xss.test.mjs`

목적(스펙 §9): 악의적 문자열이 빌더 결과에서 `text`에만 담기고 `attrs`/`style`엔 절대 안 들어가는지. + 소스에 `innerHTML` 미사용 확인.

- [ ] **Step 1: 테스트 작성**

Create `test/xss.test.mjs`:

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { buildWidget } from "../render/dispatch.js";

const EVIL = '<img src=x onerror=alert(1)>"><script>alert(2)</script>';

// PlainNode 트리를 순회하며 모든 attrs 값에 EVIL 원문이 없는지, text에는 있는지 수집
function walk(node, out) {
  if (node.text != null) out.texts.push(node.text);
  if (node.attrs) out.attrVals.push(...Object.values(node.attrs).map(String));
  for (const c of node.children || []) walk(c, out);
  return out;
}

test("evil string in metric_rank lands only in text, never in attrs", () => {
  const node = buildWidget({
    type: "metric_rank", title: EVIL, unit: "%", window: "5m", promql: EVIL, empty: false,
    rows: [{ instanceName: EVIL, projectName: EVIL, value: 50, display: EVIL, severity: "CRIT" }],
  });
  const out = walk(node, { texts: [], attrVals: [] });
  assert.ok(out.texts.some((t) => t.includes(EVIL)), "원문이 text에 보존돼야 함");
  assert.ok(out.attrVals.every((v) => !v.includes("<img") && !v.includes("<script")), "attrs에 마크업이 새면 안 됨");
});

test("evil string in threshold_banner lands only in text", () => {
  const node = buildWidget({ type: "threshold_banner", level: "CRIT", title: EVIL, detail: EVIL, count: 1 });
  const out = walk(node, { texts: [], attrVals: [] });
  assert.ok(out.attrVals.every((v) => !v.includes("<img") && !v.includes("<script")));
});

test("evil string in status_donut segment status lands only in text", () => {
  const node = buildWidget({ type: "status_donut", label: EVIL, total: 1, segments: [{ status: EVIL, count: 1, level: "crit" }] });
  const out = walk(node, { texts: [], attrVals: [] });
  assert.ok(out.attrVals.every((v) => !v.includes("<img") && !v.includes("<script")));
});

test("no innerHTML anywhere in render/ source", () => {
  const here = dirname(fileURLToPath(import.meta.url));
  const renderDir = join(here, "..", "render");
  const files = [];
  const collect = (dir) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      if (e.isDirectory()) collect(join(dir, e.name));
      else if (e.name.endsWith(".js")) files.push(join(dir, e.name));
    }
  };
  collect(renderDir);
  for (const f of files) {
    assert.ok(!readFileSync(f, "utf8").includes("innerHTML"), `${f} 에 innerHTML 사용됨`);
  }
});
```

- [ ] **Step 2: 테스트 실행 (통과 확인 — 구현은 이미 안전)**

Run: `cd src/main/resources/static/chat-widget && node --test test/xss.test.mjs`
Expected: 4 tests passing (빌더가 이미 text로만 넣으므로 통과해야 정상)

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/chat-widget/test/xss.test.mjs
git commit -m "test: XSS 회귀 — 값은 text에만, render/에 innerHTML 금지"
```

---

## Task 11: CSS — 팔레트 토큰 + 위젯 스타일

**Files:**
- Modify: `src/main/resources/static/chat-widget/chat-widget.css`

기존 `:root` 변수·기존 챗 셸(런처/패널/메시지/폼) 스타일은 유지하고, **팔레트 토큰과 위젯 스타일을 추가**한다. 기존 셸 스타일은 건드리지 않는다(Surgical).

- [ ] **Step 1: 팔레트 토큰 추가**

기존 `chat-widget.css`의 `:root { ... }` 블록 안, `--danger: #b3261e;` 다음 줄에 아래를 추가:

```css
  /* --- widget palette (spec §7.1, primary #F76205 기반 검증) --- */
  --primary: #f76205;
  --primary-soft: #fff1e8;
  --w-good: #1eb85c;
  --w-good-soft: #e7f8ef;
  --w-good-ink: #0f8043;
  --w-warn: #f0b429;
  --w-warn-soft: #fdf6e3;
  --w-warn-ink: #9a7400;
  --w-crit: #f03e3e;
  --w-crit-soft: #fdecec;
  --w-crit-ink: #c62828;
  --w-info: #3182f6;
  --w-info-soft: #e8f2ff;
  --w-info-ink: #1b64da;
  --w-ink: #191f28;
  --w-ink2: #4e5968;
  --w-muted: #8b95a1;
  --w-line: #f2f4f6;
```

- [ ] **Step 2: 위젯 스타일 추가**

`chat-widget.css` **맨 끝**에 아래 블록을 추가:

```css
/* ===================== widgets ===================== */
.card.widget {
  background: #fff;
  border: none;
  border-radius: 16px;
  padding: 16px;
  box-shadow: 0 1px 3px rgba(25, 31, 40, 0.04), 0 4px 16px rgba(25, 31, 40, 0.05);
  margin-top: 8px;
}
.widget .eyebrow {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--w-muted);
  margin-bottom: 14px;
}

/* rank / gauge / usage-bar rows */
.rk { margin-bottom: 13px; }
.rk:last-child { margin-bottom: 0; }
.rk-top { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 6px; }
.rk-nm { font-size: 14px; font-weight: 700; color: var(--w-ink); }
.rk-prj { font-size: 11px; font-weight: 500; color: var(--w-muted); margin-left: 7px; }
.rk-val { font-size: 15px; font-weight: 800; color: var(--w-ink); font-variant-numeric: tabular-nums; }
.rk-track { height: 8px; border-radius: 6px; background: var(--w-line); overflow: hidden; }
.rk-track > i { display: block; height: 100%; border-radius: 6px; }
.rk-spark { display: block; width: 100%; height: 24px; margin-top: 6px; }
.rk-spark polyline { stroke: var(--w-muted); }

/* severity fills */
.sev-good { background: var(--w-good); }
.sev-warn { background: var(--w-warn); }
.sev-crit { background: var(--w-crit); }
.sev-accent { background: var(--primary); }
.sev-muted { background: #dfe2e5; }

.wfoot { margin-top: 12px; }
.prom {
  font-family: ui-monospace, "SF Mono", Menlo, monospace;
  font-size: 10px; color: var(--w-muted);
  background: var(--w-line); padding: 2px 7px; border-radius: 6px;
  overflow-wrap: anywhere;
}

/* count */
.widget.count .hero { font-size: 40px; font-weight: 800; letter-spacing: -0.02em; color: var(--w-ink); line-height: 1; }
.count-lbl { font-size: 15px; font-weight: 700; color: var(--w-ink2); margin-top: 10px; }
.count-cond { font-size: 12.5px; color: var(--w-muted); margin-top: 4px; }

/* donut */
.donut-wrap { display: flex; align-items: center; gap: 22px; }
.donut { flex: none; }
.donut-total { fill: var(--w-ink); }
.donut-seg.seg-good { stroke: var(--w-good); }
.donut-seg.seg-warn { stroke: var(--w-warn); }
.donut-seg.seg-crit { stroke: var(--w-crit); }
.donut-seg.seg-muted { stroke: #c9d0d8; }
.legend { display: flex; flex-direction: column; gap: 11px; flex: 1; }
.lg { display: flex; align-items: center; gap: 10px; font-size: 13px; }
.lg-dot { width: 10px; height: 10px; border-radius: 3px; flex: none; }
.lg-dot.seg-good { background: var(--w-good); }
.lg-dot.seg-warn { background: var(--w-warn); }
.lg-dot.seg-crit { background: var(--w-crit); }
.lg-dot.seg-muted { background: #c9d0d8; }
.lg-name { font-weight: 600; color: var(--w-ink2); }
.lg-val { font-weight: 800; color: var(--w-ink); margin-left: auto; font-variant-numeric: tabular-nums; }

/* message banners */
.msg { display: flex; gap: 13px; align-items: flex-start; border-radius: 16px; padding: 16px 17px; margin-top: 8px; }
.msg.info { background: var(--w-info-soft); }
.msg.warn { background: var(--w-warn-soft); }
.msg.crit { background: var(--w-crit-soft); }
.msg-ic { width: 26px; height: 26px; border-radius: 50%; flex: none; display: grid; place-items: center; color: #fff; font-size: 15px; font-weight: 800; }
.msg.info .msg-ic { background: var(--w-info); }
.msg.warn .msg-ic { background: var(--w-warn); }
.msg.crit .msg-ic { background: var(--w-crit); }
.msg-t { font-size: 14px; font-weight: 700; color: var(--w-ink); }
.msg-d { font-size: 12.5px; color: var(--w-ink2); margin-top: 3px; line-height: 1.5; }

/* empty / error state */
.state { text-align: center; padding: 24px 12px; }
.state-ic { width: 46px; height: 46px; border-radius: 16px; background: var(--w-line); display: grid; place-items: center; font-size: 20px; margin: 0 auto 12px; }
.state-msg { font-size: 14px; font-weight: 700; color: var(--w-ink); }
.state-hint { font-size: 12px; color: var(--w-muted); margin-top: 5px; overflow-wrap: anywhere; }

/* assistant caption (plain, no bubble) + avatar */
.message[data-role="assistant"] { align-items: flex-start; gap: 10px; }
.avatar { width: 32px; height: 32px; border-radius: 50%; flex: none; display: grid; place-items: center; color: #fff; font-weight: 800; font-size: 14px; background: var(--primary); }
.msg-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 8px; }
.cap { font-size: 14px; line-height: 1.55; color: var(--w-ink); white-space: pre-wrap; overflow-wrap: anywhere; }

/* followups */
.followups { display: flex; flex-wrap: wrap; gap: 7px; margin-top: 2px; }
.fu { font-size: 12px; font-weight: 600; color: var(--primary); border: 1px solid var(--w-line); background: #fff; border-radius: 20px; padding: 6px 12px; }
.fu:hover { background: var(--primary-soft); }
```

- [ ] **Step 3: Commit** (렌더 배선 전이므로 CSS만 커밋)

```bash
git add src/main/resources/static/chat-widget/chat-widget.css
git commit -m "style: 위젯 팔레트 토큰 + 위젯/배너/도넛/상태 스타일"
```

---

## Task 12: 메시지 렌더에 위젯 배선 (라이트 DOM)

**Files:**
- Modify: `src/main/resources/static/chat-widget/index.html:51` (script를 module로)
- Modify: `src/main/resources/static/chat-widget/chat-widget.js`

이 단계에서 위젯이 브라우저에 실제로 보인다(라이트 DOM). Shadow DOM 격리는 다음 Task. 스펙 §6 렌더 순서: 아바타 + [캡션(평문) → 위젯들 → followups], `widgets` 비면 텍스트 버블 폴백.

- [ ] **Step 1: index.html script를 module로 변경**

`index.html`의 마지막 script 태그를 수정:

```html
    <script type="module" src="./chat-widget.js"></script>
```

- [ ] **Step 2: chat-widget.js에 import 추가**

`chat-widget.js` 최상단(`(function () {` 바로 위)에 import 추가하고 IIFE는 유지:

```js
import { mount } from "./render/dom.js";
import { buildWidget } from "./render/dispatch.js";

(function () {
```

- [ ] **Step 3: 응답에서 widgets/followups 저장**

`chat-widget.js`의 `sendQuestion` 안 `replaceMessage(loadingId, { ... })` 성공 블록을 아래로 교체(필드 2개 추가):

```js
      replaceMessage(loadingId, {
        id: loadingId,
        role: "assistant",
        content: payload.answer || "응답이 비어 있습니다.",
        sources: Array.isArray(payload.sources) ? payload.sources : [],
        widgets: Array.isArray(payload.widgets) ? payload.widgets : [],
        followups: Array.isArray(payload.followups) ? payload.followups : [],
      });
```

- [ ] **Step 4: renderMessages를 위젯 대응으로 교체**

`chat-widget.js`의 `renderMessages` 함수 전체를 아래로 교체:

```js
  function renderMessages() {
    messagesEl.replaceChildren();

    state.messages.forEach((message) => {
      const row = document.createElement("div");
      row.className = "message";
      row.dataset.role = message.role;
      if (message.error) row.dataset.error = "true";

      if (message.role === "assistant") {
        const avatar = document.createElement("div");
        avatar.className = "avatar";
        avatar.setAttribute("aria-hidden", "true");
        avatar.textContent = "C";
        row.append(avatar);

        const body = document.createElement("div");
        body.className = "msg-body";

        const widgets = Array.isArray(message.widgets) ? message.widgets : [];

        if (message.loading) {
          body.append(renderTyping());
        } else if (widgets.length > 0) {
          const cap = document.createElement("div");
          cap.className = "cap";
          cap.textContent = message.content;
          body.append(cap);
          widgets.forEach((w) => {
            const node = buildWidget(w);
            if (node) body.append(mount(node));
          });
        } else {
          // 위젯 없음 → 기존 텍스트 버블 폴백
          const bubble = document.createElement("div");
          bubble.className = "bubble";
          bubble.textContent = message.content;
          if (message.sources && message.sources.length > 0) {
            bubble.append(renderSources(message.sources));
          }
          body.append(bubble);
        }

        if (!message.loading && Array.isArray(message.followups) && message.followups.length > 0) {
          body.append(renderFollowups(message.followups));
        }
        row.append(body);
      } else {
        const bubble = document.createElement("div");
        bubble.className = "bubble";
        bubble.textContent = message.content;
        row.append(bubble);
      }

      messagesEl.append(row);
    });

    messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  function renderTyping() {
    const typing = document.createElement("div");
    typing.className = "typing";
    typing.setAttribute("aria-label", "응답 생성 중");
    typing.append(document.createElement("span"));
    typing.append(document.createElement("span"));
    typing.append(document.createElement("span"));
    return typing;
  }

  function renderFollowups(followups) {
    const wrap = document.createElement("div");
    wrap.className = "followups";
    followups.forEach((text) => {
      const chip = document.createElement("button");
      chip.type = "button";
      chip.className = "fu";
      chip.textContent = text;
      chip.addEventListener("click", () => {
        if (!state.pending) sendQuestion(text);
      });
      wrap.append(chip);
    });
    return wrap;
  }
```

- [ ] **Step 5: 전체 유닛 테스트 회귀 확인**

Run: `cd src/main/resources/static/chat-widget && node --test`
Expected: 모든 테스트 통과 (렌더 배선은 순수함수 테스트에 영향 없음)

- [ ] **Step 6: 브라우저 수동 확인**

Run: `cd src/main/resources/static/chat-widget && python3 -m http.server 8791`
브라우저에서 `http://localhost:8791/` 열고 브라우저 콘솔에 아래를 붙여 목업 응답으로 렌더 확인(백엔드 없이):

```js
// 콘솔에서 fetch를 목업으로 임시 대체
window.fetch = async () => ({ ok: true, json: async () => ({
  answer: "CPU 사용률이 높은 인스턴스 상위 3개예요.",
  sources: [],
  widgets: [{ type: "metric_rank", title: "CPU 사용률이 높은 인스턴스", unit: "%", window: "5m", promql: "topk(5, ...)", empty: false,
    rows: [
      { instanceName: "web-prod-07", projectName: "service-prod", value: 91.2, display: "91.2%", severity: "CRIT" },
      { instanceName: "batch-11", projectName: "data-platform", value: 73.4, display: "73.4%", severity: "WARN" },
      { instanceName: "cache-03", projectName: "service-prod", value: 61.9, display: "61.9%", severity: "GOOD" }]}],
  followups: ["web-prod-07 메모리는?", "네트워크 송신량 TopN"],
})});
```
런처를 열고 아무 질문이나 전송 → 캡션(평문) + 랭킹 위젯 카드 + followups 칩이 보이면 성공. 칩 클릭 시 재질문 되는지 확인. (확인 후 `Ctrl+C`로 서버 종료)

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/static/chat-widget/index.html \
        src/main/resources/static/chat-widget/chat-widget.js
git commit -m "feat: 챗 메시지에 위젯·캡션·followups 렌더 배선 (라이트 DOM)"
```

---

## Task 13: Shadow DOM 격리

**Files:**
- Modify: `src/main/resources/static/chat-widget/index.html`
- Modify: `src/main/resources/static/chat-widget/chat-widget.js`

목적(스펙 §4.4): 챗 셸·위젯을 Shadow DOM에 마운트해 외부 사이트 임베드 시 CSS/DOM 충돌을 차단. `<template>` 클론으로 innerHTML 없이. 위젯 CSS는 shadow root에 `<link>`로 로드하고, 호스트 페이지(demo-surface) 스타일은 index.html 라이트 DOM에 남긴다.

- [ ] **Step 1: index.html 재구성 (호스트 div + template)**

`index.html`의 `<body>` 전체를 아래로 교체:

```html
  <body>
    <main class="demo-surface" aria-label="CONTRABASS chat widget">
      <div class="demo-mark">
        <span>CONTRABASS</span>
      </div>
    </main>

    <div id="contrabass-chat"></div>

    <template id="cc-chrome">
      <section class="chat-widget" data-open="false" aria-label="CONTRABASS assistant">
        <button class="chat-launcher" type="button" aria-label="채팅 열기" aria-expanded="false">
          <span class="launcher-glyph" aria-hidden="true"></span>
          <span class="launcher-text">Chat</span>
        </button>

        <div class="chat-panel" role="dialog" aria-label="CONTRABASS assistant">
          <header class="chat-header">
            <div>
              <p class="chat-kicker">CONTRABASS</p>
              <h1>Assistant</h1>
            </div>
            <button class="icon-button" type="button" data-chat-close aria-label="채팅 닫기">
              <span aria-hidden="true">×</span>
            </button>
          </header>

          <div class="chat-messages" data-chat-messages aria-live="polite"></div>

          <form class="chat-form" data-chat-form>
            <label class="sr-only" for="chat-input">질문</label>
            <textarea id="chat-input" data-chat-input rows="1" maxlength="1000" placeholder="질문을 입력하세요"></textarea>
            <button class="send-button" type="submit" aria-label="전송">전송</button>
          </form>
        </div>
      </section>
    </template>

    <script type="module" src="./chat-widget.js"></script>
  </body>
```

- [ ] **Step 2: index.html `<head>`에 호스트 페이지 스타일 인라인**

`index.html`의 `<head>` 안, 기존 `<link rel="stylesheet" href="./chat-widget.css">` 줄을 **삭제**하고(위젯 CSS는 이제 shadow root에서 로드) 그 자리에 아래 `<style>`을 추가:

```html
    <style>
      /* 호스트(데모) 페이지 전용 — 위젯 셸 스타일은 shadow root의 chat-widget.css가 소유 */
      * { box-sizing: border-box; }
      html, body { min-height: 100%; }
      body {
        margin: 0;
        font-family: Inter, ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif;
        background: linear-gradient(135deg, rgba(31, 123, 242, 0.06), transparent 44%), #f5f6f8;
        color: #30353a;
      }
      .demo-surface { min-height: 100vh; display: grid; place-items: center; padding: 32px; }
      .demo-mark {
        border: 1px solid rgba(48, 53, 58, 0.12); border-radius: 8px; padding: 18px 22px;
        background: rgba(255, 255, 255, 0.72); box-shadow: 0 10px 28px rgba(28, 36, 48, 0.08); color: #f36c21;
      }
      .demo-mark span { font-size: 14px; font-weight: 700; }
    </style>
```

- [ ] **Step 3: chat-widget.js를 shadow root 기준으로 전환**

`chat-widget.js`의 IIFE 시작부에서 DOM 조회 부분을 교체한다. `(function () {` 다음의 `storage` 선언은 유지하고, 그 아래 `const widget = document.querySelector(...)` ~ `const sendButton = ...` 블록(요소 조회 6줄)을 아래로 교체:

```js
  // Shadow DOM 마운트 — 외부 임베드 시 CSS/DOM 격리
  const host = document.getElementById("contrabass-chat");
  const shadow = host.attachShadow({ mode: "open" });

  const styleLink = document.createElement("link");
  styleLink.rel = "stylesheet";
  styleLink.href = "./chat-widget.css";
  shadow.append(styleLink);

  const chrome = document.getElementById("cc-chrome");
  shadow.append(chrome.content.cloneNode(true));

  const widget = shadow.querySelector(".chat-widget");
  const launcher = shadow.querySelector(".chat-launcher");
  const closeButton = shadow.querySelector("[data-chat-close]");
  const form = shadow.querySelector("[data-chat-form]");
  const input = shadow.querySelector("[data-chat-input]");
  const messagesEl = shadow.querySelector("[data-chat-messages]");
  const sendButton = shadow.querySelector(".send-button");
```

> 나머지 코드(state, 이벤트 리스너, renderMessages 등)는 이 변수들을 그대로 참조하므로 변경 불필요. `mount()`가 만드는 위젯은 `document.createElement`로 생성되지만 shadow root 안에 append되어 shadow 스타일을 정상 적용받는다.

- [ ] **Step 4: 전체 유닛 테스트 회귀 확인**

Run: `cd src/main/resources/static/chat-widget && node --test`
Expected: 모든 테스트 통과 (순수함수 불변)

- [ ] **Step 5: 브라우저 수동 확인 (Shadow 격리)**

Run: `cd src/main/resources/static/chat-widget && python3 -m http.server 8791`
`http://localhost:8791/` 에서:
1. 런처가 뜨고 클릭 시 패널 열림 (기존 동작 유지)
2. Task 12 Step 6의 콘솔 목업을 붙이고 질문 전송 → 위젯이 정상 렌더·스타일 적용됨
3. 개발자도구 Elements에서 `<div id="contrabass-chat">` 아래 `#shadow-root (open)` 안에 위젯이 들어있는지 확인
4. 콘솔에서 `document.querySelector(".chat-panel")` → `null` 이어야 함(라이트 DOM에 노출 안 됨 = 격리 성공)

(확인 후 `Ctrl+C`로 종료)

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/chat-widget/index.html \
        src/main/resources/static/chat-widget/chat-widget.js
git commit -m "feat: 챗 위젯을 Shadow DOM에 격리 마운트 (template 클론, innerHTML 무사용)"
```

---

## Task 14: 최종 검증

**Files:** 없음(검증만)

- [ ] **Step 1: 전체 유닛 테스트**

Run: `cd src/main/resources/static/chat-widget && node --test`
Expected: 전체 통과. 실패 시 해당 Task로 돌아가 수정.

- [ ] **Step 2: 6종 위젯 + 상태 브라우저 검증 (/verify 스킬 권장)**

`python3 -m http.server 8791`로 띄운 뒤, 아래 각 목업으로 렌더를 눈으로 확인:
- `metric_rank`(%): severity 색 막대, 값은 잉크색
- `metric_rank`(B/s): 액센트 색 막대(severity null)
- `inventory_count`: 히어로 숫자
- `quota_gauge`: 무제한 항목이 중립 회색
- `project_usage_bar`: 무제한 항목 중립
- `status_donut`: 도넛 형태 유지(각진 세그먼트), 범례 값 일치
- `threshold_banner`: crit/warn 배너 톤
- `empty`(metric_rank empty:true): 빈 상태 카드
- `widgets:[]`(폴백): 텍스트 버블

각 목업은 `docs/prototype/chatbot-widgets/mock-responses.json`의 시나리오를 Task 12 Step 6 방식(콘솔 fetch 대체)으로 주입해 확인.

- [ ] **Step 3: 접근성·격리 스팟 체크**
- 막대/도넛/배너에 `aria-label` 존재
- `aria-live="polite"` 메시지 영역 동작
- 라이트 DOM에서 `.chat-panel` 조회 시 `null`(격리)

- [ ] **Step 4: 최종 커밋 (필요 시 문서 상태 갱신)**

계획 문서의 완료 체크박스 반영이 필요하면 갱신 후:

```bash
git add -A
git commit -m "chore: 위젯 렌더링 파이프라인 검증 완료"
```

---

## Self-Review 결과 (작성자 체크)

- **스펙 커버리지**: §3 위젯 6종(Task 3~8) · empty/폴백(Task 9, 12) · §4 PlainNode+mount(Task 2) · §4.3 디스패치(Task 9) · §4.4 Shadow DOM(Task 13) · §5 파일구조(전 Task) · §6 데이터흐름(Task 12) · §7 팔레트/비주얼(Task 11) · §9 테스트(각 Task + Task 10 XSS) · §10 접근성(빌더 aria-label + Task 14) — 전부 대응됨.
- **spark**(§3): Task 3에 포함(있으면 렌더).
- **범위 외 명시**: 애니메이션·임베드 로더·프레임워크는 계획에 없음(스펙 비목표와 일치).
- **타입 일관성**: 빌더 className(`rk`,`rk-track`,`sev-*`,`seg-*`,`msg-*`,`state-*`,`hero`,`count-*`,`donut-seg`,`lg-*`)이 CSS(Task 11)·테스트와 일치. `buildWidget`/`buildEmptyState`/`mount`/`h`/`clampPct`/`barWidth` 시그니처가 정의 Task와 사용 Task에서 동일.
