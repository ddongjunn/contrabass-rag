import { test } from "node:test";
import assert from "node:assert/strict";
import { buildUsageBar } from "../render/widgets/usage-bar.js";

function findAll(node, cls, acc = []) {
  if (node.className && node.className.split(" ").includes(cls)) acc.push(node);
  for (const c of node.children || []) findAll(c, cls, acc);
  return acc;
}

const w = {
  type: "usage_bar", title: "네트워크별 IP 사용률", unit: "%",
  rows: [
    { name: "10.255.43.0/24", value: 17.3, display: "17.3%", severity: "GOOD" },
    { name: "Ceph 클러스터", value: 92.0, display: "32.0 TB / 34.8 TB (92.0%)", severity: "CRIT" },
  ],
};

test("usage_bar renders one row per item with title", () => {
  const node = buildUsageBar(w);
  assert.equal(findAll(node, "rk").length, 2);
  assert.equal(findAll(node, "eyebrow")[0].children[0].text, "네트워크별 IP 사용률");
});

test("usage_bar width from value, severity color", () => {
  const fills = findAll(buildUsageBar(w), "rk-track").map((t) => t.children[0]);
  assert.equal(fills[0].attrs.style, "width:17%");
  assert.equal(fills[0].className, "sev-good");
  assert.equal(fills[1].className, "sev-crit");
});

test("usage_bar display 문자열을 그대로 표시(용량 절대값 포함 가능)", () => {
  const vals = findAll(buildUsageBar(w), "rk-val").map((n) => n.text);
  assert.deepEqual(vals, ["17.3%", "32.0 TB / 34.8 TB (92.0%)"]);
});
