# 챗봇 위젯 UI/UX 리디자인(Intercom 스타일 + 다크모드) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `chat-widget.js`/`chat-widget.css`를 Intercom 스타일 톤앤매너(모션·다크모드·Material 아이콘)로
리디자인하고, 위젯 5종의 정렬·색상 불일치를 dataviz 스킬 기준으로 정리한다.

**Architecture:** 신규 순수 유틸리티 2개(`render/icons.js` — Material Symbols SVG 번들,
`render/theme.js` — 다크/라이트·패널 크기 localStorage 저장)를 먼저 만들고, `chat-widget.css`의
컬러 토큰을 다크 기본/라이트 오버라이드로 전면 개편한 뒤, `render/chrome.js`(마크업)·`chat-widget.js`
(배선)·위젯 5종 CSS를 순서대로 갱신한다. 백엔드 API 계약·Shadow DOM 마운트 구조·바닐라 ESM
아키텍처는 변경하지 않는다.

**Tech Stack:** 바닐라 ESM, `node --test`(빌드 스텝 없음, 신규 의존성 없음)

**관련 스펙:** [`docs/superpowers/specs/2026-07-21-chat-widget-intercom-redesign-design.md`](../specs/2026-07-21-chat-widget-intercom-redesign-design.md)

## Global Constraints

- 신규 외부 의존성/CDN 요청 금지 — Material 아이콘은 SVG path를 코드에 직접 번들(스펙 §4·§12).
- `innerHTML` 사용 금지 — `render/dom.js`의 `h()`/`mount()`로만 DOM 생성(기존 불변식,
  `test/xss.test.mjs`가 `render/` 전체를 스캔해서 자동 검증됨).
- 백엔드 API 계약(`/api/chat` 요청/응답 JSON) 변경 없음.
- 웹 위젯 멀티턴 히스토리(대화 목록/인박스) 추가 금지(루트 CLAUDE.md 불변식, 스펙 §2 비목표).
- Surgical — 각 Task의 diff는 해당 Task 요청에 직접 대응해야 한다. 무관한 리팩터 금지.
- 이 프로젝트는 `docs/process.md`의 Phase 사이클(착수 전 정렬 → 개발 → **Claude 검수** → **사용자
  검수** → 커밋 → 다음 Phase)을 따른다. Phase 끝의 "사용자 검수"를 건너뛰지 말 것.
- CSS 값(색상 hex 등) 자체는 `node --test`로 검증 불가 — 순수 로직(아이콘/테마/리사이즈 clamp)만
  TDD, CSS·애니메이션·리사이즈 드래그 배선은 각 Phase의 "사용자 검수"에서 브라우저로 확인한다.

---

## Phase 1 — 아이콘 + 테마·리사이즈 상태 유틸리티 (순수 함수)

**DoD:** `render/icons.js`가 Material Symbols 아이콘 9종 + 리사이즈 그립을 `PlainNode`로 반환한다.
`render/theme.js`가 테마·패널 크기를 저장/조회/clamp하는 순수 함수를 제공한다. 둘 다 아직 어디서도
호출되지 않는다(Phase 3에서 배선).

### Task 1: `render/icons.js` — Material Symbols 아이콘 번들

**Files:**
- Create: `src/main/resources/static/chat-widget/render/icons.js`
- Test: `src/main/resources/static/chat-widget/test/icons.test.mjs`

**Interfaces:**
- Produces: `icon(name: string, size?: number): PlainNode`, `resizeGripIcon(size?: number): PlainNode`,
  `ICON_NAMES: string[]` (Phase 3의 `chrome.js`/`dispatch.js`/`threshold-banner.js`·`chat-widget.js`가 소비)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/main/resources/static/chat-widget/test/icons.test.mjs` 신규 작성:

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { icon, resizeGripIcon, ICON_NAMES } from "../render/icons.js";

test("icon returns an svg PlainNode with a path child carrying real path data", () => {
  const node = icon("close");
  assert.equal(node.tag, "svg");
  assert.equal(node.ns, "svg");
  assert.equal(node.children.length, 1);
  assert.equal(node.children[0].tag, "path");
  assert.ok(node.children[0].attrs.d.length > 10, "path d 속성이 비어있으면 안 됨");
});

test("icon defaults viewBox to Material Symbols 24px grid and fill=currentColor", () => {
  const node = icon("close");
  assert.equal(node.attrs.viewBox, "0 -960 960 960");
  assert.equal(node.attrs.fill, "currentColor");
  assert.equal(node.attrs["aria-hidden"], "true");
});

test("icon respects custom size", () => {
  const node = icon("send", 32);
  assert.equal(node.attrs.width, "32");
  assert.equal(node.attrs.height, "32");
});

test("icon throws on unknown name", () => {
  assert.throws(() => icon("does-not-exist"));
});

test("ICON_NAMES exposes every bundled icon used by the redesign", () => {
  const expected = [
    "chat", "close", "light_mode", "dark_mode", "send",
    "support_agent", "search_off", "info", "warning", "error",
  ];
  for (const name of expected) {
    assert.ok(ICON_NAMES.includes(name), `${name} 아이콘이 없음`);
  }
});

test("resizeGripIcon returns an svg PlainNode with two diagonal line marks", () => {
  const node = resizeGripIcon();
  assert.equal(node.tag, "svg");
  assert.equal(node.ns, "svg");
  assert.equal(node.children.length, 2);
  for (const c of node.children) assert.equal(c.tag, "line");
});
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd src/main/resources/static/chat-widget && npm test`
Expected: FAIL — `render/icons.js` 모듈이 없어 import 오류.

- [ ] **Step 3: `render/icons.js` 작성**

`src/main/resources/static/chat-widget/render/icons.js` 신규 작성:

