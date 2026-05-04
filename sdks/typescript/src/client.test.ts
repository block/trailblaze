// Runtime tests for the `client.tools` Proxy + the reserved-property guard. Run via
// `bun test` from this directory. The test runner is `bun:test` (built into Bun) rather
// than a separate framework — the SDK is consumed via Bun in production and the runway CI
// step already has Bun on the path, so adding a third-party test runner would just bloat
// the dependency tree.
//
// What we're guarding against:
//   1. `await client.tools` (or any framework that probes `.then`) silently dispatching a
//      `callTool("then", ...)` to the daemon. This was the original motivation for the
//      reserved-props set — without it, the Proxy returns a callable for every string
//      property and the runtime treats `client.tools` as a thenable.
//   2. Symbol-keyed access producing a callable (which would break util.inspect, structured
//      cloning, and any iterator-based introspection).
//   3. Blank / whitespace-only tool names making it to the daemon as a wasted round-trip.
//   4. The Proxy regressing into a real-property lookup pattern that breaks the "any
//      augmented key works at runtime without runtime registration" contract.

import { describe, expect, test } from "bun:test";

import { createClient } from "./client.js";
import type { TrailblazeContext } from "./context.js";

const fakeCtx: TrailblazeContext = {
  sessionId: "test-session",
  invocationId: "test-invocation",
  device: { platform: "android", widthPixels: 0, heightPixels: 0, driverType: "test" },
  memory: {},
  baseUrl: "http://invalid.example",
};

describe("createClient", () => {
  test("returns an object exposing both callTool and the tools namespace", () => {
    const client = createClient(fakeCtx);
    expect(typeof client.callTool).toBe("function");
    expect(typeof client.tools).toBe("object");
  });
});

describe("client.tools Proxy: reserved JS-protocol probes", () => {
  // Any of these returning a function would cause framework code (await, JSON.stringify,
  // util.inspect, Object.getPrototypeOf, etc.) to silently fire a `callTool` dispatch.
  const reserved = [
    "then",
    "catch",
    "finally",
    "constructor",
    "prototype",
    "__proto__",
    "toString",
    "valueOf",
    "toJSON",
  ];
  for (const prop of reserved) {
    test(`client.tools.${prop} is undefined`, () => {
      const client = createClient(fakeCtx);
      const value = (client.tools as Record<string, unknown>)[prop];
      expect(value).toBeUndefined();
    });
  }

  test("await client.tools resolves to the namespace itself, no daemon dispatch", async () => {
    const client = createClient(fakeCtx);
    const awaited = await client.tools;
    expect(awaited).toBe(client.tools);
  });

  test("symbol-keyed access returns undefined (no spurious callable)", () => {
    const client = createClient(fakeCtx);
    type SymbolKeyed = { [k: symbol]: unknown };
    expect((client.tools as unknown as SymbolKeyed)[Symbol.iterator]).toBeUndefined();
    expect((client.tools as unknown as SymbolKeyed)[Symbol.toPrimitive]).toBeUndefined();
    expect((client.tools as unknown as SymbolKeyed)[Symbol.asyncIterator]).toBeUndefined();
  });
});

describe("client.tools Proxy: name validation", () => {
  test("blank string property access throws synchronously", () => {
    const client = createClient(fakeCtx);
    expect(() => (client.tools as Record<string, unknown>)[""]).toThrow(/empty or whitespace-only/);
  });

  test("whitespace-only property access throws synchronously", () => {
    const client = createClient(fakeCtx);
    expect(() => (client.tools as Record<string, unknown>)["   "]).toThrow(/empty or whitespace-only/);
    expect(() => (client.tools as Record<string, unknown>)["\t\n"]).toThrow(/empty or whitespace-only/);
  });
});

describe("client.tools Proxy: dispatch", () => {
  test("regular property access returns a callable", () => {
    const client = createClient(fakeCtx);
    const fn = (client.tools as Record<string, unknown>)["someTool"];
    expect(typeof fn).toBe("function");
  });

  // Note: we don't actually invoke the callable here because `callTool` would attempt an
  // HTTP fetch against `http://invalid.example` and fail. The signature shape is what we're
  // verifying — any future refactor that turns `tools.X` into a non-function (object, primitive)
  // would fail this test.
});
