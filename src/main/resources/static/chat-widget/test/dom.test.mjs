import { test } from "node:test";
import assert from "node:assert/strict";
import { h } from "../render/dom.js";

test("h is a function", () => {
  assert.equal(typeof h, "function");
});
