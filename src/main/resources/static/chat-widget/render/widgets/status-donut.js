import { h } from "../dom.js";

const R = 16;
export const DONUT_CIRC = 2 * Math.PI * R; // ~100.53
export const DONUT_GAP = 1.4; // 세그먼트 사이 간격(원둘레 단위) — 경계가 깔끔하게 떨어지도록

const LEVEL_CLASS = { good: "seg-good", warn: "seg-warn", crit: "seg-crit", muted: "seg-muted" };
function levelClass(level) {
  return LEVEL_CLASS[level] || "seg-muted";
}

function arc(seg, total, offset) {
  const full = total > 0 ? (seg.count / total) * DONUT_CIRC : 0;
  const dash = Math.max(0, full - DONUT_GAP); // 간격만큼 짧게 그려 세그먼트 사이를 띄운다
  return h("circle", {
    ns: "svg",
    className: `donut-seg ${levelClass(seg.level)}`,
    attrs: {
      cx: "21", cy: "21", r: String(R), fill: "none", "stroke-width": "5",
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
    offset += w.total > 0 ? (seg.count / w.total) * DONUT_CIRC : 0; // 다음 세그먼트는 전체 폭만큼 이동
  }
  const svg = h("svg", { ns: "svg", className: "donut", attrs: { viewBox: "0 0 42 42", width: "108", height: "108", "aria-hidden": "true" } }, [
    h("circle", { ns: "svg", attrs: { cx: "21", cy: "21", r: String(R), fill: "none", "stroke-width": "5", stroke: "var(--line)" } }),
    ...arcs,
    h("text", { ns: "svg", className: "donut-total", text: String(w.total), attrs: { x: "21", y: "20.5", "text-anchor": "middle", "font-size": "7.5", "font-weight": "700" } }),
    h("text", { ns: "svg", text: w.label, attrs: { x: "21", y: "25.5", "text-anchor": "middle", "font-size": "3", fill: "var(--muted)" } }),
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
