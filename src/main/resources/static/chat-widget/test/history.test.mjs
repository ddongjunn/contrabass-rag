import { test } from "node:test";
import assert from "node:assert/strict";
import { buildHistory } from "../render/history.js";

const msgs = [
  { id: "1", role: "user", content: "CPU 사용률 TopN", sources: [] },
  { id: "2", role: "assistant", content: "CPU 사용률이 높은 인스턴스입니다", sources: [], widgets: [{ type: "metric_rank" }] },
  { id: "3", role: "assistant", content: "", sources: [], loading: true },
  { id: "4", role: "assistant", content: "일시적으로 응답할 수 없습니다.", sources: [], error: true },
];

test("buildHistory: role/content만 남기고 loading·error는 제외한다", () => {
  const out = buildHistory(msgs);
  assert.deepEqual(out, [
    { role: "user", content: "CPU 사용률 TopN" },
    { role: "assistant", content: "CPU 사용률이 높은 인스턴스입니다" },
  ]);
});

test("buildHistory: limit 초과분은 오래된 것부터 버린다", () => {
  const many = Array.from({ length: 10 }, (_, i) => ({ id: String(i), role: "user", content: `q${i}`, sources: [] }));
  const out = buildHistory(many, 3);
  assert.deepEqual(out.map((m) => m.content), ["q7", "q8", "q9"]);
});

test("buildHistory: 빈 목록이면 빈 배열", () => {
  assert.deepEqual(buildHistory([]), []);
});
