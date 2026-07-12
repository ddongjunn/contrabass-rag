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