```js
import { h } from "./dom.js";

// Material Symbols Outlined, 24px 그리드, viewBox "0 -960 960 960"(Google 표준 좌표계).
// 출처: fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined/<name>/default/24px.svg
// (Apache License 2.0 — SVG path를 코드에 직접 번들, 외부 CDN 요청 없음. 스펙 §4·§12)
const PATHS = {
  chat: "M240-400h320v-80H240v80Zm0-120h480v-80H240v80Zm0-120h480v-80H240v80ZM80-80v-720q0-33 23.5-56.5T160-880h640q33 0 56.5 23.5T880-800v480q0 33-23.5 56.5T800-240H240L80-80Zm126-240h594v-480H160v525l46-45Zm-46 0v-480 480Z",
  close: "m256-200-56-56 224-224-224-224 56-56 224 224 224-224 56 56-224 224 224 224-56 56-224-224-224 224Z",
  light_mode: "M565-395q35-35 35-85t-35-85q-35-35-85-35t-85 35q-35 35-35 85t35 85q35 35 85 35t85-35Zm-226.5 56.5Q280-397 280-480t58.5-141.5Q397-680 480-680t141.5 58.5Q680-563 680-480t-58.5 141.5Q563-280 480-280t-141.5-58.5ZM200-440H40v-80h160v80Zm720 0H760v-80h160v80ZM440-760v-160h80v160h-80Zm0 720v-160h80v160h-80ZM256-650l-101-97 57-59 96 100-52 56Zm492 496-97-101 53-55 101 97-57 59Zm-98-550 97-101 59 57-100 96-56-52ZM154-212l101-97 55 53-97 101-59-57Zm326-268Z",
  dark_mode: "M480-120q-150 0-255-105T120-480q0-150 105-255t255-105q14 0 27.5 1t26.5 3q-41 29-65.5 75.5T444-660q0 90 63 153t153 63q55 0 101-24.5t75-65.5q2 13 3 26.5t1 27.5q0 150-105 255T480-120Zm0-80q88 0 158-48.5T740-375q-20 5-40 8t-40 3q-123 0-209.5-86.5T364-660q0-20 3-40t8-40q-78 32-126.5 102T200-480q0 116 82 198t198 82Zm-10-270Z",
  send: "M120-160v-640l760 320-760 320Zm80-120 474-200-474-200v140l240 60-240 60v140Zm0 0v-400 400Z",
  support_agent: "M440-120v-80h320v-284q0-117-81.5-198.5T480-764q-117 0-198.5 81.5T200-484v244h-40q-33 0-56.5-23.5T80-320v-80q0-21 10.5-39.5T120-469l3-53q8-68 39.5-126t79-101q47.5-43 109-67T480-840q68 0 129 24t109 66.5Q766-707 797-649t40 126l3 52q19 9 29.5 27t10.5 38v92q0 20-10.5 38T840-249v49q0 33-23.5 56.5T760-120H440ZM331.5-411.5Q320-423 320-440t11.5-28.5Q343-480 360-480t28.5 11.5Q400-457 400-440t-11.5 28.5Q377-400 360-400t-28.5-11.5Zm240 0Q560-423 560-440t11.5-28.5Q583-480 600-480t28.5 11.5Q640-457 640-440t-11.5 28.5Q617-400 600-400t-28.5-11.5ZM241-462q-7-106 64-182t177-76q89 0 156.5 56.5T720-519q-91-1-167.5-49T435-698q-16 80-67.5 142.5T241-462Z",
  search_off: "M138.5-138.5Q80-197 80-280t58.5-141.5Q197-480 280-480t141.5 58.5Q480-363 480-280t-58.5 141.5Q363-80 280-80t-141.5-58.5ZM824-120 568-376q-12-13-25.5-26.5T516-428q38-24 61-64t23-88q0-75-52.5-127.5T420-760q-75 0-127.5 52.5T240-580q0 6 .5 11.5T242-557q-18 2-39.5 8T164-535q-2-11-3-22t-1-23q0-109 75.5-184.5T420-840q109 0 184.5 75.5T680-580q0 43-13.5 81.5T629-428l251 252-56 56Zm-615-61 71-71 70 71 29-28-71-71 71-71-28-28-71 71-71-71-28 28 71 71-71 71 28 28Z",
  info: "M440-280h80v-240h-80v240Zm68.5-331.5Q520-623 520-640t-11.5-28.5Q497-680 480-680t-28.5 11.5Q440-657 440-640t11.5 28.5Q463-600 480-600t28.5-11.5ZM480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z",
  warning: "m40-120 440-760 440 760H40Zm138-80h604L480-720 178-200Zm330.5-51.5Q520-263 520-280t-11.5-28.5Q497-320 480-320t-28.5 11.5Q440-297 440-280t11.5 28.5Q463-240 480-240t28.5-11.5ZM440-360h80v-200h-80v200Zm40-100Z",
  error: "M508.5-291.5Q520-303 520-320t-11.5-28.5Q497-360 480-360t-28.5 11.5Q440-337 440-320t11.5 28.5Q463-280 480-280t28.5-11.5ZM440-440h80v-240h-80v240Zm40 360q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z",
};

export const ICON_NAMES = Object.keys(PATHS);

/**
 * Material Symbols 아이콘을 PlainNode(SVG)로 만든다. fill="currentColor"라 테마별 잉크 색을
 * 자동으로 물려받는다 — 별도 아이콘 색 토큰 불필요(스펙 §4).
 */
export function icon(name, size = 20) {
  const d = PATHS[name];
  if (!d) throw new Error(`unknown icon: ${name}`);
  return h(
    "svg",
    {
      ns: "svg",
      className: "icon",
      attrs: {
        viewBox: "0 -960 960 960",
        width: String(size),
        height: String(size),
        "aria-hidden": "true",
        fill: "currentColor",
      },
    },
    [h("path", { ns: "svg", attrs: { d } })],
  );
}

// 리사이즈 그립 — Material Symbols에 대응 아이콘이 없어 범용 UI 관례(대각선 2줄)로 대체(스펙 §4·§12).
const RESIZE_GRIP_LINES = [
  { x1: 11, y1: 5, x2: 5, y2: 11 },
  { x1: 11, y1: 9, x2: 9, y2: 11 },
];

export function resizeGripIcon(size = 16) {
  return h(
    "svg",
    {
      ns: "svg",
      className: "icon icon-resize-grip",
      attrs: {
        viewBox: "0 0 16 16",
        width: String(size),
        height: String(size),
        "aria-hidden": "true",
        fill: "none",
        stroke: "currentColor",
        "stroke-width": "1.5",
        "stroke-linecap": "round",
      },
    },
    RESIZE_GRIP_LINES.map((l) =>
      h("line", { ns: "svg", attrs: { x1: String(l.x1), y1: String(l.y1), x2: String(l.x2), y2: String(l.y2) } }),
    ),
  );
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `cd src/main/resources/static/chat-widget && npm test`
Expected: PASS (신규 6개 + 기존 전체 그린)

- [ ] **Step 5: 커밋**

```bash
git add src/main/resources/static/chat-widget/render/icons.js \
        src/main/resources/static/chat-widget/test/icons.test.mjs
git commit -m "feat(chat-widget): Material Symbols 아이콘 번들 추가 (render/icons.js)"
```

### Task 2: `render/theme.js` — 다크/라이트 + 패널 크기 저장

**Files:**
- Create: `src/main/resources/static/chat-widget/render/theme.js`
- Test: `src/main/resources/static/chat-widget/test/theme.test.mjs`

**Interfaces:**
- Produces: `getTheme(storage)`, `setTheme(storage, theme)`, `applyTheme(host, theme)`,
  `loadPanelSize(storage)`, `savePanelSize(storage, size)`, `clampPanelSize(width, height, viewport)`
  (Phase 3의 `chat-widget.js`가 소비)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/main/resources/static/chat-widget/test/theme.test.mjs` 신규 작성:

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  getTheme, setTheme, applyTheme, loadPanelSize, savePanelSize, clampPanelSize,
} from "../render/theme.js";

function fakeStorage(initial = {}) {
  const data = { ...initial };
  return {
    getItem: (k) => (k in data ? data[k] : null),
    setItem: (k, v) => { data[k] = String(v); },
    _data: data,
  };
}

test("getTheme defaults to dark when nothing stored", () => {
  assert.equal(getTheme(fakeStorage()), "dark");
});

test("getTheme returns light when explicitly stored", () => {
  assert.equal(getTheme(fakeStorage({ "contrabass.chat.theme": "light" })), "light");
});

test("getTheme falls back to dark on garbage value", () => {
  assert.equal(getTheme(fakeStorage({ "contrabass.chat.theme": "purple" })), "dark");
});

test("setTheme persists a valid value", () => {
  const storage = fakeStorage();
  setTheme(storage, "light");
  assert.equal(storage._data["contrabass.chat.theme"], "light");
});

test("setTheme normalizes invalid value to dark", () => {
  const storage = fakeStorage();
  setTheme(storage, "nonsense");
  assert.equal(storage._data["contrabass.chat.theme"], "dark");
});

test("applyTheme sets data-theme=light on the host for light", () => {
  const attrs = {};
  const host = {
    setAttribute: (k, v) => { attrs[k] = v; },
    removeAttribute: (k) => { delete attrs[k]; },
  };
  applyTheme(host, "light");
  assert.equal(attrs["data-theme"], "light");
});

test("applyTheme removes data-theme attribute for dark (default)", () => {
  const attrs = { "data-theme": "light" };
  const host = {
    setAttribute: (k, v) => { attrs[k] = v; },
    removeAttribute: (k) => { delete attrs[k]; },
  };
  applyTheme(host, "dark");
  assert.equal("data-theme" in attrs, false);
});

test("loadPanelSize returns null when nothing stored", () => {
  assert.equal(loadPanelSize(fakeStorage()), null);
});

