import { test } from "node:test";
import assert from "node:assert/strict";
import { buildStatusDonut, DONUT_CIRC } from "../render/widgets/status-donut.js";

function findAll(node, cls, acc = []) {
  if (node.className && node.className.split(" ").includes(cls)) acc.push(node);
  for (const c of node.children || []) findAll(c, cls, acc);
  return acc;
}

const w = {
  type: "status_donut", label: "인스턴스", total: 140,
  segments: [
    { status: "ACTIVE", count: 128, level: "good" },
    { status: "SHUTOFF", count: 9, level: "muted" },
    { status: "ERROR", count: 3, level: "crit" },
  ],
};

test("status_donut renders one legend row per segment", () => {
  const rows = findAll(buildStatusDonut(w), "lg");
  assert.equal(rows.length, 3);
});

test("status_donut legend shows status and count", () => {
  const node = buildStatusDonut(w);
  assert.deepEqual(findAll(node, "lg-name").map((n) => n.text), ["ACTIVE", "SHUTOFF", "ERROR"]);
  assert.deepEqual(findAll(node, "lg-val").map((n) => n.text), ["128", "9", "3"]);
});

test("status_donut segment arc dasharray is count/total of circumference", () => {
  const node = buildStatusDonut(w);
  const arcs = findAll(node, "donut-seg");
  const activeDash = (128 / 140) * DONUT_CIRC;
  assert.ok(arcs[0].attrs["stroke-dasharray"].startsWith(activeDash.toFixed(2)));
});

test("status_donut never uses round linecap", () => {
  const node = buildStatusDonut(w);
  for (const arc of findAll(node, "donut-seg")) {
    assert.notEqual(arc.attrs["stroke-linecap"], "round");
  }
});

test("status_donut center shows total", () => {
  const node = buildStatusDonut(w);
  const texts = findAll(node, "donut-total");
  assert.equal(texts[0].text, "140");
});
