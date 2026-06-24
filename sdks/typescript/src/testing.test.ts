// Runtime tests for the testing helpers shipped at `@trailblaze/scripting/testing`.
// What we're guarding:
//   1. createMockClient: calls are recorded in order with the args the tool passed.
//   2. Stubs apply per-tool and persist until reset().
//   3. The reserved-props proxy guard matches the production client — `await client.tools`
//      doesn't silently record a `then` call.
//   4. createMockContext: defaults fill in, `runtime: "host"` flattens to undefined,
//      caller-supplied fields win over defaults.

import { describe, expect, test } from "bun:test";

import { _unwrapToolResult, type TrailblazeCallToolResult } from "./client.js";
import { createMockClient, createMockContext, type MockStubResponse } from "./testing.js";

describe("createMockClient: call recording", () => {
  test("records every tool call in order with the args verbatim", async () => {
    const client = createMockClient();
    await (client.tools as Record<string, (a: Record<string, unknown>) => Promise<unknown>>)[
      "web_navigate"
    ]({ action: "GOTO", url: "https://example.test" });
    await (client.tools as Record<string, (a: Record<string, unknown>) => Promise<unknown>>)[
      "web_verifyTextVisible"
    ]({ text: "Hello" });
    expect(client.calls.map((c) => c.tool)).toEqual(["web_navigate", "web_verifyTextVisible"]);
    expect(client.calls[0]?.args).toEqual({ action: "GOTO", url: "https://example.test" });
    expect(client.calls[1]?.args).toEqual({ text: "Hello" });
  });

  test("default unstubbed response unwraps to an empty string", async () => {
    // After the structured-content wire shape landed, the mock proxy mirrors production
    // and returns the UNWRAPPED `result` — not the envelope. Tools whose declared `result`
    // is `string` (the per-trailmap codegen default) see an empty string when no stub is set.
    const client = createMockClient();
    const result = await (
      client.tools as Record<string, (a: Record<string, unknown>) => Promise<string>>
    )["someTool"]({});
    expect(result).toBe("");
  });
});

describe("createMockClient: stub", () => {
  test("stubbed textContent is returned to the caller as the unwrapped string", async () => {
    const client = createMockClient();
    client.stub("web_read_page", { textContent: "<html>hi</html>" });
    const result = await (
      client.tools as Record<string, (a: Record<string, unknown>) => Promise<string>>
    )["web_read_page"]({});
    expect(result).toBe("<html>hi</html>");
  });

  test("stubbed structuredContent is returned to the caller as the unwrapped typed value", async () => {
    // When a producer (TS scripted tool with `trailblaze.tool<I, O>(handler)`) populates
    // structuredContent, the mock unwrap returns the typed value directly. Mirrors
    // production [createClient]'s behavior so a test asserting on the unwrapped shape
    // doesn't drift from what the live daemon would produce.
    const client = createMockClient();
    client.stub("typed_demo", {
      textContent: "(structured)",
      structuredContent: { formatted: "prefix:msg", inputLength: 3 },
    });
    const result = await (
      client.tools as Record<
        string,
        (a: Record<string, unknown>) => Promise<{ formatted: string; inputLength: number }>
      >
    )["typed_demo"]({});
    expect(result).toEqual({ formatted: "prefix:msg", inputLength: 3 });
  });

  test("stub with errorMessage throws with production wording", async () => {
    const client = createMockClient();
    client.stub("web_verifyTextVisible", { textContent: "", errorMessage: "no match" });
    await expect(
      (
        client.tools as Record<string, (a: Record<string, unknown>) => Promise<unknown>>
      )["web_verifyTextVisible"]({ text: "missing" }),
    ).rejects.toThrow(/tool failed: no match/);
  });

  test("reset clears recorded calls and stubs", async () => {
    const client = createMockClient();
    client.stub("foo", { textContent: "stubbed" });
    await (client.tools as Record<string, (a: Record<string, unknown>) => Promise<unknown>>)[
      "foo"
    ]({});
    expect(client.calls).toHaveLength(1);
    client.reset();
    expect(client.calls).toHaveLength(0);
    const result = await (
      client.tools as Record<string, (a: Record<string, unknown>) => Promise<string>>
    )["foo"]({});
    expect(result).toBe(""); // stub gone — default unwraps to empty string
  });

  test("blank stub name throws synchronously", () => {
    const client = createMockClient();
    expect(() => client.stub("   ", { textContent: "" })).toThrow(
      /empty or whitespace-only/,
    );
  });
});

describe("createMockClient: tools Proxy guards", () => {
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
    test(`client.tools.${prop} is undefined (no spurious recorded call)`, () => {
      const client = createMockClient();
      const value = (client.tools as Record<string, unknown>)[prop];
      expect(value).toBeUndefined();
      expect(client.calls).toHaveLength(0);
    });
  }

  test("await client.tools does not record a call", async () => {
    const client = createMockClient();
    const awaited = await client.tools;
    expect(awaited).toBe(client.tools);
    expect(client.calls).toHaveLength(0);
  });

  test("symbol-keyed access returns undefined", () => {
    const client = createMockClient();
    type SymbolKeyed = { [k: symbol]: unknown };
    expect((client.tools as unknown as SymbolKeyed)[Symbol.iterator]).toBeUndefined();
  });

  test("blank tool name throws synchronously at access", () => {
    const client = createMockClient();
    expect(() => (client.tools as Record<string, unknown>)[""]).toThrow(
      /empty or whitespace-only/,
    );
  });
});

