import { test } from "node:test";
import assert from "node:assert/strict";
import { h, mount } from "../render/dom.js";

test("h builds a PlainNode with tag only", () => {
  assert.deepEqual(h("div"), { tag: "div" });
});

test("h includes className, text, attrs, ns", () => {
  const node = h("i", { className: "bar", text: "91%", attrs: { style: "width:91%" }, ns: "svg" });
  assert.deepEqual(node, { tag: "i", ns: "svg", className: "bar", text: "91%", attrs: { style: "width:91%" } });
});

test("h coerces text to string", () => {
  assert.equal(h("span", { text: 342 }).text, "342");
});

test("h nests children only when non-empty", () => {
  assert.equal(h("div", {}, []).children, undefined);
  const parent = h("div", {}, [h("span")]);
  assert.deepEqual(parent.children, [{ tag: "span" }]);
});

// 최소 가짜 DOM — 실제 브라우저 API의 형태만 흉내
function fakeDoc() {
  const make = (tag, ns) => ({
    tag, ns, attrs: {}, text: null, kids: [],
    setAttribute(k, v) { this.attrs[k] = v; },
    set textContent(v) { this.text = v; },
    append(...cs) { this.kids.push(...cs); },
  });
  return {
    createElement: (tag) => make(tag, null),
    createElementNS: (ns, tag) => make(tag, ns),
  };
}

test("mount puts text into textContent (never markup)", () => {
  const el = mount(h("span", { text: "<img src=x>" }), fakeDoc());
  assert.equal(el.text, "<img src=x>");   // 문자열 그대로, 실행 불가
  assert.equal(el.tag, "span");
});

test("mount sets attrs via setAttribute and nests children", () => {
  const el = mount(h("div", { className: "card", attrs: { role: "img" } }, [h("i", { text: "x" })]), fakeDoc());
  assert.equal(el.attrs.class, "card");
  assert.equal(el.attrs.role, "img");
  assert.equal(el.kids.length, 1);
  assert.equal(el.kids[0].text, "x");
});

test("mount uses createElementNS for svg nodes", () => {
  const el = mount(h("circle", { ns: "svg" }), fakeDoc());
  assert.equal(el.ns, "http://www.w3.org/2000/svg");
});
