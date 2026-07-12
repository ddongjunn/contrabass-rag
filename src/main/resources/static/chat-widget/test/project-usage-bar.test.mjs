import { test } from "node:test";
import assert from "node:assert/strict";
import { buildProjectUsageBar } from "../render/widgets/project-usage-bar.js";

function findAll(node, cls, acc = []) {
  if (node.className && node.className.split(" ").includes(cls)) acc.push(node);
  for (const c of node.children || []) findAll(c, cls, acc);
  return acc;
}

const w = {
  type: "project_usage_bar", metric: "vCPU", unit: "%",
  rows: [
    { projectName: "service-prod", value: 82.0, display: "82%", severity: "WARN" },
    { projectName: "AUTOTEST", value: null, display: "무제한", severity: null },
  ],
};

test("project_usage_bar renders one row per project", () => {
  assert.equal(findAll(buildProjectUsageBar(w), "rk").length, 2);
});

test("project_usage_bar width from value, severity color", () => {
  const fill = findAll(buildProjectUsageBar(w), "rk-track")[0].children[0];
  assert.equal(fill.attrs.style, "width:82%");
  assert.equal(fill.className, "sev-warn");
});

test("project_usage_bar unlimited (value null) is muted full bar", () => {
  const fill = findAll(buildProjectUsageBar(w), "rk-track")[1].children[0];
  assert.equal(fill.className, "sev-muted");
  assert.equal(findAll(buildProjectUsageBar(w), "rk-val")[1].text, "무제한");
});
