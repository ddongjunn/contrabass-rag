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
