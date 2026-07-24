import { h } from "../dom.js";
import { clampPct } from "../format.js";
import { buildHead } from "./head.js";

const SEV_CLASS = { GOOD: "sev-good", WARN: "sev-warn", CRIT: "sev-crit" };

function usageRow(row) {
  const cls = SEV_CLASS[row.severity] || "sev-accent";
  const width = clampPct(row.value);
  return h("div", { className: "rk" }, [
    h("div", { className: "rk-name" }, [h("span", { className: "rk-nm", text: row.name, attrs: { title: row.name } })]),
    h("div", { className: "rk-track", attrs: { "aria-hidden": "true" } }, [
      h("i", { className: cls, attrs: { style: `width:${width.toFixed(0)}%` } }),
    ]),
    h("span", { className: "rk-val", text: row.display }),
  ]);
}

export function buildUsageBar(w) {
  return h("div", { className: "card widget" }, [
    buildHead(w.title, null),
    ...w.rows.map(usageRow),
  ]);
}
