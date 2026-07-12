import { h } from "../dom.js";
import { clampPct } from "../format.js";

const SEV_CLASS = { GOOD: "sev-good", WARN: "sev-warn", CRIT: "sev-crit" };

function usageRow(row) {
  const unlimited = row.value == null;
  const cls = unlimited ? "sev-muted" : (SEV_CLASS[row.severity] || "sev-accent");
  const width = unlimited ? 100 : clampPct(row.value);
  return h("div", { className: "rk" }, [
    h("div", { className: "rk-top" }, [
      h("span", { className: "rk-nm", text: row.projectName }),
      h("span", { className: "rk-val", text: row.display }),
    ]),
    h("div", { className: "rk-track", attrs: { role: "img", "aria-label": `${row.projectName} ${row.display}` } }, [
      h("i", { className: cls, attrs: { style: `width:${width.toFixed(0)}%` } }),
    ]),
  ]);
}

export function buildProjectUsageBar(w) {
  return h("div", { className: "card widget" }, [
    h("div", { className: "eyebrow" }, [h("span", { text: `프로젝트별 ${w.metric} 사용률` })]),
    ...w.rows.map(usageRow),
  ]);
}
