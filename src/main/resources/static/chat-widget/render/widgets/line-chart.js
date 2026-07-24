import { h } from "../dom.js";
import { buildHead } from "./head.js";

// 정규화 좌표계 + preserveAspectRatio=none + 높이는 CSS 고정(170px).
// viewBox를 비율째 확대하면 넓은 임베드에서 선·글자·점이 통째로 비대해진다(실화면 확인 2026-07-24).
// 선은 vector-effect=non-scaling-stroke로 어떤 폭에서도 2px, 텍스트는 전부 SVG 밖 HTML에 둔다.
const VB = 1000;

/** 축용 깔끔한 상한: 1/2/5 × 10^k 중 max 이상 최소값. max=0이면 1(0 나눗셈 방지). */
function niceCeil(max) {
  if (max <= 0) return 1;
  const exp = Math.floor(Math.log10(max));
  for (const m of [1, 2, 5, 10]) {
    const v = m * 10 ** exp;
    if (v >= max) return v;
  }
  return 10 ** (exp + 1);
}

/** 값 라벨 포맷. % 는 소수 1자리, 그 외 단위는 k/M/G 축약(레전드·축 공용). */
function fmtValue(v, unit) {
  if (unit === "%") return `${v.toFixed(1)}%`;
  const abs = Math.abs(v);
  if (abs >= 1e9) return `${(v / 1e9).toFixed(1)}G${unit}`;
  if (abs >= 1e6) return `${(v / 1e6).toFixed(1)}M${unit}`;
  if (abs >= 1e3) return `${(v / 1e3).toFixed(1)}k${unit}`;
  return `${v.toFixed(1)}${unit}`;
}

/** 축 눈금 라벨 — 정수면 소수점 없이("20%"), 아니면 fmtValue와 동일. */
function fmtTick(v, unit) {
  if (Number.isInteger(v) && Math.abs(v) < 1e3) return `${v}${unit}`;
  return fmtValue(v, unit);
}

function fmtTime(ts) {
  const d = new Date(ts * 1000);
  const hh = String(d.getHours()).padStart(2, "0");
  const mm = String(d.getMinutes()).padStart(2, "0");
  return `${hh}:${mm}`;
}

export function buildLineChart(w) {
  const allPoints = w.series.flatMap((s) => s.points);
  const t0 = allPoints.reduce((m, p) => Math.min(m, p.ts), Infinity);
  const t1 = allPoints.reduce((m, p) => Math.max(m, p.ts), -Infinity);
  const yMax = niceCeil(allPoints.reduce((m, p) => Math.max(m, p.value), 0));
  const tSpan = t1 - t0 || 1;

  const x = (ts) => ((ts - t0) / tSpan) * VB;
  // y 도메인은 항상 0부터 — 구간을 잘라 기울기를 부풀리지 않는다
  const y = (v) => (1 - v / yMax) * VB;

  const grid = [0, 0.5, 1].map((f) =>
    h("line", {
      ns: "svg", className: "lc-grid",
      attrs: { x1: 0, y1: (f * VB).toFixed(0), x2: VB, y2: (f * VB).toFixed(0), "vector-effect": "non-scaling-stroke" },
    }));

  const lines = w.series.map((s, i) => {
    const pts = s.points.map((p) => `${x(p.ts).toFixed(1)},${y(p.value).toFixed(1)}`).join(" ");
    return h("polyline", {
      ns: "svg", className: `lc-ln ln-${i + 1}`,
      // non-scaling-stroke: preserveAspectRatio=none의 비균등 확대에서도 선은 2px 유지
      attrs: { points: pts, fill: "none", "vector-effect": "non-scaling-stroke" },
    });
  });

  const children = [
    buildHead(w.title, `최근 ${w.range}`),
    // y축 라벨은 HTML(스케일 안 탐) — 그리드 0/50/100% 지점과 정렬
    h("div", { className: "lc-wrap" }, [
      h("div", { className: "lc-yaxis" }, [
        h("span", { className: "lc-y lc-ymax", text: fmtTick(yMax, w.unit) }),
        h("span", { className: "lc-y", text: fmtTick(yMax / 2, w.unit) }),
        h("span", { className: "lc-y", text: `0${w.unit === "%" ? "%" : ""}` }),
      ]),
      h("svg", {
        ns: "svg", className: "lc-plot",
        attrs: { viewBox: `0 0 ${VB} ${VB}`, preserveAspectRatio: "none", "aria-hidden": "true" },
      }, [...grid, ...lines]),
    ]),
    h("div", { className: "lc-x" }, [
      h("span", { text: Number.isFinite(t0) ? fmtTime(t0) : "" }),
      h("span", { text: Number.isFinite(t1) ? fmtTime(t1) : "" }),
    ]),
  ];

  // 레전드는 2개 시리즈부터(1개면 제목이 이미 말한다) — 이름 + 마지막 값(색약·저대비 대비 텍스트 채널)
  if (w.series.length >= 2) {
    children.push(h("div", { className: "legend" }, w.series.map((s, i) => {
      const last = s.points[s.points.length - 1];
      return h("div", { className: "lg" }, [
        h("i", { className: `lg-dot ln-${i + 1}` }),
        h("span", { className: "lg-name", text: s.name }),
        h("span", { className: "lg-val", text: last ? fmtValue(last.value, w.unit) : "—" }),
      ]);
    })));
  }

  // promql 근거는 API 계약(w.promql)엔 유지하되 화면엔 표기하지 않는다(사용자 결정 2026-07-24).
  return h("div", { className: "card widget" }, children);
}
