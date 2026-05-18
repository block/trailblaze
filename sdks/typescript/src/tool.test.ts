// Regression tests for `registerPendingTools`'s handler-error envelope.
//
// What we're guarding against: an author handler that throws (sync or async-reject) used to
// let the throw escape the MCP SDK boundary, which wrapped it into a result envelope whose
// text content was just `error.message` — `error.stack` was lost on the way out. Authors
// debugging from session logs alone had no breadcrumb to the failing line.
//
// Post-fix, the `registerPendingTools` wrapper catches the throw and produces an `isError`
// envelope whose text content carries `name + ": " + message + "\n" + stack`. These tests
// pin that shape against three failure modes (sync throw, async rejection, primitive throw).

import { describe, expect, test, beforeEach } from "bun:test";

import { tool, registerPendingTools, _clearPendingTools } from "./tool.js";

// Minimal McpServer test double. We only assert on the handler the SDK was registered with,
// so we capture `(name, spec, handler)` from `registerTool` and call the handler directly
// with synthetic args + an `extra` shaped like the real MCP SDK threads it through.
function createCapturingServer() {
  const registered: Record<string, (args: Record<string, unknown>, extra: unknown) => unknown> = {};
  const server = {
    registerTool(
      name: string,
      _spec: unknown,
      handler: (args: Record<string, unknown>, extra: unknown) => unknown,
    ) {
      registered[name] = handler;
    },
  };
  return { server, registered };
}

beforeEach(() => {
  _clearPendingTools();
});

// Shape of the registered tool callback we invoke directly in tests.
type RegisteredHandler = (args: Record<string, unknown>, extra: unknown) => unknown;

// Result shape returned by the wrapped handler. Test-local because all assertions read
// the same `isError + content[0].text` slice.
type EnvelopeResult = {
  isError?: boolean;
  content: Array<{ type: string; text: string }>;
};

/**
 * Test fixture: register a tool, run `registerPendingTools` against a capturing server,
 * invoke the wrapped handler once, and return the result. Removes the boilerplate of
 * constructing the server + calling `registerPendingTools` + locating the registered
 * handler that every test would otherwise repeat. The handler type accepts both `async`
 * and non-`async` shapes (matching the SDK's `TrailblazeToolHandler` union) so a test that
 * wants a true sync throw can pass a non-async function.
 */
async function invokeTool(
  toolName: string,
  handler: (args: Record<string, unknown>, ctx: unknown, client: unknown) => unknown,
  invokeArgs: Record<string, unknown> = {},
  invokeExtra: unknown = {},
): Promise<EnvelopeResult> {
  // The SDK's `TrailblazeToolHandler` is wider than this test's narrowed `unknown` ctx /
  // client — the cast is safe because the catch path doesn't dereference either.
  tool(toolName, {}, handler as never);
  const { server, registered } = createCapturingServer();
  registerPendingTools(server as never);
  return (await registered[toolName](invokeArgs, invokeExtra)) as EnvelopeResult;
}

