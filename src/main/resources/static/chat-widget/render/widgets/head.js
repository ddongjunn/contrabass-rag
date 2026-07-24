import { h } from "../dom.js";

// 지표 종류 → 고정 색 슬롯(정체성 채널). 막대 색은 severity(상태)라 카드끼리 똑같아 보인다는
// 실사용 피드백(2026-07-24) — 종류 구분은 헤더 태그가 맡는다. 순서 고정, 순환 금지(dataviz).
const TAGS = [
  ["IP", "mk-7"],
  ["CPU", "mk-1"],
  ["메모리", "mk-3"],
  ["네트워크", "mk-2"],
  ["디스크", "mk-4"],
  ["스토리지", "mk-5"],
  ["VM", "mk-6"],
];

/** 위젯 헤더: [지표 태그] 제목 ─────── 부제(우측). 태그 키워드가 제목 맨 앞이면 중복을 줄이려 뗀다. */
export function buildHead(title, sub) {
  const hit = TAGS.find(([kw]) => title.includes(kw));
  const children = [];
  let rest = title;
  if (hit) {
    const [kw, cls] = hit;
    if (rest.startsWith(`${kw} `)) rest = rest.slice(kw.length + 1);
    children.push(h("span", { className: `wg-tag ${cls}`, text: kw }));
  }
  children.push(h("span", { className: "wg-title", text: rest }));
  if (sub) children.push(h("span", { className: "wg-sub", text: sub }));
  return h("div", { className: "wg-head" }, children);
}
