// Runtime tests for the testing helpers shipped at `@trailblaze/scripting/testing`.
// What we're guarding:
//   1. createMockClient: calls are recorded in order with the args the tool passed.
//   2. Stubs apply per-tool and persist until reset().
//   3. The reserved-props proxy guard matches the production client — `await client.tools`
//      doesn't silently record a `then` call.
//   4. createMockContext: defaults fill in, `runtime: "host"` flattens to undefined,
//      caller-supplied fields win over defaults.

import { describe, expect, test } from "bun:test";

import { createMockClient, createMockContext } from "./testing.js";

describe("createMockClient: call recording", () => {
  test("records every tool call in order with the args verbatim", async () => {
    const client = createMockClient();
    await (client.tools as Record<string, (a: Record<string, unknown>) => Promise<unknown>>)[
      "web_navigate"
    ]({ action: "GOTO", url: "https://example.test" });
    await (client.tools as Record<string, (a: Record<string, unknown>) => Promise<unknown>>)[
      "web_verify_text_visible"
    ]({ text: "Hello" });
    expect(client.calls.map((c) => c.tool)).toEqual(["web_navigate", "web_verify_text_visible"]);
    expect(client.calls[0]?.args).toEqual({ action: "GOTO", url: "https://example.test" });
    expect(client.calls[1]?.args).toEqual({ text: "Hello" });
  });

  test("default response is success with empty textContent", async () => {
    const client = createMockClient();
    const result = await (
      client.tools as Record<string, (a: Record<string, unknown>) => Promise<{
        success: boolean;
        textContent: string;
      }>>
    )["someTool"]({});
    expect(result.success).toBe(true);
    expect(result.textContent).toBe("");
  });
});

describe("createMockClient: stub", () => {
  test("stubbed textContent is returned to the caller", async () => {
    const client = createMockClient();
    client.stub("web_read_page", { textContent: "<html>hi</html>" });
    const result = await (
      client.tools as Record<string, (a: Record<string, unknown>) => Promise<{
        textContent: string;
      }>>
    )["web_read_page"]({});
    expect(result.textContent).toBe("<html>hi</html>");
  });

  test("stub with errorMessage throws with production wording", async () => {
    const client = createMockClient();
    client.stub("web_verify_text_visible", { textContent: "", errorMessage: "no match" });
    await expect(
      (
        client.tools as Record<string, (a: Record<string, unknown>) => Promise<unknown>>
      )["web_verify_text_visible"]({ text: "missing" }),
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
      client.tools as Record<string, (a: Record<string, unknown>) => Promise<{ textContent: string }>>
    )["foo"]({});
    expect(result.textContent).toBe(""); // stub gone
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
    expect(ctx.memory).toEqual({});
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
    expect(ctx.memory).toEqual({ last: "value" });
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
