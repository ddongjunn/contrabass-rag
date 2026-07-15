import { h } from "./dom.js";
import { buildMetricRank } from "./widgets/metric-rank.js";
import { buildInventoryCount } from "./widgets/inventory-count.js";
import { buildQuotaGauge } from "./widgets/quota-gauge.js";
import { buildProjectUsageBar } from "./widgets/project-usage-bar.js";
import { buildStatusDonut } from "./widgets/status-donut.js";
import { buildThresholdBanner } from "./widgets/threshold-banner.js";

const BUILDERS = {
  metric_rank: buildMetricRank,
  inventory_count: buildInventoryCount,
  quota_gauge: buildQuotaGauge,
  project_usage_bar: buildProjectUsageBar,
  status_donut: buildStatusDonut,
  threshold_banner: buildThresholdBanner,
};

function emptyHint(w) {
  if (!w) return "";
  return w.title || w.label || w.metric || "";
}

export function buildEmptyState(widget) {
  return h("div", { className: "card widget" }, [
    h("div", { className: "state" }, [
      h("div", { className: "state-ic", text: "🔍" }),
      h("div", { className: "state-msg", text: "조건에 맞는 결과가 없어요" }),
      // title은 metric_rank에만 있다. status_donut/project_usage_bar는 label/metric을 쓴다.
      h("div", { className: "state-hint", text: emptyHint(widget) }),
    ]),
  ]);
}

export function buildWidget(widget) {
  const builder = BUILDERS[widget.type];
  if (!builder) return null;            // 모르는 type은 스킵 (하위호환)
  if (widget.empty) return buildEmptyState(widget);
  return builder(widget);
}
