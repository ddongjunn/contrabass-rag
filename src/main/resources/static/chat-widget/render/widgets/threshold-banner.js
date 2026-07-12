import { h } from "../dom.js";

const LEVEL_CLASS = { CRIT: "crit", WARN: "warn", GOOD: "info" };
const LEVEL_ICON = { CRIT: "!", WARN: "!", GOOD: "i" };

export function buildThresholdBanner(w) {
  const level = LEVEL_CLASS[w.level] || "info";
  const body = [h("div", { className: "msg-t", text: w.title })];
  if (w.detail) body.push(h("div", { className: "msg-d", text: w.detail }));
  return h("div", { className: `msg ${level}`, attrs: { role: "img", "aria-label": w.title } }, [
    h("div", { className: "msg-ic", text: LEVEL_ICON[w.level] || "i" }),
    h("div", {}, body),
  ]);
}
