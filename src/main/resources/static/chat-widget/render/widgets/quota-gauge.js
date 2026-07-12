import { h } from "../dom.js";
import { clampPct } from "../format.js";

const SEV_CLASS = { GOOD: "sev-good", WARN: "sev-warn", CRIT: "sev-crit" };

// display("820 / 1000")를 현재량/총량으로 시각 분리 — 백엔드 포맷 문자열을 재포맷하지 않고
// " / " 구분선에서만 나눠 현재량은 진하게, 총량은 흐리게 보여준다.
function valueNode(display) {
  const idx = display.indexOf(" / ");
  if (idx === -1) return h("span", { className: "rk-val", text: display });
  return h("span", { className: "rk-val" }, [
    h("span", { className: "rk-cur", text: display.slice(0, idx) }),
    h("span", { className: "rk-tot", text: display.slice(idx) }),
  ]);
}

function gaugeRow(item) {
  const unlimited = item.quota == null || item.ratio == null;
  const cls = unlimited ? "sev-muted" : (SEV_CLASS[item.severity] || "sev-accent");
  const width = unlimited ? 100 : clampPct(item.ratio * 100);
  return h("div", { className: "rk" }, [
    h("div", { className: "rk-name" }, [h("span", { className: "rk-nm", text: item.resource })]),
    h("div", { className: "rk-track", attrs: { "aria-hidden": "true" } }, [
      h("i", { className: cls, attrs: { style: `width:${width.toFixed(0)}%` } }),
    ]),
    valueNode(item.display),
  ]);
}

export function buildQuotaGauge(w) {
  return h("div", { className: "card widget" }, [
    h("div", { className: "eyebrow" }, [h("span", { text: "쿼터 사용량" })]),
    ...w.items.map(gaugeRow),
  ]);
}
