import { test } from "node:test";
import assert from "node:assert/strict";
import { buildChrome } from "../render/chrome.js";

function find(node, cls) {
  if (node.className && node.className.split(" ").includes(cls)) return node;
  for (const c of node.children || []) { const h = find(c, cls); if (h) return h; }
  return null;
}

function findByAttr(node, key) {
  if (node.attrs && key in node.attrs) return node;
  for (const c of node.children || []) { const h = findByAttr(c, key); if (h) return h; }
  return null;
}

test("buildChrome includes launcher, panel, form and message markers", () => {
  const node = buildChrome();
  assert.notEqual(find(node, "chat-widget"), null);
  assert.notEqual(find(node, "chat-launcher"), null);
  assert.notEqual(find(node, "chat-panel"), null);
  assert.notEqual(findByAttr(node, "data-chat-messages"), null);
  assert.notEqual(findByAttr(node, "data-chat-form"), null);
  assert.notEqual(findByAttr(node, "data-chat-input"), null);
  assert.notEqual(findByAttr(node, "data-chat-close"), null);
});

test("buildChrome root carries data-open=false initially", () => {
  const node = buildChrome();
  assert.equal(node.attrs["data-open"], "false");
});

test("buildChrome includes theme toggle, resize grip and unread badge markers", () => {
  const node = buildChrome();
  assert.notEqual(findByAttr(node, "data-chat-theme-toggle"), null);
  assert.notEqual(findByAttr(node, "data-chat-resize"), null);
  assert.notEqual(findByAttr(node, "data-chat-badge"), null);
});

test("buildChrome launcher and close button carry icon svg children (no bare glyph/× text)", () => {
  const node = buildChrome();
  const launcher = find(node, "chat-launcher");
  assert.notEqual(launcher, null);
  assert.equal(find(launcher, "launcher-icon-open").children[0].tag, "svg");
  assert.equal(find(launcher, "launcher-icon-close").children[0].tag, "svg");

  const closeButton = findByAttr(node, "data-chat-close");
  assert.equal(closeButton.children[0].tag, "svg");
});