describe("createMockContext", () => {
  test("fills defaults for everything except platform", () => {
    const ctx = createMockContext({ platform: "web" });
    expect(ctx.device.platform).toBe("web");
    expect(ctx.device.widthPixels).toBeGreaterThan(0);
    expect(ctx.device.heightPixels).toBeGreaterThan(0);
    expect(typeof ctx.sessionId).toBe("string");
    expect(typeof ctx.invocationId).toBe("string");
    expect(ctx.target).toBeUndefined();
    expect(ctx.memory.keys()).toEqual([]);
    expect(typeof ctx.logger.info).toBe("function");
  });

  test("caller-supplied fields win over defaults", () => {
    const ctx = createMockContext({
      platform: "android",
      sessionId: "explicit",
      invocationId: "inv-42",
      baseUrl: "http://test",
      device: { widthPixels: 100, heightPixels: 200, driverType: "test-driver" },
      memory: { last: "value" },
    });
    expect(ctx.sessionId).toBe("explicit");
    expect(ctx.invocationId).toBe("inv-42");
    expect(ctx.baseUrl).toBe("http://test");
    expect(ctx.device).toEqual({
      platform: "android",
      widthPixels: 100,
      heightPixels: 200,
      driverType: "test-driver",
    });
    expect(ctx.memory.get("last")).toBe("value");
    expect(ctx.memory.keys()).toEqual(["last"]);
  });

  test("deprecated device.driver fixture is normalized into driverType", () => {
    const ctx = createMockContext({
      platform: "android",
      device: { driver: "android-ondevice-accessibility" },
    });
    expect(ctx.device.driverType).toBe("android-ondevice-accessibility");
    expect(ctx.device.driver).toBe("android-ondevice-accessibility");
  });

  test("runtime: 'host' flattens to undefined on the context", () => {
    const ctx = createMockContext({ platform: "ios", runtime: "host" });
    expect(ctx.runtime).toBeUndefined();
  });

  test("runtime: 'ondevice' is preserved", () => {
    const ctx = createMockContext({ platform: "android", runtime: "ondevice" });
    expect(ctx.runtime).toBe("ondevice");
  });
});

describe("mock unwrap parity with production _unwrapToolResult", () => {
  // The mock proxy's unwrap (`mockUnwrapToolResult` in testing.ts) duplicates production
  // `_unwrapToolResult` by design — the testing module must stay runtime-import-free per
  // its file-header constraint, so we can't share the function. This drift-guard runs both
  // unwrap paths against a shared envelope table and asserts they produce identical
  // results. If a future change to either function shifts a branch, this test fails first.
  //
  // Each case is structured as `{ envelope, expected }` plus a `stub` derived from the
  // envelope so the mock client can be configured to surface the same envelope from its
  // dispatch. Production unwrap is called directly on the envelope; mock unwrap is exercised
  // indirectly via `await client.tools.<name>(args)` (which dispatches → unwraps).
  type ParityCase = {
    label: string;
    envelope: TrailblazeCallToolResult;
    stub: MockStubResponse;
    expected: unknown;
  };
  const parityCases: ParityCase[] = [
    {
      label: "structured payload present",
      envelope: {
        success: true,
        textContent: "(structured)",
        errorMessage: "",
        structuredContent: { formatted: "hi", inputLength: 2 },
      },
      stub: {
        textContent: "(structured)",
        structuredContent: { formatted: "hi", inputLength: 2 },
      },
      expected: { formatted: "hi", inputLength: 2 },
    },
    {
      label: "structured absent — textContent fallback",
      envelope: { success: true, textContent: "fallback", errorMessage: "" },
      stub: { textContent: "fallback" },
      expected: "fallback",
    },
    {
      label: "structured explicitly null — textContent fallback",
      envelope: {
        success: true,
        textContent: "fallback-on-null",
        errorMessage: "",
        structuredContent: null,
      },
      stub: { textContent: "fallback-on-null", structuredContent: null },
      expected: "fallback-on-null",
    },
    {
      label: "structured is 0 — falsy but present, returned as-is",
      envelope: { success: true, textContent: "distractor", errorMessage: "", structuredContent: 0 },
      stub: { textContent: "distractor", structuredContent: 0 },
      expected: 0,
    },
    {
      label: "structured is false — falsy but present, returned as-is",
      envelope: { success: true, textContent: "distractor", errorMessage: "", structuredContent: false },
      stub: { textContent: "distractor", structuredContent: false },
      expected: false,
    },
    {
      label: "structured is empty string — falsy but present, returned as-is",
      envelope: { success: true, textContent: "distractor", errorMessage: "", structuredContent: "" },
      stub: { textContent: "distractor", structuredContent: "" },
      expected: "",
    },
  ];

  for (const { label, envelope, stub, expected } of parityCases) {
    test(`parity: ${label}`, async () => {
      // Production unwrap — direct call on the envelope.
      const prodResult = _unwrapToolResult<unknown>(envelope);
      expect(prodResult).toEqual(expected);

      // Mock unwrap — exercised through the full dispatch → proxy path.
      const client = createMockClient();
      client.stub("paritytool", stub);
      const mockResult = await (
        client.tools as Record<string, (a: Record<string, unknown>) => Promise<unknown>>
      )["paritytool"]({});

      // Pin the equality between both paths. If a future refactor changes one branch but
      // not the other, the comparison fails before any consumer hits the divergence.
      expect(mockResult).toEqual(prodResult);
      expect(mockResult).toEqual(expected);
    });
  }
});
