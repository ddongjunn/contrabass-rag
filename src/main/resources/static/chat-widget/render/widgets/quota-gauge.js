import { h } from "../dom.js";
import { clampPct } from "../format.js";

const SEV_CLASS = { GOOD: "sev-good", WARN: "sev-warn", CRIT: "sev-crit" };

function gaugeRow(item) {
  const unlimited = item.quota == null || item.ratio == null;
  const cls = unlimited ? "sev-muted" : (SEV_CLASS[item.severity] || "sev-accent");
  const width = unlimited ? 100 : clampPct(item.ratio * 100);
  return h("div", { className: "rk" }, [
    h("div", { className: "rk-name" }, [h("span", { className: "rk-nm", text: item.resource })]),
    h("div", { className: "rk-track", attrs: { "aria-hidden": "true" } }, [
      h("i", { className: cls, attrs: { style: `width:${width.toFixed(0)}%` } }),
    ]),
    h("span", { className: "rk-val", text: item.display }),
  ]);
}

export function buildQuotaGauge(w) {
  return h("div", { className: "card widget" }, [
    h("div", { className: "eyebrow" }, [h("span", { text: "쿼터 사용량" })]),
    ...w.items.map(gaugeRow),
  ]);
}
