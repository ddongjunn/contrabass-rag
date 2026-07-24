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
  // title은 허용된 텍스트 싱크다 — mount()가 setAttribute로 넣고 브라우저는 마크업 해석 없이
  // 툴팁 평문으로만 쓴다(말줄임 이름 호버 표시용). 그 외 속성엔 사용자 문자열 금지 유지.
  if (node.attrs) out.attrVals.push(...Object.entries(node.attrs).filter(([k]) => k !== "title").map(([, v]) => String(v)));
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

test("evil string in metric_line lands only in text, never in attrs", () => {
  const node = buildWidget({
    type: "metric_line", title: EVIL, unit: "%", range: "1h", promql: EVIL, empty: false,
    series: [
      { name: EVIL, projectName: EVIL, points: [{ ts: 1000, value: 10 }, { ts: 1060, value: 20 }] },
      { name: "web-02", projectName: null, points: [{ ts: 1000, value: 5 }, { ts: 1060, value: 15 }] },
    ],
  });
  const out = walk(node, { texts: [], attrVals: [] });
  assert.ok(out.texts.some((t) => t.includes(EVIL)), "원문이 text에 보존돼야 함");
  assert.ok(out.attrVals.every((v) => !v.includes("<img") && !v.includes("<script")), "attrs에 마크업이 새면 안 됨");
});

test("evil string in usage_bar lands only in text", () => {
  const node = buildWidget({
    type: "usage_bar", title: EVIL, unit: "%", empty: false,
    rows: [{ name: EVIL, value: 17.3, display: EVIL, severity: "GOOD" }],
  });
  const out = walk(node, { texts: [], attrVals: [] });
  assert.ok(out.texts.some((t) => t.includes(EVIL)));
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
    // 실제 사용은 프로퍼티 접근(.innerHTML). 주석 속 단어는 오탐이므로 점 접근만 검사.
    assert.ok(!/\.innerHTML/.test(readFileSync(f, "utf8")), `${f} 에 innerHTML 사용됨`);
  }
});