describe("registerPendingTools handler error envelope", () => {
  test("synchronous throw is captured into an isError envelope with name/message/stack", async () => {
    // Non-async handler so the throw is a TRUE sync throw rather than an async rejection.
    // Both paths must produce the same envelope, but pinning the sync-throw path
    // separately defends against a refactor that drops the synchronous try/catch leg.
    const result = await invokeTool("sync_thrower", () => {
      throw new Error("sync boom");
    });
    expect(result.isError).toBe(true);
    expect(result.content).toHaveLength(1);
    const text = result.content[0].text;
    // `Error.stack` in Node/bun already starts with `${name}: ${message}\n`, so using it
    // verbatim avoids the duplicated-header text Copilot review flagged. The envelope text
    // begins with the same prefix either way (constructed or stack-derived).
    expect(text.startsWith("Error: sync boom")).toBe(true);
    // Stack frame referencing this test file proves end-to-end stack preservation.
    expect(text).toContain("tool.test.ts");
    // Header should appear exactly once, not duplicated (Copilot inline review).
    const headerOccurrences = text.split("Error: sync boom").length - 1;
    expect(headerOccurrences).toBe(1);
  });

  test("async-reject from an async handler is captured the same way as a sync throw", async () => {
    const result = await invokeTool("async_rejecter", async () => {
      await Promise.resolve(); // suspend so the rejection lands in the next microtask
      throw new Error("async boom");
    });
    expect(result.isError).toBe(true);
    const text = result.content[0].text;
    expect(text).toContain("async boom");
    expect(text).toContain("tool.test.ts");
  });

  test("explicit Promise.reject from a non-async handler is captured the same way", async () => {
    // Different code path from `async () => { throw ... }` — the engine schedules the
    // rejection differently. Both shapes must produce the same envelope.
    const result = await invokeTool("explicit_rejecter", () => {
      return Promise.reject(new Error("explicit boom"));
    });
    expect(result.isError).toBe(true);
    const text = result.content[0].text;
    expect(text).toContain("explicit boom");
    expect(text).toContain("tool.test.ts");
  });

  test("non-Error throw (string, number) falls back to String()", async () => {
    const result = await invokeTool("primitive_thrower", async () => {
      // eslint-disable-next-line @typescript-eslint/no-throw-literal
      throw "raw string";
    });
    expect(result.isError).toBe(true);
    const text = result.content[0].text;
    // Primitive throws have no `.name` / `.message` / `.stack`; fallback prefix + String(e).
    expect(text).toContain("raw string");
    // Defense: no literal "undefined" leaked into the message because of missing fields.
    expect(text).not.toContain("undefined");
  });

  test.each([
    ["throw 0", 0, "0"],
    ["throw false", false, "false"],
    ["throw empty string", "", ""],
    ["throw null", null, "null"],
    ["throw undefined", undefined, "undefined"],
  ])("falsy throw %s surfaces as isError envelope (not silent success)", async (_label, thrown, expectedSubstring) => {
    // Sibling PR #2941's review surfaced a truthiness-check bug for the same class of
    // values. The TS version uses `instanceof Error` rather than `if (errorObj)`, so the
    // bug isn't present here — but pinning it defends against a future refactor that
    // re-introduces the same trap.
    const result = await invokeTool("falsy_thrower", () => {
      // eslint-disable-next-line @typescript-eslint/no-throw-literal
      throw thrown;
    });
    expect(result.isError).toBe(true);
    if (expectedSubstring.length > 0) {
      expect(result.content[0].text).toContain(expectedSubstring);
    }
  });

  test.each(["TypeError", "RangeError", "SyntaxError"])(
    "Error subtype %s name flows through the envelope prefix",
    async (subtype) => {
      // Defends `e.name` actually reaching the envelope vs always falling back to "Error".
      const ErrorClass = (globalThis as Record<string, unknown>)[subtype] as ErrorConstructor;
      const result = await invokeTool(`${subtype}_thrower`, () => {
        throw new ErrorClass("subtype failure");
      });
      expect(result.isError).toBe(true);
      expect(result.content[0].text.startsWith(`${subtype}: subtype failure`)).toBe(true);
    },
  );

  test("hostile extra.request.params._meta accessor throws are captured as isError", async () => {
    // `extractMeta` and `fromMeta` are themselves defensive (return `undefined` on bad
    // shapes rather than throwing), but the property-access chain `extra.request.params._meta`
    // is not — a Proxy with a throwing `get` trap on any link would propagate up. The catch
    // now wraps that whole chain (lead-dev review Finding #2), so even a hostile extra
    // surfaces as an `isError` envelope rather than escaping the MCP SDK boundary.
    const hostileExtra = new Proxy({} as Record<string, unknown>, {
      get() {
        throw new Error("extra-proxy sabotage");
      },
    });
    const result = await invokeTool(
      "extra_thrower",
      async () => ({ content: [{ type: "text", text: "should not reach here" }] }),
      {},
      hostileExtra,
    );
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("extra-proxy sabotage");
    expect(result.content[0].text).not.toContain("should not reach here");
  });

  test("handler returning undefined passes through as success (not error)", async () => {
    // Void-shaped tools — handlers that do work for side effects and don't return content.
    // Should NOT be classified as errors.
    const result = await invokeTool("void_handler", async () => undefined);
    expect(result?.isError).toBeFalsy();
  });

  test("handler returning hostile object whose toString throws still produces an envelope", async () => {
    // Defensive-stringify regression: a thrown object with a sabotaging `toString` would
    // crash the catch block's `String(e)` call and re-introduce the lost-envelope bug.
    // Mirrors `callTool handles thrown objects whose String() itself throws` in
    // `QuickJsToolHostTest.kt`.
    const result = await invokeTool("hostile_thrower", () => {
      // eslint-disable-next-line @typescript-eslint/no-throw-literal
      throw {
        toString() {
          throw new Error("toString sabotage");
        },
      };
    });
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("unstringifiable thrown value");
  });

  test("very long Error.stack is truncated to bound session-log size", async () => {
    // `MAX_STACK_LENGTH` cap defends against deep async chains producing >16 KB stacks
    // that bloat `*ToolLog.json` and CI report artifacts.
    const result = await invokeTool("deep_stack_thrower", () => {
      const e = new Error("boom");
      // Synthesize a stack longer than MAX_STACK_LENGTH (16 KB) so the truncation path fires.
      const longFrame = "    at fake (synthetic.ts:1:1)\n".repeat(1000);
      e.stack = `Error: boom\n${longFrame}`;
      throw e;
    });
    expect(result.isError).toBe(true);
    const text = result.content[0].text;
    expect(text.length).toBeLessThanOrEqual(16_384 + "\n...[stack truncated]".length + 1);
    expect(text).toContain("...[stack truncated]");
  });

  test("QuickJS-style frames-only Error.stack still produces a header line", async () => {
    // QuickJS's `Error.stack` is frames-only — no `${name}: ${message}\n` header. On the
    // on-device bundle runtime this catch runs inside QuickJS, so a verbatim-stack envelope
    // would drop the error message entirely (just `  at ...` frames reach the
    // session log). The catch block constructs the header explicitly and appends the stack
    // so the message survives on both engines. Without this pin, a refactor that goes back
    // to `envelopeText = stack` would silently re-introduce the on-device gap that
    // `RealSdkBundleHandlerThrowTest` (in `:trailblaze-scripting-bundle:jvmTest`) exists to
    // catch end-to-end.
    const result = await invokeTool("quickjs_style_thrower", () => {
      const e = new Error("on-device boom");
      // Frames-only — matches QuickJS-NG's `Error.stack` shape.
      e.stack = "    at <anonymous> (some-bundle.js:42:7)";
      throw e;
    });
    expect(result.isError).toBe(true);
    const text = result.content[0].text;
    expect(text.startsWith("Error: on-device boom")).toBe(true);
    // Stack frames must still appear after the header.
    expect(text).toContain("some-bundle.js:42:7");
    // Header appears exactly once (not duplicated even though the stack happens to lack one).
    const headerOccurrences = text.split("Error: on-device boom").length - 1;
    expect(headerOccurrences).toBe(1);
  });

  test("empty-message Error does not produce a duplicated header block", async () => {
    // V8/bun omits the `: ` separator when message is empty — `new Error("").stack` starts
    // with `"Error\n  at ..."`, not `"Error: \n  at ..."`. A strict-prefix-match against
    // the constructed `"Error: "` header would fail to strip the engine's bare-name line,
    // producing `"Error: \nError\n  at ..."` (two near-duplicate headers). Pin the
    // empty-message-aware strip path.
    const result = await invokeTool("empty_message_thrower", () => {
      throw new Error("");
    });
    expect(result.isError).toBe(true);
    const text = result.content[0].text;
    // Exactly one header line. The engine's bare-name `Error` line must be stripped.
    const lines = text.split("\n");
    expect(lines[0]).toBe("Error: ");
    // No bare `Error` line immediately following — that would be the duplicated-header
    // regression.
    expect(lines[1]).not.toBe("Error");
    // Stack frames must still appear after the single header.
    expect(text).toContain("tool.test.ts");
  });

  test("successful handler return passes through unchanged", async () => {
    const result = await invokeTool("happy_path", async () => ({
      content: [{ type: "text", text: "ok" }],
    }));
    expect(result.isError).toBeUndefined();
    expect(result.content[0].text).toBe("ok");
  });
});
