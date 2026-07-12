import { h } from "../dom.js";

export function buildInventoryCount(w) {
  const children = [
    h("div", { className: "hero", text: String(w.total) }),
    h("div", { className: "count-lbl", text: w.label }),
  ];
  if (w.condition) children.push(h("div", { className: "count-cond", text: w.condition }));
  return h("div", { className: "card widget count" }, children);
}
