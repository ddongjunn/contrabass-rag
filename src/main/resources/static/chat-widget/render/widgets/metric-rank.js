import { h } from "../dom.js";
import { barWidth } from "../format.js";

const SEV_CLASS = { GOOD: "sev-good", WARN: "sev-warn", CRIT: "sev-crit" };
function sevClass(sev) {
  return SEV_CLASS[sev] || "sev-accent";
}

function sparkNode(spark) {
  const w = 96, hgt = 22;
  const max = spark.reduce((m, v) => Math.max(m, v), 0) || 1;
  const min = spark.reduce((m, v) => Math.min(m, v), spark[0]);
  const span = max - min || 1;
  const step = spark.length > 1 ? w / (spark.length - 1) : w;
  const pts = spark
    .map((v, i) => `${(i * step).toFixed(1)},${(hgt - ((v - min) / span) * hgt).toFixed(1)}`)
    .join(" ");
  return h("svg", { ns: "svg", className: "rk-spark", attrs: { viewBox: `0 0 ${w} ${hgt}`, preserveAspectRatio: "none", "aria-hidden": "true" } }, [
    h("polyline", { ns: "svg", attrs: { points: pts, fill: "none", "stroke-width": "2", "stroke-linecap": "round", "stroke-linejoin": "round" } }),
  ]);
}

export function buildMetricRank(w) {
  const max = w.rows.reduce((m, r) => Math.max(m, r.value), 0);
  const rows = w.rows.map((r) => {
    const width = Math.max(2, barWidth(r.value, max));
    const nameCell = [h("span", { className: "rk-nm", text: r.instanceName })];
    if (r.projectName) nameCell.push(h("span", { className: "rk-prj", text: r.projectName }));
    const rowChildren = [
      h("div", { className: "rk-name" }, nameCell),
      h("div", { className: "rk-track", attrs: { "aria-hidden": "true" } }, [
        h("i", { className: sevClass(r.severity), attrs: { style: `width:${width.toFixed(0)}%` } }),
      ]),
      h("span", { className: "rk-val", text: r.display }),
    ];
    if (Array.isArray(r.spark) && r.spark.length > 1) rowChildren.push(sparkNode(r.spark));
    return h("div", { className: "rk" }, rowChildren);
  });
  return h("div", { className: "card widget" }, [
    h("div", { className: "eyebrow" }, [
      h("span", { text: w.title }),
      h("span", { text: `최근 ${w.window}` }),
    ]),
    ...rows,
    // promql 근거는 API 계약(w.promql)엔 유지하되 화면엔 표기하지 않는다(사용자 결정 2026-07-24).
  ]);
}
