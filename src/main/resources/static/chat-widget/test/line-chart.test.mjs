import { test } from "node:test";
import assert from "node:assert/strict";
import { buildLineChart } from "../render/widgets/line-chart.js";

// PlainNode 트리에서 className으로 노드 찾기 (테스트 헬퍼)
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
  type: "metric_line", title: "CPU 사용률 추이", unit: "%", range: "1h",
  promql: "(sum by(domain)(rate(...)))", empty: false,
  series: [
    { name: "web-01", projectName: "prod", points: [{ ts: 1000, value: 10 }, { ts: 1060, value: 20 }] },
    { name: "web-02", projectName: "prod", points: [{ ts: 1000, value: 5 }, { ts: 1060, value: 15 }] },
  ],
};

test("metric_line renders one polyline per series with fixed slot classes", () => {
  const node = buildLineChart(cpu);
  const lines = findAll(node, "lc-ln");
  assert.equal(lines.length, 2);
  assert.ok(lines[0].className.includes("ln-1"));
  assert.ok(lines[1].className.includes("ln-2"));
  // 각 폴리라인은 포인트 수만큼 좌표쌍을 갖는다
  assert.equal(lines[0].attrs.points.trim().split(/\s+/).length, 2);
});

test("metric_line legend: name + last value per series (≥2 series)", () => {
  const node = buildLineChart(cpu);
  const names = findAll(node, "lg-name").map((n) => n.text);
  assert.deepEqual(names, ["web-01", "web-02"]);
  const vals = findAll(node, "lg-val").map((n) => n.text);
  assert.deepEqual(vals, ["20.0%", "15.0%"]);
});

test("metric_line single series: no legend box (제목이 이미 말한다)", () => {
  const node = buildLineChart({ ...cpu, series: [cpu.series[0]] });
  assert.equal(find(node, "legend"), null);
});

test("metric_line header shows title and range", () => {
  const node = buildLineChart(cpu);
  const texts = [];
  (function walk(n) { if (n.text != null) texts.push(n.text); (n.children || []).forEach(walk); })(node);
  assert.ok(texts.includes("CPU 사용률 추이"));
  assert.ok(texts.some((t) => t.includes("1h")));
});

test("metric_line y축 라벨은 깔끔한 최대값, promql은 footer에 보존", () => {
  const node = buildLineChart(cpu);
  const ymax = find(node, "lc-ymax");
  assert.equal(ymax.text, "20%");
  assert.equal(find(node, "prom").text, cpu.promql);
});

test("metric_line 값이 모두 같아도(스팬 0) 좌표가 NaN이 되지 않는다", () => {
  const node = buildLineChart({
    ...cpu,
    series: [{ name: "flat", projectName: null, points: [{ ts: 1000, value: 0 }, { ts: 1060, value: 0 }] }],
  });
  const line = find(node, "lc-ln");
  assert.ok(!line.attrs.points.includes("NaN"), line.attrs.points);
});
