import { h } from "../dom.js";

// viewBox 좌표계 — CSS로 늘려도 비율 유지(preserveAspectRatio none은 폴리라인이 찌그러져 금지)
const W = 260;
const H = 120;
const PAD = { top: 8, right: 12, bottom: 6, left: 34 };

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

  const plotW = W - PAD.left - PAD.right;
  const plotH = H - PAD.top - PAD.bottom;
  const x = (ts) => PAD.left + ((ts - t0) / tSpan) * plotW;
  // y 도메인은 항상 0부터 — 구간을 잘라 기울기를 부풀리지 않는다
  const y = (v) => PAD.top + (1 - v / yMax) * plotH;

  const grid = [0, 0.5, 1].map((f) =>
    h("line", {
      ns: "svg", className: "lc-grid",
      attrs: { x1: PAD.left, y1: (PAD.top + f * plotH).toFixed(1), x2: W - PAD.right, y2: (PAD.top + f * plotH).toFixed(1) },
    }));

  const lines = w.series.map((s, i) => {
    const pts = s.points.map((p) => `${x(p.ts).toFixed(1)},${y(p.value).toFixed(1)}`).join(" ");
    return h("polyline", {
      ns: "svg", className: `lc-ln ln-${i + 1}`,
      attrs: { points: pts, fill: "none" },
    });
  });

  // 끝점 마커(r=4) — 라인 교차 지점에서도 읽히도록 CSS가 표면색 링(2px)을 입힌다
  const dots = w.series.map((s, i) => {
    const last = s.points[s.points.length - 1];
    if (!last) return null;
    return h("circle", {
      ns: "svg", className: `lc-dot ln-${i + 1}`,
      attrs: { cx: x(last.ts).toFixed(1), cy: y(last.value).toFixed(1), r: 4 },
    });
  }).filter(Boolean);

  const yLabels = [
    h("text", { ns: "svg", className: "lc-y lc-ymax", text: fmtTick(yMax, w.unit), attrs: { x: PAD.left - 5, y: PAD.top + 4, "text-anchor": "end" } }),
    h("text", { ns: "svg", className: "lc-y", text: `0${w.unit === "%" ? "%" : ""}`, attrs: { x: PAD.left - 5, y: PAD.top + plotH + 4, "text-anchor": "end" } }),
  ];

  const children = [
    h("div", { className: "eyebrow" }, [
      h("span", { text: w.title }),
      h("span", { text: `최근 ${w.range}` }),
    ]),
    // 제목(eyebrow)·레전드가 텍스트 채널을 이미 담당한다 — 사용자 유래 문자열은 attrs에 넣지 않는다(XSS 계약)
    h("svg", {
      ns: "svg", className: "lc-plot",
      attrs: { viewBox: `0 0 ${W} ${H}`, "aria-hidden": "true" },
    }, [...grid, ...yLabels, ...lines, ...dots]),
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

  children.push(h("div", { className: "wfoot" }, [h("span", { className: "prom", text: w.promql })]));
  return h("div", { className: "card widget" }, children);
}