test("loadPanelSize returns null on corrupted JSON", () => {
  assert.equal(loadPanelSize(fakeStorage({ "contrabass.chat.panelSize": "{not json" })), null);
});

test("loadPanelSize round-trips a saved size", () => {
  const storage = fakeStorage();
  savePanelSize(storage, { width: 500, height: 700 });
  assert.deepEqual(loadPanelSize(storage), { width: 500, height: 700 });
});

test("clampPanelSize enforces minimum size", () => {
  const result = clampPanelSize(100, 100, { width: 1200, height: 900 });
  assert.equal(result.width, 340);
  assert.equal(result.height, 440);
});

test("clampPanelSize caps at 90% of viewport", () => {
  const result = clampPanelSize(5000, 5000, { width: 1000, height: 800 });
  assert.equal(result.width, 900);
  assert.equal(result.height, 720);
});

test("clampPanelSize passes through an in-range size unchanged", () => {
  const result = clampPanelSize(460, 620, { width: 1200, height: 900 });
  assert.equal(result.width, 460);
  assert.equal(result.height, 620);
});
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd src/main/resources/static/chat-widget && npm test`
Expected: FAIL — `render/theme.js` 모듈이 없어 import 오류.

- [ ] **Step 3: `render/theme.js` 작성**

`src/main/resources/static/chat-widget/render/theme.js` 신규 작성:

```js
// 다크/라이트 테마 + 패널 크기를 localStorage에 저장/복원한다. userId 등 기존 저장 패턴과 동일하게
// 순수 함수로 둬서 real DOM/localStorage 없이도 테스트 가능하게 한다(스펙 §7).

const THEME_KEY = "contrabass.chat.theme";
const PANEL_SIZE_KEY = "contrabass.chat.panelSize";

// 패널 최소 크기(스펙 §5). 최대는 뷰포트의 90% — clampPanelSize가 그때그때 계산한다.
const PANEL_MIN = { width: 340, height: 440 };

export function getTheme(storage) {
  const raw = storage.getItem(THEME_KEY);
  return raw === "light" ? "light" : "dark"; // 다크 기본, 손상되거나 알 수 없는 값도 다크로 폴백
}

export function setTheme(storage, theme) {
  storage.setItem(THEME_KEY, theme === "light" ? "light" : "dark");
}

/** host: 테마 속성을 받을 엘리먼트(운영 경로에서는 Shadow host). 다크가 기본값이라 속성 자체를 뺀다. */
export function applyTheme(host, theme) {
  if (theme === "light") host.setAttribute("data-theme", "light");
  else host.removeAttribute("data-theme");
}

export function loadPanelSize(storage) {
  try {
    const parsed = JSON.parse(storage.getItem(PANEL_SIZE_KEY) || "null");
    if (!parsed || typeof parsed.width !== "number" || typeof parsed.height !== "number") return null;
    return parsed;
  } catch {
    return null;
  }
}

export function savePanelSize(storage, size) {
  storage.setItem(PANEL_SIZE_KEY, JSON.stringify(size));
}

/** 최소 340×440, 최대는 뷰포트의 90%(스펙 §5). */
export function clampPanelSize(width, height, viewport) {
  const maxWidth = viewport.width * 0.9;
  const maxHeight = viewport.height * 0.9;
  return {
    width: Math.min(Math.max(width, PANEL_MIN.width), maxWidth),
    height: Math.min(Math.max(height, PANEL_MIN.height), maxHeight),
  };
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `cd src/main/resources/static/chat-widget && npm test`
Expected: PASS (신규 12개 + 기존 전체 그린)

- [ ] **Step 5: 커밋**

```bash
git add src/main/resources/static/chat-widget/render/theme.js \
        src/main/resources/static/chat-widget/test/theme.test.mjs
git commit -m "feat(chat-widget): 다크/라이트 테마 + 패널 크기 저장 유틸리티 추가 (render/theme.js)"
```

### Phase 1 검수

**Claude 검수(자동):** Task 1·2 Step 4의 `npm test` 전체 그린이 증거. 둘 다 순수 함수라 리뷰로
DOM/브라우저 의존 없음을 확인.

**사용자 검수:** 없음 — 아직 아무 UI에도 배선되지 않아 눈에 보이는 변화가 없다. Phase 3에서 함께 확인.

Phase 2로.

---

## Phase 2 — 컬러 토큰 전면 개편 (다크 기본 + 라이트 오버라이드)

**DoD:** `chat-widget.css`의 `:host,:root` 토큰 블록이 다크를 기본값으로 갖고,
`[data-theme="light"]`가 적용되면 라이트로 전환된다. 브랜드 오렌지가 `--brand` 하나로 통합되고,
위젯 전용 `--w-ink`/`--w-ink2`/`--w-muted`/`--w-line`이 챗 셸과 공유하는 `--ink`/`--ink2`/`--muted`/
`--line`로 합쳐진다. 이 Phase는 토큰 선언만 바꾼다 — 그 값을 참조하는 규칙 자체는 Phase 4에서 정리.

### Task 3: 토큰 블록 교체

**Files:**
- Modify: `src/main/resources/static/chat-widget/chat-widget.css:1-41`

**Interfaces:**
- Produces: `--brand`, `--brand-dark`, `--brand-soft`, `--w-good/-warn/-crit`, `--w-good-soft/-warn-soft/-crit-soft`,
  `--accent-blue`, `--accent-blue-soft`, `--ink`, `--ink2`, `--muted`, `--panel`, `--page-bg`, `--line`, `--shadow`
  (Phase 4가 위젯 규칙에서, Task 5가 챗 셸 규칙에서 소비)

- [ ] **Step 1: 토큰 블록 교체**

`src/main/resources/static/chat-widget/chat-widget.css`의 1~41번째 줄(`:host,\n:root {`로
시작해서 그 블록이 끝나는 `}`까지)을 아래로 통째 교체:

```css
/* :host = shadow 호스트(임베드 시), :root = 라이트 DOM 폴백.
   custom property는 :host에 선언하면 shadow 트리로 상속된다(:root는 shadow에서 매치 안 됨).
   다크가 기본값이다 — [data-theme="light"]가 붙으면 아래 라이트 오버라이드 블록이 이긴다. */
:host,
:root {
  color-scheme: dark;
  /* --- 브랜드/인터랙션 (라이트·다크 공용 — 회사 브랜드, 테마 안 탐) --- */
  --brand: #f76205;
  --brand-dark: #d95b16;
  --brand-soft: color-mix(in srgb, var(--brand) 16%, transparent);
  /* --- 상태(severity) — dataviz 스킬 검증 status palette, 라이트·다크 공용 --- */
  --w-good: #0ca30c;
  --w-warn: #fab219;
  --w-crit: #d03b3b;
  --w-good-soft: color-mix(in srgb, var(--w-good) 16%, transparent);
  --w-warn-soft: color-mix(in srgb, var(--w-warn) 16%, transparent);
  --w-crit-soft: color-mix(in srgb, var(--w-crit) 16%, transparent);
  /* --- 중립 크기(accent) — severity 없는 수치·안내 메시지. 라이트/다크 다른 값(dataviz sequential blue) --- */
  --accent-blue: #3987e5;
  --accent-blue-soft: color-mix(in srgb, var(--accent-blue) 16%, transparent);
  /* --- 잉크/서페이스 (다크 기본) --- */
  --ink: #ffffff;
  --ink2: #c3c2b7;
  --muted: #898781;
  --panel: #1a1a19;
  --page-bg: #0d0d0d;
  --line: #2c2c2a;
  --shadow: 0 18px 48px rgba(0, 0, 0, 0.45);
  font-family:
    Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont,
    "Segoe UI", sans-serif;
}

:host([data-theme="light"]),
:root[data-theme="light"] {
  color-scheme: light;
  --accent-blue: #2a78d6;
  --ink: #0b0b0b;
  --ink2: #52514e;
  --panel: #ffffff;
  --page-bg: #f9f9f7;
  --line: #e1e0d9;
  --shadow: 0 18px 48px rgba(28, 36, 48, 0.18);
}
```

이 시점에는 아직 이 파일의 나머지 규칙들이 옛 변수명(`--brand`는 그대로지만 `--w-ink`·`--w-line`·
`--danger`·`--primary`·`--accent`·`--accent-dark`·`--accent-soft` 등)을 참조하고 있어 **일부러
깨진 상태**다 — Phase 3·4에서 그 규칙들을 새 변수로 옮기며 순서대로 고친다. 지금 브라우저로 열면
안 맞는 색이 보이는 게 정상이다(아직 커밋 전 중간 상태).

- [ ] **Step 2: 커밋**

```bash
git add src/main/resources/static/chat-widget/chat-widget.css
git commit -m "feat(chat-widget): 컬러 토큰 전면 개편 — 다크 기본 + 라이트 오버라이드, 브랜드/상태/중립 3분리"
```

### Phase 2 검수

**Claude 검수(자동):** 이 Task는 선언부만 바꿔서 자동 테스트 대상이 없다(CSS 값은 `node --test`로
검증 불가 — Global Constraints 참고). 다음 Phase가 끝나 전체가 다시 일관되면 그때 브라우저로 확인.

**사용자 검수:** 없음(중간 상태, 다음 Phase로 이어짐). Phase 3으로.

---

## Phase 3 — 챗 셸 마크업·배선 (런처 아이콘화, 테마 토글, 리사이즈)

**DoD:** 런처가 원형 아이콘 버튼으로 바뀌고 열림 상태에 따라 `chat`↔`close` 아이콘이 모핑된다.
헤더에 라이트/다크 토글 버튼이 동작한다. 패널 왼쪽 위 모서리를 드래그하면 크기가 조절되고
새로고침 후에도 유지된다. 안읽음 뱃지가 패널이 닫혀있을 때 새 답변 수를 보여주고 열면 사라진다.

### Task 4: `render/chrome.js` 마크업 갱신

**Files:**
- Modify: `src/main/resources/static/chat-widget/render/chrome.js`
- Test: `src/main/resources/static/chat-widget/test/chrome.test.mjs`

**Interfaces:**
- Consumes: `icon`, `resizeGripIcon`(Task 1)
- Produces: `data-chat-theme-toggle`, `data-chat-resize`, `data-chat-badge` 마커
  (Task 6의 `chat-widget.js`가 `querySelector`로 소비)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/main/resources/static/chat-widget/test/chrome.test.mjs`의 기존 첫 번째 테스트 다음에 추가
(파일 마지막 `});` 앞, 기존 두 테스트는 그대로 둔다):

```js
test("buildChrome includes theme toggle, resize grip and unread badge markers", () => {
  const node = buildChrome();
  assert.notEqual(findByAttr(node, "data-chat-theme-toggle"), null);
  assert.notEqual(findByAttr(node, "data-chat-resize"), null);
  assert.notEqual(findByAttr(node, "data-chat-badge"), null);
});

