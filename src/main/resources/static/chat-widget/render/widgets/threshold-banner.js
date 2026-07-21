import { h } from "../dom.js";
import { icon } from "../icons.js";

const LEVEL_CLASS = { CRIT: "crit", WARN: "warn", GOOD: "info" };
const LEVEL_ICON = { CRIT: "error", WARN: "warning", GOOD: "info" };

export function buildThresholdBanner(w) {
  const level = LEVEL_CLASS[w.level] || "info";
  const body = [h("div", { className: "msg-t", text: w.title })];
  if (w.detail) body.push(h("div", { className: "msg-d", text: w.detail }));
  return h("div", { className: `msg ${level}` }, [
    h("div", { className: "msg-ic" }, [icon(LEVEL_ICON[w.level] || "info", 15)]),
    h("div", {}, body),
  ]);
}
