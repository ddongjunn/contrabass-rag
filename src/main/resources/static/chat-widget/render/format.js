export function clampPct(n) {
  if (n == null || Number.isNaN(n)) return 0;
  return Math.max(0, Math.min(100, n));
}

export function barWidth(value, max) {
  if (!(max > 0)) return 0;
  return clampPct((value / max) * 100);
}