test("buildChrome launcher and close button carry icon svg children (no bare glyph/× text)", () => {
  const node = buildChrome();
  const launcher = find(node, "chat-launcher");
  assert.notEqual(launcher, null);
  assert.equal(find(launcher, "launcher-icon-open").children[0].tag, "svg");
  assert.equal(find(launcher, "launcher-icon-close").children[0].tag, "svg");

  const closeButton = findByAttr(node, "data-chat-close");
  assert.equal(closeButton.children[0].tag, "svg");
});
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd src/main/resources/static/chat-widget && npm test`
Expected: FAIL — `find(node, "launcher-icon-open")`가 아직 없는 마크업이라 `null`, 또는
`data-chat-theme-toggle`/`data-chat-resize`/`data-chat-badge`를 못 찾아 실패.

- [ ] **Step 3: `render/chrome.js` 전체 교체**

`src/main/resources/static/chat-widget/render/chrome.js` 전체를 아래로 교체:

```js
import { h } from "./dom.js";
import { icon, resizeGripIcon } from "./icons.js";

// index.html의 <template id="cc-chrome"> 마크업을 그대로 코드로 옮긴 것 — 클래스명/데이터
// 속성은 chat-widget.css 및 chat-widget.js의 querySelector와 1:1로 맞아야 한다.
export function buildChrome() {
  return h("section", { className: "chat-widget", attrs: { "data-open": "false", "aria-label": "CONTRABASS assistant" } }, [
    h("button", { className: "chat-launcher", attrs: { type: "button", "aria-label": "채팅 열기", "aria-expanded": "false" } }, [
      h("span", { className: "launcher-icon launcher-icon-open" }, [icon("chat", 24)]),
      h("span", { className: "launcher-icon launcher-icon-close" }, [icon("close", 24)]),
      h("span", { className: "launcher-badge", attrs: { "data-chat-badge": "", hidden: "" } }),
    ]),
    h("div", { className: "chat-panel", attrs: { role: "dialog", "aria-label": "CONTRABASS assistant" } }, [
      h("button", { className: "resize-grip", attrs: { type: "button", "data-chat-resize": "", "aria-label": "패널 크기 조절" } }, [
        resizeGripIcon(16),
      ]),
      h("header", { className: "chat-header" }, [
        h("div", {}, [
          h("p", { className: "chat-kicker", text: "CONTRABASS" }),
          h("h1", { text: "Assistant" }),
        ]),
        h("div", { className: "header-actions" }, [
          h("button", { className: "icon-button", attrs: { type: "button", "data-chat-theme-toggle": "", "aria-label": "테마 전환" } }, [
            h("span", { className: "theme-icon theme-icon-light" }, [icon("light_mode", 18)]),
            h("span", { className: "theme-icon theme-icon-dark" }, [icon("dark_mode", 18)]),
          ]),
          h("button", { className: "icon-button", attrs: { type: "button", "data-chat-close": "", "aria-label": "채팅 닫기" } }, [
            icon("close", 18),
          ]),
        ]),
      ]),
      h("div", { className: "chat-messages", attrs: { "data-chat-messages": "", "aria-live": "polite" } }),
      h("form", { className: "chat-form", attrs: { "data-chat-form": "" } }, [
        h("label", { className: "sr-only", attrs: { for: "chat-input" }, text: "질문" }),
        h("textarea", { attrs: { id: "chat-input", "data-chat-input": "", rows: "1", maxlength: "1000", placeholder: "질문을 입력하세요" } }),
        h("button", { className: "send-button", attrs: { type: "submit", "aria-label": "전송" } }, [icon("send", 18)]),
      ]),
    ]),
  ]);
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `cd src/main/resources/static/chat-widget && npm test`
Expected: PASS (기존 `data-chat-close` 관련 단언은 여전히 통과 — 버튼 자체는 유지하고 안의
텍스트만 아이콘으로 바뀜)

- [ ] **Step 5: 커밋**

```bash
git add src/main/resources/static/chat-widget/render/chrome.js \
        src/main/resources/static/chat-widget/test/chrome.test.mjs
git commit -m "feat(chat-widget): 런처/닫기/전송을 아이콘 기반으로, 테마 토글·리사이즈 그립·안읽음 뱃지 마크업 추가"
```

### Task 5: `chat-widget.css` — 챗 셸 컴포넌트 스타일 + 애니메이션

**Files:**
- Modify: `src/main/resources/static/chat-widget/chat-widget.css`

**Interfaces:**
- Consumes: Task 3의 토큰(`--brand`, `--ink`, `--ink2`, `--muted`, `--panel`, `--page-bg`, `--line`,
  `--shadow`, `--w-crit`)

- [ ] **Step 1: 런처를 원형 FAB + 아이콘 모핑으로 교체**

`chat-widget.css`에서 `.chat-launcher`부터 `.chat-launcher:hover .launcher-glyph::after`까지
(기존 `.launcher-glyph`/`::after` 커스텀 도형 규칙 전부 포함)를 아래로 통째 교체:

```css
.chat-launcher {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border: 0;
  border-radius: 50%;
  color: #ffffff;
  background: var(--brand);
  box-shadow: var(--shadow);
  transition: background-color 120ms ease, transform 120ms ease;
}

.chat-launcher:hover {
  background: var(--brand-dark);
  transform: scale(1.04);
}

.launcher-icon {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  opacity: 0;
  transform: rotate(-45deg) scale(0.6);
  transition: opacity 150ms ease, transform 150ms ease;
}

.launcher-icon-open {
  opacity: 1;
  transform: rotate(0deg) scale(1);
}

.chat-widget[data-open="true"] .launcher-icon-open {
  opacity: 0;
  transform: rotate(45deg) scale(0.6);
}

.chat-widget[data-open="true"] .launcher-icon-close {
  opacity: 1;
  transform: rotate(0deg) scale(1);
}

.launcher-badge {
  position: absolute;
  top: -2px;
  right: -2px;
  min-width: 20px;
  height: 20px;
  padding: 0 5px;
  border-radius: 10px;
  background: var(--w-crit);
  color: #ffffff;
  font-size: 11px;
  font-weight: 700;
  line-height: 20px;
  text-align: center;
}

.launcher-badge[hidden] {
  display: none;
}
```

(런처는 이제 열림 상태에서도 `display:none`으로 안 사라진다 — `chat-widget.js`가 이 클릭 핸들러를
토글로 바꾼다. `data-open="true"`일 때 런처를 숨기던 기존 `.chat-widget[data-open="true"]
.chat-launcher { display: none; }` 규칙은 Step 2에서 함께 제거한다.)

- [ ] **Step 2: 런처 숨김 규칙 제거 + 패널에 애니메이션·리사이즈 대응 추가**

`chat-widget.css`에서 아래 블록을 찾아:

```css
.chat-panel {
  width: min(388px, calc(100vw - 32px));
  height: min(640px, calc(100vh - 96px));
  min-height: 420px;
  display: none;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid rgba(48, 53, 58, 0.12);
  border-radius: 8px;
  background: var(--panel);
  box-shadow: var(--shadow);
}

.chat-widget[data-open="true"] .chat-panel {
  display: flex;
}

.chat-widget[data-open="true"] .chat-launcher {
  display: none;
}
```

아래로 교체:

```css
.chat-panel {
  position: relative;
  width: min(420px, calc(100vw - 32px));
  height: min(680px, calc(100vh - 96px));
  min-height: 440px;
  min-width: 340px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--panel);
  box-shadow: var(--shadow);
  margin-bottom: 12px;
  transform-origin: bottom right;
  opacity: 0;
  transform: scale(0.92) translateY(12px);
  visibility: hidden;
  pointer-events: none;
  transition:
    opacity 160ms ease-in,
    transform 160ms ease-in,
    visibility 0s linear 160ms;
}

.chat-widget[data-open="true"] .chat-panel {
  opacity: 1;
  transform: scale(1) translateY(0);
  visibility: visible;
  pointer-events: auto;
  transition:
    opacity 220ms cubic-bezier(0.16, 1, 0.3, 1),
    transform 220ms cubic-bezier(0.16, 1, 0.3, 1);
}

@media (prefers-reduced-motion: reduce) {
  .chat-panel {
    transition: opacity 0.01ms, visibility 0.01ms;
  }
}
```

- [ ] **Step 3: 리사이즈 그립 스타일 추가**

`.chat-panel` 규칙 바로 다음에 추가:

```css
.resize-grip {
  position: absolute;
  top: 0;
  left: 0;
  z-index: 1;
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 8px 0 8px 0;
  background: transparent;
  color: var(--muted);
  cursor: nwse-resize;
  touch-action: none;
}

.resize-grip:hover {
  color: var(--ink);
  background: var(--line);
}

@media (max-width: 520px) {
  .resize-grip {
    display: none;
  }
}
```

- [ ] **Step 4: 헤더에 테마 토글 자리 추가**

`.chat-header`부터 `.chat-header h1`까지 사이에 있는 `justify-content: space-between` 규칙은
그대로 두고, 그 아래 `.icon-button` 규칙 앞에 추가:

```css
.header-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
}

.theme-icon {
  display: none;
}

:host(:not([data-theme="light"])) .theme-icon-dark,
:root:not([data-theme="light"]) .theme-icon-dark {
  display: block;
}

:host([data-theme="light"]) .theme-icon-light,
:root[data-theme="light"] .theme-icon-light {
  display: block;
}
```

- [ ] **Step 5: 메시지 fade-in 애니메이션 추가**

`.message` 규칙을 찾아:

```css
.message {
  display: flex;
  margin: 0 0 12px;
}
```

아래로 교체:

```css
.message {
  display: flex;
  margin: 0 0 12px;
  animation: message-in 180ms ease-out;
}

@keyframes message-in {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (prefers-reduced-motion: reduce) {
  .message {
    animation: none;
  }
}
```

- [ ] **Step 6: 브라우저로 수동 확인 (Node 테스트로 커버 불가 — 애니메이션·레이아웃)**

Run: `cd src/main/resources/static/chat-widget && python3 -m http.server 8790`, 브라우저로
`http://localhost:8790/`을 열어 확인:
- 런처가 원형 아이콘 버튼으로 보이는지, 클릭하면 부드럽게 펼쳐지는지(끊기지 않고)
- 열림 상태에서 런처 아이콘이 `chat`에서 `close`로 바뀌는지(런처가 사라지지 않고 같은 자리에서 모핑)
- 패널 왼쪽 위에 리사이즈 그립이 보이는지(아직 드래그 동작은 Task 6에서 배선 — 지금은 커서 모양만 확인)
- 헤더에 테마 토글 아이콘 자리가 보이는지(클릭 동작은 Task 6)

- [ ] **Step 7: 커밋**

```bash
git add src/main/resources/static/chat-widget/chat-widget.css
git commit -m "feat(chat-widget): 런처 FAB 애니메이션, 패널 열림/닫힘 트랜지션, 리사이즈 그립·테마 토글 스타일 추가"
```

### Task 6: `chat-widget.js` 배선 — 테마 토글, 리사이즈 드래그, 안읽음 뱃지

**Files:**
- Modify: `src/main/resources/static/chat-widget/chat-widget.js`

**Interfaces:**
- Consumes: `icon`(Task 1), `getTheme`/`setTheme`/`applyTheme`/`loadPanelSize`/`savePanelSize`/
  `clampPanelSize`(Task 2), `data-chat-theme-toggle`/`data-chat-resize`/`data-chat-badge`(Task 4)

이 파일은 DOM/이벤트 배선이라 Node 테스트로 커버되지 않는다(기존 파일도 테스트 없음 — 순수 로직은
전부 Task 1·2에서 이미 테스트됨). 수동 확인은 Phase 검수에서.

- [ ] **Step 1: import 추가**

`chat-widget.js` 최상단 import에 추가:

```js
import { icon } from "./render/icons.js";
import {
  getTheme, setTheme, applyTheme, loadPanelSize, savePanelSize, clampPanelSize,
} from "./render/theme.js";
```

- [ ] **Step 2: 새 엘리먼트 참조 + 테마 초기화**

`const sendButton = shadow.querySelector(".send-button");` 다음 줄에 추가:

```js
  const themeToggle = shadow.querySelector("[data-chat-theme-toggle]");
  const resizeGrip = shadow.querySelector("[data-chat-resize]");
  const badge = shadow.querySelector("[data-chat-badge]");
  const panel = shadow.querySelector(".chat-panel");

  applyTheme(host, getTheme(localStorage));

  const savedSize = loadPanelSize(localStorage);
  if (savedSize) {
    const clamped = clampPanelSize(savedSize.width, savedSize.height, { width: window.innerWidth, height: window.innerHeight });
    panel.style.width = `${clamped.width}px`;
    panel.style.height = `${clamped.height}px`;
  }
```

- [ ] **Step 3: 안읽음 상태 추가**

`const state = { ... };` 블록에 `pending: false,` 다음 줄로 추가:

```js
    unread: 0,
```

- [ ] **Step 4: 테마 토글 클릭 핸들러**

`closeButton.addEventListener("click", () => setOpen(false));` 다음 줄에 추가:

```js
  themeToggle.addEventListener("click", () => {
    const next = getTheme(localStorage) === "light" ? "dark" : "light";
    setTheme(localStorage, next);
    applyTheme(host, next);
  });
```

- [ ] **Step 5: 리사이즈 드래그 배선**

같은 위치(테마 토글 핸들러 다음)에 추가:

```js
  resizeGrip.addEventListener("pointerdown", (event) => {
    event.preventDefault();
    const startX = event.clientX;
    const startY = event.clientY;
    const startRect = panel.getBoundingClientRect();

    function onMove(moveEvent) {
      // 그립이 왼쪽 위 모서리라 왼쪽/위로 끌수록 커져야 한다 — X/Y 델타 부호를 뒤집는다.
      const nextWidth = startRect.width - (moveEvent.clientX - startX);
      const nextHeight = startRect.height - (moveEvent.clientY - startY);
      const clamped = clampPanelSize(nextWidth, nextHeight, { width: window.innerWidth, height: window.innerHeight });
      panel.style.width = `${clamped.width}px`;
      panel.style.height = `${clamped.height}px`;
    }

    function onUp() {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      const rect = panel.getBoundingClientRect();
      savePanelSize(localStorage, { width: rect.width, height: rect.height });
    }

    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  });
```

- [ ] **Step 6: 안읽음 뱃지 갱신 함수 추가 + `setOpen`에서 리셋**

`function setOpen(open) { ... }` 함수 전체를 아래로 교체:

```js
  function setOpen(open) {
    widget.dataset.open = String(open);
    launcher.setAttribute("aria-expanded", String(open));
    if (open) {
      state.unread = 0;
      updateBadge();
      window.setTimeout(() => input.focus(), 0);
    }
  }

  function updateBadge() {
    if (state.unread <= 0) {
      badge.setAttribute("hidden", "");
      return;
    }
    badge.removeAttribute("hidden");
    badge.textContent = state.unread > 9 ? "9+" : String(state.unread);
  }

  function notifyUnreadIfClosed() {
    if (widget.dataset.open === "true") return;
    state.unread += 1;
    updateBadge();
  }
```

- [ ] **Step 7: 답변이 실제로 도착했을 때 안읽음 카운트 올리기**

`sendQuestion` 함수의 `try` 블록에서 `replaceMessage(loadingId, { ... });` 바로 다음 줄에 추가
(성공 경로만 — 로딩 placeholder·에러 메시지는 카운트하지 않는다는 기존 정책 유지):

```js
      notifyUnreadIfClosed();
```

정확히는 아래처럼 그 블록 전체가 되어야 한다:

```js
      replaceMessage(loadingId, {
        id: loadingId,
        role: "assistant",
        content: payload.answer || "응답이 비어 있습니다.",
        sources: Array.isArray(payload.sources) ? payload.sources : [],
        widgets: Array.isArray(payload.widgets) ? payload.widgets : [],
        followups: Array.isArray(payload.followups) ? payload.followups : [],
      });
      notifyUnreadIfClosed();
```

- [ ] **Step 8: 초기 인사말도 배지 대상에 포함**

`renderMessages();` 호출(파일 55번째 줄 부근, `if (state.messages.length === 0) { ... }` 블록
바로 다음) 다음 줄에 추가:

```js
  notifyUnreadIfClosed();
```

- [ ] **Step 9: 아바타를 글자 대신 아이콘으로 교체**

`renderMessages()` 안에서:

```js
        const avatar = document.createElement("div");
        avatar.className = "avatar";
        avatar.setAttribute("aria-hidden", "true");
        avatar.textContent = "C";
        row.append(avatar);
```

를 아래로 교체:

```js
        const avatar = document.createElement("div");
        avatar.className = "avatar";
        avatar.setAttribute("aria-hidden", "true");
        avatar.append(mount(icon("support_agent", 18)));
        row.append(avatar);
```

- [ ] **Step 10: 브라우저로 수동 확인**

Run: `cd src/main/resources/static/chat-widget && python3 -m http.server 8790`, 브라우저로
`http://localhost:8790/` 확인:
- 헤더의 테마 토글 클릭 → 즉시 라이트/다크 전환되는지, 새로고침해도 유지되는지
- 패널 왼쪽 위 그립을 마우스로 드래그 → 패널이 커지는지, 최소/최대 범위를 벗어나지 않는지,
  새로고침 후에도 조절한 크기가 유지되는지
- 패널을 닫은 상태에서 백엔드가 응답하면(또는 첫 인사말) 런처에 빨간 배지가 뜨는지, 열면 사라지는지
- 어시스턴트 메시지 아바타가 로봇 아이콘으로 보이는지

- [ ] **Step 11: 커밋**

```bash
git add src/main/resources/static/chat-widget/chat-widget.js
git commit -m "feat(chat-widget): 테마 토글·패널 리사이즈 드래그·안읽음 뱃지·아이콘 아바타 배선"
```

### Phase 3 검수

**Claude 검수(자동):** Task 4의 `npm test` 통과가 마크업 구조의 증거. Task 5·6은 CSS/DOM 배선이라
자동 테스트 대상 밖 — 코드 리뷰로 토큰 참조 오타·이벤트 리스너 중복 등록 여부 확인.

**사용자 검수(브라우저 필요):**
- [ ] 런처 클릭 → 패널이 부드럽게(뚝뚝 끊기지 않고) 열리고, 아이콘이 `chat`→`close`로 모핑되는지.
- [ ] 헤더 테마 토글로 라이트/다크 전환, 새로고침 후에도 선택이 유지되는지.
- [ ] 패널 왼쪽 위 그립을 드래그해 리사이즈, 새로고침 후에도 크기가 유지되는지, 최소 크기 밑으로도
  화면 90% 위로도 안 커지는지.
- [ ] 패널 닫고 질문 보내서 답 오면 런처에 안읽음 배지가 뜨고, 패널 열면 사라지는지.
- [ ] `prefers-reduced-motion` 켠 상태(OS 설정)에서 애니메이션이 즉시 전환으로 바뀌는지.

다섯 항목 확인되면 Phase 4로.

---

## Phase 4 — 위젯 5종 정렬·색상·아이콘 정리

**DoD:** `metric_rank`/`project_usage_bar`의 이름·값 칸이 모든 행에서 같은 지점에 정렬된다. 막대
끝은 기준선 쪽이 각지고 값 쪽만 둥글다. 모든 위젯이 Phase 2에서 만든 통합 토큰(`--ink`/`--ink2`/
`--muted`/`--line`/`--w-good`/`--w-warn`/`--w-crit`/`--accent-blue`)만 참조하고 옛 `--w-ink`·
`--w-line` 등 위젯 전용 중복 토큰은 완전히 사라진다. 빈 상태 아이콘과 `threshold_banner` 배너
아이콘이 Material 아이콘으로 바뀐다.

### Task 7: `chat-widget.css` 위젯 섹션 전면 교체

**Files:**
- Modify: `src/main/resources/static/chat-widget/chat-widget.css`

**Interfaces:**
- Consumes: Task 3의 토큰(`--ink`, `--ink2`, `--muted`, `--line`, `--w-good/-warn/-crit`,
  `--w-good-soft/-warn-soft/-crit-soft`, `--accent-blue`, `--accent-blue-soft`)

- [ ] **Step 1: `/* ===================== widgets ===================== */` 이후 파일 끝까지 전체 교체**

`chat-widget.css`에서 `/* ===================== widgets ===================== */` 줄부터
파일 마지막 줄(`.fu:hover { ... }`)까지를 통째로 아래로 교체:

```css
/* ===================== widgets ===================== */
.card.widget {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 16px;
  padding: 16px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12), 0 4px 16px rgba(0, 0, 0, 0.14);
  margin-top: 8px;
}
.widget .eyebrow {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--muted);
  margin-bottom: 14px;
}

/* rank / gauge / usage-bar rows — 이름 · 막대 · 값 한 줄 레이아웃.
   이름·값 칸을 고정폭으로 둬서 행마다 텍스트 길이가 달라도 항상 같은 지점에서 정렬된다. */
.rk { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 11px; }
.rk:last-child { margin-bottom: 0; }
.rk-name { flex: 0 0 100px; width: 100px; display: flex; flex-direction: column; overflow: hidden; }
.rk-nm { font-size: 13px; font-weight: 600; color: var(--ink); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rk-prj { font-size: 10.5px; font-weight: 400; color: var(--muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rk-track { flex: 1 1 auto; height: 6px; border-radius: 6px; background: var(--line); overflow: hidden; }
/* 막대 마크(dataviz 스펙): 기준선(왼쪽)은 각지고, 값 끝(오른쪽)만 둥글게. */
.rk-track > i { display: block; height: 100%; border-radius: 0 4px 4px 0; }
.rk-val { flex: 0 0 64px; width: 64px; text-align: right; font-size: 13.5px; font-weight: 600; color: var(--ink); font-variant-numeric: tabular-nums; }
/* 쿼터류 값: 현재량은 진하게, 총량(/ 뒤)은 흐리게 — 크기/굵기는 그대로, 색 대비만 */
.rk-cur { color: var(--ink); }
.rk-tot { color: var(--muted); font-weight: 500; }
.rk-spark { flex: 0 0 100%; display: block; width: 100%; height: 22px; margin-top: 2px; }
.rk-spark polyline { stroke: var(--muted); }

/* severity fills */
.sev-good { background: var(--w-good); }
.sev-warn { background: var(--w-warn); }
.sev-crit { background: var(--w-crit); }
.sev-accent { background: var(--accent-blue); }
.sev-muted { background: var(--line); }

.wfoot { margin-top: 12px; }
.prom {
  font-family: ui-monospace, "SF Mono", Menlo, monospace;
  font-size: 10px; color: var(--muted);
  background: var(--line); padding: 2px 7px; border-radius: 6px;
  overflow-wrap: anywhere;
}

/* count */
.widget.count .hero { font-size: 34px; font-weight: 700; letter-spacing: -0.02em; color: var(--ink); line-height: 1; }
.count-lbl { font-size: 14px; font-weight: 600; color: var(--ink2); margin-top: 8px; }
.count-cond { font-size: 12px; color: var(--muted); margin-top: 4px; }

/* donut */
.donut-wrap { display: flex; align-items: center; gap: 22px; }
.donut { flex: none; }
.donut-total { fill: var(--ink); }
.donut-seg.seg-good { stroke: var(--w-good); }
.donut-seg.seg-warn { stroke: var(--w-warn); }
.donut-seg.seg-crit { stroke: var(--w-crit); }
.donut-seg.seg-muted { stroke: var(--line); }
.legend { display: flex; flex-direction: column; gap: 11px; flex: 1; }
.lg { display: flex; align-items: center; gap: 10px; font-size: 13px; }
.lg-dot { width: 10px; height: 10px; border-radius: 3px; flex: none; }
.lg-dot.seg-good { background: var(--w-good); }
.lg-dot.seg-warn { background: var(--w-warn); }
.lg-dot.seg-crit { background: var(--w-crit); }
.lg-dot.seg-muted { background: var(--line); }
.lg-name { font-weight: 600; color: var(--ink2); }
.lg-val { font-weight: 800; color: var(--ink); margin-left: auto; font-variant-numeric: tabular-nums; }

/* message banners */
.msg { display: flex; gap: 13px; align-items: flex-start; border-radius: 16px; padding: 16px 17px; margin-top: 8px; }
.msg.info { background: var(--accent-blue-soft); }
.msg.warn { background: var(--w-warn-soft); }
.msg.crit { background: var(--w-crit-soft); }
.msg-ic { width: 26px; height: 26px; border-radius: 50%; flex: none; display: grid; place-items: center; color: #fff; }
.msg-ic svg { width: 15px; height: 15px; }
.msg.info .msg-ic { background: var(--accent-blue); }
.msg.warn .msg-ic { background: var(--w-warn); }
.msg.crit .msg-ic { background: var(--w-crit); }
.msg-t { font-size: 14px; font-weight: 700; color: var(--ink); }
.msg-d { font-size: 12.5px; color: var(--ink2); margin-top: 3px; line-height: 1.5; }

/* empty / error state */
.state { text-align: center; padding: 24px 12px; }
.state-ic { width: 46px; height: 46px; border-radius: 16px; background: var(--line); display: grid; place-items: center; color: var(--muted); margin: 0 auto 12px; }
.state-msg { font-size: 14px; font-weight: 700; color: var(--ink); }
.state-hint { font-size: 12px; color: var(--muted); margin-top: 5px; overflow-wrap: anywhere; }

/* assistant caption (plain, no bubble) + avatar */
.message[data-role="assistant"] { align-items: flex-start; gap: 10px; }
.avatar { width: 32px; height: 32px; border-radius: 50%; flex: none; display: grid; place-items: center; color: #fff; background: var(--brand); }
.avatar svg { width: 18px; height: 18px; }
.msg-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 8px; }
.cap { font-size: 14px; line-height: 1.55; color: var(--ink); white-space: pre-wrap; overflow-wrap: anywhere; }

/* followups — 브랜드색에서 파생된 옅은 배경(카드와 구분) */
.followups { display: flex; flex-wrap: wrap; gap: 7px; margin-top: 2px; }
.fu { font-size: 12px; font-weight: 600; color: var(--brand); border: 1px solid color-mix(in srgb, var(--brand) 24%, transparent); background: var(--brand-soft); border-radius: 20px; padding: 6px 12px; }
.fu:hover { background: color-mix(in srgb, var(--brand) 24%, transparent); border-color: color-mix(in srgb, var(--brand) 36%, transparent); }
```

- [ ] **Step 2: 파일 전체에서 옛 토큰 잔존 여부 확인**

Run: `cd src/main/resources/static/chat-widget && grep -nE -- "--w-ink|--w-ink2|--w-muted|--w-line|--w-info|--danger|--primary\b|--accent\b|--accent-dark|--accent-soft" chat-widget.css`
Expected: 결과 없음(전부 `--ink`/`--ink2`/`--muted`/`--line`/`--accent-blue`/`--w-crit`로 대체됨).
남아있으면 그 줄을 찾아 새 토큰으로 고친다.

- [ ] **Step 3: 브라우저로 수동 확인**

Run: `cd src/main/resources/static/chat-widget && python3 -m http.server 8790`, 브라우저로
`http://localhost:8790/preview.html`을 열어(백엔드 없이 5종 위젯 목업을 볼 수 있는 페이지) 확인:
- `metric_rank`/`project_usage_bar`의 이름·값 칸이 행마다 같은 위치에서 정렬되는지
- 막대 끝이 오른쪽만 둥근지(왼쪽은 각짐)
- 다크/라이트 토글 전환 시 위젯 카드 배경·텍스트가 챗 셸과 같은 톤으로 자연스럽게 바뀌는지
  (챗 셸과 위젯이 서로 다른 색으로 따로 노는 느낌이 없는지)

- [ ] **Step 4: 커밋**

```bash
git add src/main/resources/static/chat-widget/chat-widget.css
git commit -m "fix(chat-widget): 위젯 정렬 고정폭 칸 + 막대 radius 수정, 위젯 전용 토큰을 챗 셸과 통합"
```

### Task 8: 빈 상태 + `threshold_banner` 아이콘 교체

**Files:**
- Modify: `src/main/resources/static/chat-widget/render/dispatch.js`
- Modify: `src/main/resources/static/chat-widget/render/widgets/threshold-banner.js`
- Test: `src/main/resources/static/chat-widget/test/dispatch.test.mjs`
- Test: `src/main/resources/static/chat-widget/test/threshold-banner.test.mjs`

**Interfaces:**
- Consumes: `icon`(Task 1)

- [ ] **Step 1: 실패하는 테스트 작성 — 빈 상태**

`src/main/resources/static/chat-widget/test/dispatch.test.mjs` 최상단의

```js
import { buildWidget } from "../render/dispatch.js";
```

를 아래로 교체:

```js
import { buildWidget, buildEmptyState } from "../render/dispatch.js";
```

파일 마지막(기존 테스트들 다음)에 아래 테스트를 추가:

```js
test("buildEmptyState renders an icon svg instead of an emoji glyph", () => {
  const node = buildEmptyState({ title: "결과 없음" });
  const stateNode = node.children[0];
  assert.equal(stateNode.className, "state");
  const iconWrap = stateNode.children[0];
  assert.equal(iconWrap.className, "state-ic");
  assert.equal(iconWrap.text, undefined, "이모지 텍스트가 남아있으면 안 됨");
  assert.equal(iconWrap.children[0].tag, "svg");
});
```

- [ ] **Step 2: 실패하는 테스트 작성 — threshold_banner**

`src/main/resources/static/chat-widget/test/threshold-banner.test.mjs`에 아래 테스트를 추가
(기존 테스트 아래):

```js
test("buildThresholdBanner renders a status icon svg instead of a literal ! / i character", () => {
  const node = buildThresholdBanner({ level: "CRIT", title: "t", detail: null, count: 1 });
  const iconEl = node.children[0];
  assert.equal(iconEl.className, "msg-ic");
  assert.equal(iconEl.text, undefined, "\"!\" 문자가 남아있으면 안 됨");
  assert.equal(iconEl.children[0].tag, "svg");
});
```

- [ ] **Step 3: 테스트 실행 → 실패 확인**

Run: `cd src/main/resources/static/chat-widget && npm test`
Expected: FAIL — 둘 다 아직 텍스트 글리프라 `iconEl.text`가 정의돼 있고 `children[0]`이 없음.

- [ ] **Step 4: `render/dispatch.js`의 `buildEmptyState` 수정**

```js
export function buildEmptyState(widget) {
  return h("div", { className: "card widget" }, [
    h("div", { className: "state" }, [
      h("div", { className: "state-ic" }, [icon("search_off", 22)]),
      h("div", { className: "state-msg", text: "조건에 맞는 결과가 없어요" }),
      // title은 metric_rank에만 있다. status_donut/project_usage_bar는 label/metric을 쓴다.
      h("div", { className: "state-hint", text: emptyHint(widget) }),
    ]),
  ]);
}
```

파일 최상단 import에 추가: `import { icon } from "./icons.js";`

- [ ] **Step 5: `render/widgets/threshold-banner.js` 수정**

전체 파일을 아래로 교체:

```js
import { h } from "../dom.js";
import { icon } from "../icons.js";

const LEVEL_CLASS = { CRIT: "crit", WARN: "warn", GOOD: "info" };
const LEVEL_ICON = { CRIT: "error", WARN: "warning", GOOD: "info" };

export function buildThresholdBanner(w) {
  const level = LEVEL_CLASS[w.level] || "info";
  const body = [h("div", { className: "msg-t", text: w.title })];
  if (w.detail) body.push(h("div", { className: "msg-d", text: w.detail }));
  return h("div", { className: `msg ${level}` }, [
    h("div", { className: "msg-ic" }, [icon(LEVEL_ICON[w.level] || "info", 15)]),
    h("div", {}, body),
  ]);
}
```

- [ ] **Step 6: 테스트 실행 → 통과 확인**

Run: `cd src/main/resources/static/chat-widget && npm test`
Expected: PASS (전체 그린)

- [ ] **Step 7: 커밋**

```bash
git add src/main/resources/static/chat-widget/render/dispatch.js \
        src/main/resources/static/chat-widget/render/widgets/threshold-banner.js \
        src/main/resources/static/chat-widget/test/dispatch.test.mjs \
        src/main/resources/static/chat-widget/test/threshold-banner.test.mjs
git commit -m "fix(chat-widget): 빈 상태/threshold_banner 이모지·문자 글리프를 Material 아이콘으로 교체"
```

### Phase 4 검수

**Claude 검수(자동):** Task 8 Step 6의 `npm test` 전체 그린이 증거. Task 7(순수 CSS)은 Step 2의
`grep`으로 옛 토큰 잔존 여부를 정적으로 확인.

**사용자 검수(브라우저 필요):**
- [ ] `preview.html`에서 5종 위젯 전부(빈 상태 포함) 다크/라이트 양쪽에서 이모지·문자 글리프 없이
  아이콘으로 보이는지.
- [ ] 실제 백엔드(`./gradlew bootRun`)와 연결해 "CPU사용량 top5", "인스턴스 상태 분포", "임계 넘은
  노드 있어?", "프로젝트별 사용률" 질문으로 각 위젯이 실 데이터로 정렬 안 어긋나고 뜨는지.
- [ ] 포털(`remote-contrabass-admin`)에 임베드된 상태로도 다크모드 기본·리사이즈·테마 토글이 똑같이
  동작하는지(Shadow DOM 격리 확인).

세 항목 확인되면 전체 리디자인이 끝난다.

---

## 최종 참고

- Material Symbols SVG path는 `fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined/`
  에서 직접 받은 실제 데이터다(Apache License 2.0) — 임의로 만든 값이 아니다.
- `color-mix()` CSS 함수를 쓴다(Chrome 111+/Safari 16.4+) — 이 프로젝트가 지원하는 브라우저
  범위가 그보다 낮다면 Task 3·7에서 `color-mix()` 대신 고정 hex soft 변형으로 되돌려야 한다(열린
  리스크, 스펙 §11).
- `status_donut`의 세그먼트 간격(`DONUT_GAP = 1.4`, `render/widgets/status-donut.js`)은 계획 작성
  중 재확인한 결과 이미 dataviz 2px 기준을 충족해서(약 3.6px 상당) 이번 계획에 별도 Task로 넣지
  않았다 — 스펙 §6의 해당 항목은 "이미 충족됨"으로 정정한다.
- 스펙 §8은 정렬 수정 대상으로 위젯 렌더러 5개(`metric-rank.js`/`project-usage-bar.js`/
  `status-donut.js`/`threshold-banner.js`/`inventory-count.js`) 전부를 "수정"으로 적었지만,
  계획 작성 중 실제 코드를 보니 `metric-rank.js`/`project-usage-bar.js`는 이미 `.rk-name`/`.rk-val`
  클래스명을 쓰고 있어 Task 7의 CSS 폭 고정만으로 충분했다(JS 변경 불필요). `status-donut.js`는
  위 항목대로 이미 충족, `inventory-count.js`는애초에 정렬 문제가 없는 단일 stat tile이라 손댈 게
  없었다. 실제로 JS를 고친 건 `threshold-banner.js`(아이콘 교체, Task 8)뿐이다.
