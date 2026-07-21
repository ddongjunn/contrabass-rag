import { test } from "node:test";
import assert from "node:assert/strict";
import {
  getTheme, setTheme, applyTheme, loadPanelSize, savePanelSize, clampPanelSize,
} from "../render/theme.js";

function fakeStorage(initial = {}) {
  const data = { ...initial };
  return {
    getItem: (k) => (k in data ? data[k] : null),
    setItem: (k, v) => { data[k] = String(v); },
    _data: data,
  };
}

test("getTheme defaults to dark when nothing stored", () => {
  assert.equal(getTheme(fakeStorage()), "dark");
});

test("getTheme returns light when explicitly stored", () => {
  assert.equal(getTheme(fakeStorage({ "contrabass.chat.theme": "light" })), "light");
});

test("getTheme falls back to dark on garbage value", () => {
  assert.equal(getTheme(fakeStorage({ "contrabass.chat.theme": "purple" })), "dark");
});

test("setTheme persists a valid value", () => {
  const storage = fakeStorage();
  setTheme(storage, "light");
  assert.equal(storage._data["contrabass.chat.theme"], "light");
});

test("setTheme normalizes invalid value to dark", () => {
  const storage = fakeStorage();
  setTheme(storage, "nonsense");
  assert.equal(storage._data["contrabass.chat.theme"], "dark");
});

test("applyTheme sets data-theme=light on the host for light", () => {
  const attrs = {};
  const host = {
    setAttribute: (k, v) => { attrs[k] = v; },
    removeAttribute: (k) => { delete attrs[k]; },
  };
  applyTheme(host, "light");
  assert.equal(attrs["data-theme"], "light");
});

test("applyTheme removes data-theme attribute for dark (default)", () => {
  const attrs = { "data-theme": "light" };
  const host = {
    setAttribute: (k, v) => { attrs[k] = v; },
    removeAttribute: (k) => { delete attrs[k]; },
  };
  applyTheme(host, "dark");
  assert.equal("data-theme" in attrs, false);
});

test("loadPanelSize returns null when nothing stored", () => {
  assert.equal(loadPanelSize(fakeStorage()), null);
});

test("loadPanelSize returns null on corrupted JSON", () => {
  assert.equal(loadPanelSize(fakeStorage({ "contrabass.chat.panelSize": "{not json" })), null);
});

test("loadPanelSize round-trips a saved size", () => {
  const storage = fakeStorage();
  savePanelSize(storage, { width: 500, height: 700 });
  assert.deepEqual(loadPanelSize(storage), { width: 500, height: 700 });
});

test("clampPanelSize enforces minimum size", () => {
  const result = clampPanelSize(100, 100, { width: 1200, height: 900 });
  assert.equal(result.width, 340);
  assert.equal(result.height, 440);
});

test("clampPanelSize caps at 90% of viewport", () => {
  const result = clampPanelSize(5000, 5000, { width: 1000, height: 800 });
  assert.equal(result.width, 900);
  assert.equal(result.height, 720);
});

test("clampPanelSize passes through an in-range size unchanged", () => {
  const result = clampPanelSize(460, 620, { width: 1200, height: 900 });
  assert.equal(result.width, 460);
  assert.equal(result.height, 620);
});
