import { test } from "node:test";
import assert from "node:assert/strict";
import { resolveUserId, resolveProject } from "../render/context.js";

test("resolveUserId prefers window override when set", () => {
  assert.equal(resolveUserId({ CONTRABASS_CHAT_USER_ID: "u1" }, "fallback-id"), "u1");
});

test("resolveUserId falls back when unset", () => {
  assert.equal(resolveUserId({}, "fallback-id"), "fallback-id");
});

test("resolveUserId falls back when blank string", () => {
  assert.equal(resolveUserId({ CONTRABASS_CHAT_USER_ID: "   " }, "fallback-id"), "fallback-id");
});

test("resolveProject returns value when set", () => {
  assert.equal(resolveProject({ CONTRABASS_CHAT_PROJECT: "AUTOTEST" }), "AUTOTEST");
});

test("resolveProject returns undefined when unset", () => {
  assert.equal(resolveProject({}), undefined);
});

test("resolveProject returns undefined when blank string", () => {
  assert.equal(resolveProject({ CONTRABASS_CHAT_PROJECT: "  " }), undefined);
});
