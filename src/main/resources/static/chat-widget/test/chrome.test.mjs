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
