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
