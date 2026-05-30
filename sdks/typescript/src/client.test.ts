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

import { createClient, _unwrapToolResult, type TrailblazeCallToolResult } from "./client.js";
import type { TrailblazeContext } from "./context.js";

const fakeCtx: TrailblazeContext = {
  sessionId: "test-session",
  invocationId: "test-invocation",
  device: { platform: "android", widthPixels: 0, heightPixels: 0, driverType: "test" },
  memory: {},
  baseUrl: "http://invalid.example",
};

describe("createClient", () => {
  test("returns an object whose runtime carries callTool (hidden from the public type) and the tools namespace", () => {
    const client = createClient(fakeCtx);
    // `callTool` is omitted from the exported `TrailblazeClient` type so authors can only
    // dispatch through `client.tools.<name>(args)`. The runtime object still carries the
    // method as the internal dispatch primitive the `tools` Proxy delegates to — this test
    // exercises that runtime contract directly via a `Record` cast.
    expect(typeof (client as unknown as Record<string, unknown>).callTool).toBe("function");
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

describe("unwrapToolResult", () => {
  // Pins the SDK-side unwrap branches for the structured-content wire shape. Together with
  // the Kotlin-side `JsScriptingCallbackContractTest`, these tests guarantee that a TS
  // consumer reading `client.tools.<name>(args)` gets the typed `result` declared in
  // `TrailblazeToolMap` end-to-end (producer populates → wire carries → SDK unwraps).

  test("returns structuredContent when the producer populated one", () => {
    const envelope: TrailblazeCallToolResult = {
      success: true,
      textContent: "",
      errorMessage: "",
      structuredContent: { formatted: "hi", inputLength: 2 },
    };
    type Out = { formatted: string; inputLength: number };
    const unwrapped = _unwrapToolResult<Out>(envelope);
    expect(unwrapped).toEqual({ formatted: "hi", inputLength: 2 });
  });

  test("falls back to textContent when structuredContent is absent (string-result tool)", () => {
    // Most existing tools — Kotlin-side, no structured payload populated. The unwrap returns
    // textContent, and the typed `result: string` in TrailblazeToolMap matches.
    const envelope: TrailblazeCallToolResult = {
      success: true,
      textContent: "https://en.wikipedia.org/wiki/Main_Page",
      errorMessage: "",
    };
    const unwrapped = _unwrapToolResult<string>(envelope);
    expect(unwrapped).toBe("https://en.wikipedia.org/wiki/Main_Page");
  });

  test("falls back to textContent when structuredContent is explicitly null", () => {
    // Wire JSON can carry `structured_content: null` (a serializer that always emits the key
    // with an explicit null instead of omitting it). The unwrap must still take the
    // textContent branch; treating an explicit-null structured payload as "structured value
    // is null" would surface `null` to a `result: string` consumer.
    const envelope: TrailblazeCallToolResult = {
      success: true,
      textContent: "fallback text",
      errorMessage: "",
      structuredContent: null,
    };
    const unwrapped = _unwrapToolResult<string>(envelope);
    expect(unwrapped).toBe("fallback text");
  });

  test("script-overload tools translate (fn, ...args) into a stringified `apply` call", async () => {
    // Pins the production buildScriptOverloadArgs translation: `(fnSrc).apply(null, [args])`
    // with JSON-encoded args. The mock client exercises the same proxy-side translation as
    // production (see SCRIPT_OVERLOAD_TOOLS in client.ts and the sibling mirror in testing.ts)
    // so the wire payload format is locked here as a regression gate.
    const { createMockClient } = await import("./testing.js");
    const mock = createMockClient();
    // Kotlin's `result?.toString()` emits the raw string for a `() => "..."` arrow — no
    // JSON-quoting on the wire. Matching that exactly here so the test reflects what
    // production sees (an earlier revision used `'"https://example.com"'` to exercise a
    // JSON.parse heuristic that no longer exists — that heuristic corrupted any string
    // return whose textual form happened to parse as JSON, e.g. `"42"` → `42`).
    mock.stub("web_evaluate", { textContent: "https://example.com" });
    type ToolsAny = Record<string, (...a: unknown[]) => Promise<unknown>>;
    const tools = mock.tools as unknown as ToolsAny;
    const result = await tools.web_evaluate(
      (path: string) => `prefix-${path}`,
      "/wiki/Main_Page",
    );
    // The proxy passes the textContent through `_unwrapToolResult` unchanged. The
    // function-overload's `TResult` is a documented type lie until structured-content
    // support lands — see [WebEvaluateMethod] kdoc.
    expect(result).toBe("https://example.com");
    // The wire payload is the `apply` form with JSON-encoded args. Pinning the exact format
    // catches accidental refactors that drop the `apply(null, ...)` or the JSON-stringify step.
    expect(mock.calls).toHaveLength(1);
    const call = mock.calls[0]!;
    expect(call.tool).toBe("web_evaluate");
    expect(call.args).toEqual({
      script: `((path) => \`prefix-\${path}\`).apply(null, ["/wiki/Main_Page"])`,
    });
  });

  test("script-overload tools accept a bare string as the script", async () => {
    const { createMockClient } = await import("./testing.js");
    const mock = createMockClient();
    mock.stub("web_evaluate", { textContent: "ok" });
    type ToolsAny = Record<string, (...a: unknown[]) => Promise<unknown>>;
    const tools = mock.tools as unknown as ToolsAny;
    await tools.web_evaluate("() => window.location.href");
    expect(mock.calls[0]!.args).toEqual({ script: "() => window.location.href" });
  });

  test("script-overload tools pass through an args object unchanged", async () => {
    const { createMockClient } = await import("./testing.js");
    const mock = createMockClient();
    mock.stub("web_evaluate", { textContent: "" });
    type ToolsAny = Record<string, (...a: unknown[]) => Promise<unknown>>;
    const tools = mock.tools as unknown as ToolsAny;
    await tools.web_evaluate({ script: "(() => window.location.href)()" });
    expect(mock.calls[0]!.args).toEqual({ script: "(() => window.location.href)()" });
  });

  test("script-overload tools reject non-fn/string/object inputs with a tool-named error", async () => {
    const { createMockClient } = await import("./testing.js");
    const mock = createMockClient();
    type ToolsAny = Record<string, (...a: unknown[]) => Promise<unknown>>;
    const tools = mock.tools as unknown as ToolsAny;
    // Number primitive — not a valid script-overload first arg. Throw must name the tool so
    // a debugger pointed at the stack frame can see WHICH tool was misused, not just "bad arg".
    await expect(tools.web_evaluate(42 as unknown)).rejects.toThrow(/web_evaluate.*number/);
    // null — distinct error path from undefined (typeof null === "object" but we explicitly
    // exclude it). Verify the tool-name still surfaces.
    await expect(tools.web_evaluate(null as unknown)).rejects.toThrow(/web_evaluate.*null/);
  });

  test("script-overload web_evaluate preserves legitimate empty-string returns", async () => {
    // Regression guard against an earlier revision that mapped `textContent === ""` to
    // `undefined` for the function form. `() => ""` (or a missing-DOM-text fallback in a
    // migrated Playwright script) legitimately returns the empty string; collapsing it to
    // `undefined` would break string comparisons and downstream string handling.
    const { createMockClient } = await import("./testing.js");
    const mock = createMockClient();
    mock.stub("web_evaluate", { textContent: "" });
    type ToolsAny = Record<string, (...a: unknown[]) => Promise<unknown>>;
    const tools = mock.tools as unknown as ToolsAny;
    const result = await tools.web_evaluate(() => "");
    expect(result).toBe("");
  });

  test("script-overload web_evaluate returns raw text for JSON-literal-looking strings", async () => {
    // Regression guard against an earlier JSON.parse heuristic that corrupted legitimate
    // string returns whose textual form happened to parse as JSON (`"42"` → `42`,
    // `"true"` → `true`, `"null"` → `null`). A function declared as `() => string` must
    // round-trip its string value verbatim — anything else is a silent data corruption
    // for callers reading localStorage strings, serialized payloads, etc.
    const { createMockClient } = await import("./testing.js");
    const mock = createMockClient();
    mock.stub("web_evaluate", { textContent: "42" });
    type ToolsAny = Record<string, (...a: unknown[]) => Promise<unknown>>;
    const tools = mock.tools as unknown as ToolsAny;
    const result = await tools.web_evaluate((): string => "42");
    expect(result).toBe("42");
    expect(typeof result).toBe("string");
  });

  test("script-overload web_evaluate prefers structuredContent over textContent", async () => {
    // When the producer DOES populate structuredContent (the typed-result future), it takes
    // precedence over textContent — locks the precedence so a future refactor that flips
    // the branch order doesn't silently change which payload wins. Same semantic as
    // `_unwrapToolResult`'s standard unwrap; pinned here at the script-overload boundary
    // because the script-overload branch routes through `_unwrapToolResult` and a future
    // refactor that special-cased text might bypass the structured branch.
    const { createMockClient } = await import("./testing.js");
    const mock = createMockClient();
    mock.stub("web_evaluate", {
      textContent: "text-distractor",
      structuredContent: { typedShape: true },
    });
    type ToolsAny = Record<string, (...a: unknown[]) => Promise<unknown>>;
    const tools = mock.tools as unknown as ToolsAny;
    const result = await tools.web_evaluate(() => ({ typedShape: true }));
    expect(result).toEqual({ typedShape: true });
  });

  test("falsy but non-null structuredContent values still unwrap as the typed result", () => {
    // A producer that genuinely wants to return `0` / `false` / `""` as the typed result
    // would set structuredContent to that primitive. The unwrap must NOT trip the "absent"
    // branch on a falsy-but-present value — only undefined/null mean "no structured payload".
    const zero: TrailblazeCallToolResult = {
      success: true,
      textContent: "",
      errorMessage: "",
      structuredContent: 0,
    };
    expect(_unwrapToolResult<number>(zero)).toBe(0);

    const falsy: TrailblazeCallToolResult = {
      success: true,
      textContent: "",
      errorMessage: "",
      structuredContent: false,
    };
    expect(_unwrapToolResult<boolean>(falsy)).toBe(false);

    const emptyString: TrailblazeCallToolResult = {
      success: true,
      textContent: "this is not the result",
      errorMessage: "",
      structuredContent: "",
    };
    // The structured payload is the empty string; the textContent is a distractor. Unwrap
    // returns the structured value, not textContent.
    expect(_unwrapToolResult<string>(emptyString)).toBe("");
  });
});
