import { test } from "node:test";
import assert from "node:assert/strict";
import { buildWidget, buildEmptyState } from "../render/dispatch.js";

function find(node, cls) {
  if (node.className && node.className.split(" ").includes(cls)) return node;
  for (const c of node.children || []) { const h = find(c, cls); if (h) return h; }
  return null;
}

test("buildWidget routes known types", () => {
  const node = buildWidget({ type: "inventory_count", label: "볼륨", total: 342, condition: null });
  assert.equal(find(node, "hero").text, "342");
});

test("buildWidget routes metric_line to line chart", () => {
  const node = buildWidget({
    type: "metric_line", title: "CPU 사용률 추이", unit: "%", range: "1h", promql: "expr", empty: false,
    series: [{ name: "web-01", projectName: null, points: [{ ts: 1, value: 1 }, { ts: 2, value: 2 }] }],
  });
  assert.notEqual(find(node, "lc-plot"), null);
});

test("buildWidget returns null for unknown type", () => {
  assert.equal(buildWidget({ type: "resource_dashboard", foo: 1 }), null);
});

test("buildWidget returns empty-state card when empty:true", () => {
  const node = buildWidget({ type: "metric_rank", title: "CPU 사용률이 높은 인스턴스", unit: "%", window: "5m", promql: "topk(...)", empty: true, rows: [] });
  assert.notEqual(find(node, "state"), null);
  assert.equal(find(node, "state-msg").text.length > 0, true);
});

test("buildEmptyState renders an icon svg instead of an emoji glyph", () => {
  const node = buildEmptyState({ title: "결과 없음" });
  const stateNode = node.children[0];
  assert.equal(stateNode.className, "state");
  const iconWrap = stateNode.children[0];
  assert.equal(iconWrap.className, "state-ic");
  assert.equal(iconWrap.text, undefined, "이모지 텍스트가 남아있으면 안 됨");
  assert.equal(iconWrap.children[0].tag, "svg");
});
