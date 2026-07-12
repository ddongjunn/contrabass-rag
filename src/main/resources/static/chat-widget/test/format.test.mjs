import { test } from "node:test";
import assert from "node:assert/strict";
import { clampPct, barWidth } from "../render/format.js";

test("clampPct clamps to 0..100", () => {
  assert.equal(clampPct(-5), 0);
  assert.equal(clampPct(150), 100);
  assert.equal(clampPct(42), 42);
});

test("clampPct returns 0 for null/NaN", () => {
  assert.equal(clampPct(null), 0);
  assert.equal(clampPct(NaN), 0);
});

test("barWidth scales value against max", () => {
  assert.equal(barWidth(50, 100), 50);
  assert.equal(barWidth(91.2, 91.2), 100);
});

test("barWidth returns 0 when max is 0 or missing", () => {
  assert.equal(barWidth(5, 0), 0);
  assert.equal(barWidth(5, null), 0);
});
