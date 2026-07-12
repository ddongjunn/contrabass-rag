import { h } from "../dom.js";

const R = 15.5;
export const DONUT_CIRC = 2 * Math.PI * R; // ~97.39

const LEVEL_CLASS = { good: "seg-good", warn: "seg-warn", crit: "seg-crit", muted: "seg-muted" };
function levelClass(level) {
  return LEVEL_CLASS[level] || "seg-muted";
}

function arc(seg, total, offset) {
  const dash = total > 0 ? (seg.count / total) * DONUT_CIRC : 0;
  return h("circle", {
    ns: "svg",
    className: `donut-seg ${levelClass(seg.level)}`,
    attrs: {
      cx: "21", cy: "21", r: String(R), fill: "none", "stroke-width": "7",
      "stroke-dasharray": `${dash.toFixed(2)} ${(DONUT_CIRC - dash).toFixed(2)}`,
      "stroke-dashoffset": `${(-offset).toFixed(2)}`,
      transform: "rotate(-90 21 21)",
    },
  });
}

export function buildStatusDonut(w) {
  const arcs = [];
  let offset = 0;
  for (const seg of w.segments) {
    arcs.push(arc(seg, w.total, offset));
    offset += w.total > 0 ? (seg.count / w.total) * DONUT_CIRC : 0;
  }
  const svg = h("svg", { ns: "svg", className: "donut", attrs: { viewBox: "0 0 42 42", width: "112", height: "112", "aria-hidden": "true" } }, [
    h("circle", { ns: "svg", attrs: { cx: "21", cy: "21", r: String(R), fill: "none", "stroke-width": "7", stroke: "var(--line)" } }),
    ...arcs,
    h("text", { ns: "svg", className: "donut-total", text: String(w.total), attrs: { x: "21", y: "20.5", "text-anchor": "middle", "font-size": "8", "font-weight": "800" } }),
    h("text", { ns: "svg", text: w.label, attrs: { x: "21", y: "26", "text-anchor": "middle", "font-size": "3.2", fill: "var(--muted)" } }),
  ]);
  const legend = h("div", { className: "legend" }, w.segments.map((seg) =>
    h("div", { className: "lg" }, [
      h("i", { className: `lg-dot ${levelClass(seg.level)}` }),
      h("span", { className: "lg-name", text: seg.status }),
      h("span", { className: "lg-val", text: String(seg.count) }),
    ])
  ));
  return h("div", { className: "card widget" }, [
    h("div", { className: "eyebrow" }, [h("span", { text: `${w.label} 상태 분포` }), h("span", { text: `총 ${w.total}` })]),
    h("div", { className: "donut-wrap" }, [svg, legend]),
  ]);
}
