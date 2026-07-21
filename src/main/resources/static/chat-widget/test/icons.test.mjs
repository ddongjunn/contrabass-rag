import { test } from "node:test";
import assert from "node:assert/strict";
import { icon, resizeGripIcon, ICON_NAMES } from "../render/icons.js";

test("icon returns an svg PlainNode with a path child carrying real path data", () => {
  const node = icon("close");
  assert.equal(node.tag, "svg");
  assert.equal(node.ns, "svg");
  assert.equal(node.children.length, 1);
  assert.equal(node.children[0].tag, "path");
  assert.ok(node.children[0].attrs.d.length > 10, "path d 속성이 비어있으면 안 됨");
});

test("icon defaults viewBox to Material Symbols 24px grid and fill=currentColor", () => {
  const node = icon("close");
  assert.equal(node.attrs.viewBox, "0 -960 960 960");
  assert.equal(node.attrs.fill, "currentColor");
  assert.equal(node.attrs["aria-hidden"], "true");
});

test("icon respects custom size", () => {
  const node = icon("send", 32);
  assert.equal(node.attrs.width, "32");
  assert.equal(node.attrs.height, "32");
});

test("icon throws on unknown name", () => {
  assert.throws(() => icon("does-not-exist"));
});

test("ICON_NAMES exposes every bundled icon used by the redesign", () => {
  const expected = [
    "chat", "close", "light_mode", "dark_mode", "send",
    "support_agent", "search_off", "info", "warning", "error",
  ];
  for (const name of expected) {
    assert.ok(ICON_NAMES.includes(name), `${name} 아이콘이 없음`);
  }
});

test("resizeGripIcon returns an svg PlainNode with two diagonal line marks", () => {
  const node = resizeGripIcon();
  assert.equal(node.tag, "svg");
  assert.equal(node.ns, "svg");
  assert.equal(node.children.length, 2);
  for (const c of node.children) assert.equal(c.tag, "line");
});
