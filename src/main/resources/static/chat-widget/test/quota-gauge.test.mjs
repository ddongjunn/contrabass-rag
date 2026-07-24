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
  // display를 " / " 기준으로 현재량/총량 분리
  assert.equal(findAll(node, "rk-cur")[0].text, "8");
  assert.equal(findAll(node, "rk-tot")[0].text, " / 무제한");
});

test("quota_gauge splits current vs total, current emphasized", () => {
  const node = buildQuotaGauge(w);
  assert.deepEqual(findAll(node, "rk-cur").map((n) => n.text), ["820", "6100"]);
  assert.deepEqual(findAll(node, "rk-tot").map((n) => n.text), [" / 1000", " / 10000 GB"]);
});
