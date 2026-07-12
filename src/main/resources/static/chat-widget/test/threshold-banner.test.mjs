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
