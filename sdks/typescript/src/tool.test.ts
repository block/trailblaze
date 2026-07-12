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
import { z } from "zod";

import {
  tool,
  registerPendingTools,
  _clearPendingTools,
  type TrailblazeTypedToolSpec,
} from "./tool.js";
import { createMemory, DRAIN_DELTA, type DrainableMemory, type TrailblazeMemory } from "./memory.js";

// Minimal McpServer test double. We only assert on the handler the SDK was registered with,
// so we capture `(name, spec, handler)` from `registerTool` and call the handler directly
// with synthetic args + an `extra` shaped like the real MCP SDK threads it through.
function createCapturingServer() {
  const registered: Record<string, (args: Record<string, unknown>, extra: unknown) => unknown> = {};
  // Captures every `sendLoggingMessage(...)` call the dispatch path makes via
  // `createLogger` — used by validation tests to assert `logger.warn(...)` was
  // invoked alongside the isError envelope. The stub still returns a resolved
  // Promise so the logger's `.catch` doesn't fire.
  const loggingMessages: Array<{ level: string; data: unknown; logger: string }> = [];
  const server = {
    registerTool(
      name: string,
      _spec: unknown,
      handler: (args: Record<string, unknown>, extra: unknown) => unknown,
    ) {
      registered[name] = handler;
    },
    // Stub for the structured-log path. The real MCP SDK's `McpServer` exposes
    // `sendLoggingMessage` (the wire emitter for `notifications/message`).
    // `createLogger` invokes it on every `logger.warn(...)` / `info(...)` call
    // and `.catch`es the returned promise. The catch only protects against an
    // async rejection — a missing method throws a synchronous `TypeError` that
    // escapes the dispatch path entirely. Capture each call so tests can pin
    // logger.warn behavior; resolve to undefined so the logger sees success.
    sendLoggingMessage: async (payload: { level: string; data: unknown; logger: string }) => {
      loggingMessages.push(payload);
      return undefined;
    },
  };
  return { server, registered, loggingMessages };
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
  spec: Record<string, unknown> = {},
): Promise<EnvelopeResult> {
  // The SDK's `TrailblazeToolHandler` is wider than this test's narrowed `unknown` ctx /
  // client — the cast is safe because the catch path doesn't dereference either.
  // `spec` is widened to `Record<string, unknown>` so the ajv-validation tests can
  // pass a zod raw shape under `spec.inputSchema` without forcing every existing
  // call site to import the typed `TrailblazeToolSpec`.
  tool(toolName, spec as never, handler as never);
  const { server, registered } = createCapturingServer();
  registerPendingTools(server as never);
  return (await registered[toolName](invokeArgs, invokeExtra)) as EnvelopeResult;
}

/**
 * Same as [invokeTool] but ALSO returns the captured `sendLoggingMessage` payloads so
 * tests can assert that `logger.warn(...)` was invoked alongside the isError envelope.
 * Kept distinct from [invokeTool] so the existing throw-envelope tests don't get a
 * tuple back they don't care about.
 */
async function invokeToolWithLogCapture(
  toolName: string,
  handler: (args: Record<string, unknown>, ctx: unknown, client: unknown) => unknown,
  invokeArgs: Record<string, unknown> = {},
  invokeExtra: unknown = {},
  spec: Record<string, unknown> = {},
): Promise<{ result: EnvelopeResult; loggingMessages: Array<{ level: string; data: unknown; logger: string }> }> {
  tool(toolName, spec as never, handler as never);
  const { server, registered, loggingMessages } = createCapturingServer();
  registerPendingTools(server as never);
  const result = (await registered[toolName](invokeArgs, invokeExtra)) as EnvelopeResult;
  return { result, loggingMessages };
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

describe("tool() overload — typed authoring surface", () => {
  // The `tool()` export is overloaded at compile time: 3-arg `(name, spec, handler)` is the
  // imperative MCP-registration form (returns void, side-effects pendingTools), and 1-arg
  // `<I, O>(handler)` is the typed declarative form (returns the handler, no-op runtime).
  // These tests pin the runtime dispatch for both branches.

  test("typed tool<I, O>(handler) returns a 3-arg adapter and does NOT queue a pending registration", async () => {
    interface Input { x: string }
    interface Output { y: number }
    let observedInput: Input | null = null;
    let observedCtxShape: string[] | null = null;
    const handler = async (input: Input, ctx: { tools: Record<string, unknown> }): Promise<Output> => {
      observedInput = input;
      observedCtxShape = Object.keys(ctx);
      return { y: input.x.length };
    };

    const definition = tool<Input, Output>(handler);

    // Adapter shape: the typed overload returns a 3-arg function so the existing
    // synthesized wrapper (which always invokes `(args, ctx, client)`) reaches the
    // typed handler. The author's `(input, ToolContext)` shape is what runs inside.
    expect(typeof definition).toBe("function");
    expect(definition.length).toBe(3);

    // The adapter forwards `args` straight through as `input` and constructs a
    // `ToolContext` ({ tools }) from the legacy client's `client.tools` proxy.
    const fakeTools = { fakeTool: async () => "fake" };
    const fakeClient = { tools: fakeTools } as never;
    const result = await definition({ x: "hello" }, undefined, fakeClient);
    expect(result).toEqual({ y: 5 });
    expect(observedInput).toEqual({ x: "hello" });
    // ToolContext exposes `tools` (compose primitive), `memory` (per-invocation
    // memory surface from #3361), `device` (connected-device descriptor forwarded
    // from `legacyCtx.device`; undefined when no envelope was set, as here), and
    // `target` (resolved-target descriptor forwarded from `legacyCtx.target`;
    // likewise undefined here). Order matches the order the adapter constructs the
    // object literal in `defineTypedTool` — the test pins all four keys present
    // (an explicitly-set key is enumerable even when its value is `undefined`).
    expect(observedCtxShape).toEqual(["tools", "memory", "device", "target"]);

    // Nothing was queued for MCP registration — the typed form is declarative, not imperative.
    // Verify by running registerPendingTools and asserting the capturing server saw no tools.
    const { server, registered } = createCapturingServer();
    registerPendingTools(server as never);
    expect(Object.keys(registered)).toHaveLength(0);
  });

  test("typed handler can compose other tools via ctx.tools.X — bridging legacy client.tools", async () => {
    // Pin the typed handler's `ctx.tools.X(args)` namespace identity with the legacy
    // `client.tools.X(args)` it bridges from. This is the load-bearing contract for
    // migrated tools (e.g. `contacts_ios_searchContacts`) whose handler body calls
    // `ctx.tools.tapOnElementWithText(...)` and expects it to dispatch identically.
    interface Input { count: number }
    const calls: Array<{ name: string; args: unknown }> = [];
    const handler = async (input: Input, ctx: { tools: Record<string, (a: unknown) => Promise<unknown>> }): Promise<string> => {
      for (let i = 0; i < input.count; i++) {
        await ctx.tools.tapOnElementWithText({ text: `row-${i}` });
      }
      return `tapped ${input.count} rows`;
    };

    const definition = tool<Input, string>(handler);

    const fakeTools = new Proxy(
      {},
      {
        get(_target, name: string) {
          return async (args: unknown) => {
            calls.push({ name, args });
            return undefined;
          };
        },
      },
    ) as Record<string, (a: unknown) => Promise<unknown>>;
    const fakeClient = { tools: fakeTools } as never;

    const result = await definition({ count: 3 }, undefined, fakeClient);
    expect(result).toBe("tapped 3 rows");
    expect(calls.map((c) => c.name)).toEqual([
      "tapOnElementWithText",
      "tapOnElementWithText",
      "tapOnElementWithText",
    ]);
    expect(calls.map((c) => (c.args as { text: string }).text)).toEqual([
      "row-0",
      "row-1",
      "row-2",
    ]);
  });

  test("typed handler receives ctx.target forwarded from legacyCtx.target", async () => {
    // Pin the contract documented on `defineTypedTool` — `target` rides through from
    // the legacy ctx untouched, so a typed handler sees the same
    // `ctx.target?.resolveAppId(...)` surface a `TrailblazeContext`-shaped handler
    // would. The `resolveAppId` / `resolveBaseUrl` method bindings are injected by
    // `fromMeta` on the producer side; the adapter's only job is the forward.
    let observedTarget: unknown = "<unset>";
    const handler = async (_input: { x: string }, ctx: { target?: unknown }): Promise<string> => {
      observedTarget = ctx.target;
      return "ok";
    };
    const definition = tool<{ x: string }>(handler);

    const fakeTarget = {
      id: "wikipedia",
      appIds: ["org.wikipedia"],
      appId: "org.wikipedia",
      resolveAppId: () => "org.wikipedia",
      resolveBaseUrl: () => "https://en.wikipedia.org",
    };
    const legacyCtx = { target: fakeTarget } as never;
    const fakeClient = { tools: {} } as never;
    await definition({ x: "hi" }, legacyCtx, fakeClient);
    expect(observedTarget).toBe(fakeTarget);

    // Undefined `legacyCtx.target` (e.g. session without a target configured)
    // propagates as `undefined` — handlers MUST optional-chain.
    observedTarget = "<unset>";
    await definition({ x: "hi" }, { target: undefined } as never, fakeClient);
    expect(observedTarget).toBeUndefined();

    // Entirely-undefined legacyCtx (unit-test fixture, bundle path with no
    // envelope) is the most defensive case — target falls back to undefined
    // via the `legacyCtx?.target` chain.
    observedTarget = "<unset>";
    await definition({ x: "hi" }, undefined, fakeClient);
    expect(observedTarget).toBeUndefined();
  });

  test("typed handler receives canonical device.driverType when legacy ctx only has driver alias", async () => {
    let observedDriverType: string | undefined;
    const handler = async (
      _input: { x: string },
      ctx: { device?: { driverType: string; driver?: string } },
    ): Promise<string> => {
      observedDriverType = ctx.device?.driverType;
      return "ok";
    };
    const definition = tool<{ x: string }>(handler);

    await definition(
      { x: "hi" },
      {
        device: {
          platform: "android",
          widthPixels: 1080,
          heightPixels: 2400,
          driver: "android-ondevice-accessibility",
        },
      } as never,
      { tools: {} } as never,
    );

    expect(observedDriverType).toBe("android-ondevice-accessibility");
  });

  test("typed handler ctx.memory: raw on-device snapshot is wrapped, subprocess TrailblazeMemory passes through", async () => {
    // The adapter accepts `legacyCtx.memory` in two shapes:
    //   • On-device QuickJS path — a raw `Record<string,string>` (the Kotlin
    //     `QuickJsToolCtxEnvelope.memory` snapshot, plain data, no methods). The adapter
    //     wraps it via `createMemory` so the handler still gets the full surface.
    //   • Subprocess path — an already-built `TrailblazeMemory` (from `fromMeta`), which
    //     must pass through identically (the discriminator is a function-valued
    //     `interpolate`).
    let observedMemory: TrailblazeMemory | undefined;
    const handler = async (_input: { x: string }, ctx: { memory: TrailblazeMemory }): Promise<string> => {
      observedMemory = ctx.memory;
      return "ok";
    };
    const definition = tool<{ x: string }>(handler);
    const fakeClient = { tools: {} } as never;

    // On-device shape: raw record gets wrapped into a working memory surface.
    await definition(
      { x: "hi" },
      { memory: { greeting: "hello", name: "Ada" } } as never,
      fakeClient,
    );
    expect(observedMemory?.get("greeting")).toBe("hello");
    expect(observedMemory?.has("name")).toBe(true);
    expect(observedMemory?.interpolate("{{greeting}} {{name}}")).toBe("hello Ada");

    // Subprocess shape: a real TrailblazeMemory rides through unchanged (identity).
    const built = createMemory({ token: "xyz" });
    await definition({ x: "hi" }, { memory: built } as never, fakeClient);
    expect(observedMemory).toBe(built);

    // No memory on the envelope (older daemon / unit fixture) → empty, never throws.
    await definition({ x: "hi" }, {} as never, fakeClient);
    expect(observedMemory?.get("anything")).toBeUndefined();
    expect(observedMemory?.interpolate("{{missingToken}}")).toBe("{{missingToken}}");
  });

  test("typed handler flushes ctx.memory writes to _meta.trailblaze.memoryDelta on the in-process (raw snapshot) path", async () => {
    // The on-device / in-process QuickJS path hands the adapter a raw `Record<string,string>`
    // snapshot and has NO external `attachMemoryDelta` wrapper. So the adapter itself must stamp the
    // handler's `ctx.memory.set(...)` writes onto the result's `_meta.trailblaze.memoryDelta` — the
    // write-flush the QuickJS path silently dropped before the fix (write-then-read hand-off between two scripted tools).
    const definition = tool<{ token: string }>(async (input, ctx) => {
      ctx.memory.set("session_token", input.token);
      return "stored";
    });
    const out = (await definition(
      { token: "tok-9" },
      { memory: { existing: "v" } } as never,
      { tools: {} } as never,
    )) as { content: unknown[]; _meta: { trailblaze: { memoryDelta: Record<string, string> } } };
    expect(out._meta.trailblaze.memoryDelta).toEqual({ session_token: "tok-9" });
    expect(out.content).toEqual([{ type: "text", text: "stored" }]);
  });

  // Local re-implementation of the synthesized in-process wrapper's `__normalizeResult`
  // (`sdks/typescript/tools/in-process-wrapper-template.mjs`) — kept minimal and inline rather than
  // importing the .mjs template (it's a build-time text template, not an importable module). Proves
  // that whatever `defineTypedTool` returns survives the SAME normalization step production applies
  // before the Kotlin host ever sees the envelope.
  function normalizeResultLikeInProcessWrapper(result: unknown): { content: unknown[]; _meta?: unknown } {
    if (result == null) return { content: [] };
    if (typeof result === "object" && Array.isArray((result as { content?: unknown }).content)) {
      return result as { content: unknown[]; _meta?: unknown };
    }
    if (typeof result === "string") return { content: [{ type: "text", text: result }] };
    return { content: [{ type: "text", text: JSON.stringify(result) }] };
  }

  test("typed handler returning a plain object still flushes the delta through the in-process wrapper's normalization", async () => {
    // Code-review regression: a typed tool's `TResult` can be any author-declared object (e.g.
    // `{ ok: true }`), not just a string. The FIRST cut of the fix bolted `_meta` onto that bare
    // object, but the synthesized in-process wrapper's `__normalizeResult` only passes an object
    // through untouched when it ALREADY has a `content` array — a bare `{ ok: true, _meta: {...} }`
    // would hit its `JSON.stringify` fallback and lose `_meta` (and the memoryDelta inside it)
    // entirely. `attachMemoryDeltaToResult` must wrap a bare object the same way, so the delta
    // survives the downstream normalization pass unchanged.
    interface Output { ok: boolean }
    const definition = tool<{ token: string }, Output>(async (input, ctx) => {
      ctx.memory.set("session_token", input.token);
      return { ok: true };
    });
    const out = await definition({ token: "tok-9" }, { memory: {} } as never, { tools: {} } as never);

    // The adapter's own output must already carry a `content` array (not a bare `{ ok, _meta }`).
    expect(Array.isArray((out as { content?: unknown }).content)).toBe(true);

    // Simulate what production actually does next: hand this to the in-process wrapper's
    // normalizer. It must pass through unchanged (the `Array.isArray(content)` branch), preserving
    // `_meta.trailblaze.memoryDelta` for the Kotlin host to apply.
    const normalized = normalizeResultLikeInProcessWrapper(out) as {
      _meta: { trailblaze: { memoryDelta: Record<string, string> } };
      structuredContent?: Output;
    };
    expect(normalized._meta.trailblaze.memoryDelta).toEqual({ session_token: "tok-9" });
    // The typed value itself must still be recoverable by a composing caller.
    expect(normalized.structuredContent).toEqual({ ok: true });
  });

  test("typed handler returning an object with NO memory writes is left byte-for-byte unchanged", async () => {
    // Negative companion: attachMemoryDeltaToResult must not touch a write-free tool's return value
    // at all — its shape (bare object, no `content`) is exactly what the downstream normalizer
    // already knows how to handle, so re-wrapping it here would be an unnecessary behavior change.
    interface Output { ok: boolean }
    const definition = tool<Record<string, never>, Output>(async (_input, _ctx) => ({ ok: true }));
    const out = await definition({}, { memory: {} } as never, { tools: {} } as never);
    expect(out).toEqual({ ok: true });
  });

  test("typed handler does NOT attach a delta on the subprocess path (already-wrapped TrailblazeMemory)", async () => {
    // When memory arrives already wrapped (the subprocess/MCP `fromMeta` path), the surrounding
    // `attachMemoryDelta` in tool.ts owns the flush. The adapter must return the raw handler value
    // untouched — otherwise the two paths would both wrap. Pins the `memoryIsWrapped` gate: the
    // write is still buffered on the shared memory for the external drain to pick up.
    const wrapped = createMemory({});
    const definition = tool<{ token: string }>(async (input, ctx) => {
      ctx.memory.set("session_token", input.token);
      return "stored";
    });
    const out = await definition({ token: "tok-9" }, { memory: wrapped } as never, { tools: {} } as never);
    expect(out).toBe("stored");
    expect((wrapped as unknown as DrainableMemory)[DRAIN_DELTA]().sets).toEqual({
      session_token: "tok-9",
    });
  });

  test("imperative tool(name, spec, handler) queues a pending registration", () => {
    const handler = async () => ({ content: [{ type: "text" as const, text: "ok" }] });
    tool("imperative_tool", {}, handler);

    const { server, registered } = createCapturingServer();
    registerPendingTools(server as never);

    expect(Object.keys(registered)).toEqual(["imperative_tool"]);
  });

  test("typed tool(handler) — BARE FUNCTION form — registers and dispatches", async () => {
    // The canonical authoring shape. Author passes the async function directly; SDK
    // detects it via `typeof === "function"` and wraps it in a 3-arg adapter that
    // bridges `(input, ToolContext)` semantics onto the synthesized-wrapper's
    // `(args, ctx, client)` call shape.
    interface Input { x: string }
    interface Output { y: number }
    const observed: { input?: Input; ctxKeys?: string[] } = {};
    const definition = tool<Input, Output>(async (input, ctx) => {
      observed.input = input;
      observed.ctxKeys = Object.keys(ctx);
      return { y: input.x.length };
    });
    expect(typeof definition).toBe("function");
    expect(definition.length).toBe(3);
    const result = await definition({ x: "hello" }, undefined, { tools: {} } as never);
    expect(result).toEqual({ y: 5 });
    expect(observed.input).toEqual({ x: "hello" });
    expect(observed.ctxKeys).toEqual(["tools", "memory", "device", "target"]);
  });

  test("typed tool(handler) with NO type arguments — bare function + defaults", async () => {
    // The simplest possible tool: bare async returning a string. `<{}, string>`
    // defaults from the SDK + the bare-function form give the lightest authoring
    // shape end-to-end.
    const definition = tool(async () => "pong");
    const result = await definition({} as never, undefined, { tools: {} } as never);
    expect(result).toBe("pong");
  });

  test("typed tool<I>(handler) with ONE type argument defaults TResult to string", async () => {
    // Single-generic path: author writes `<MyInput>` for the input shape, TResult
    // defaults to `string`. Pin that the adapter forwards the typed input AND returns
    // the string result.
    interface GreetInput { name: string }
    const definition = tool<GreetInput>(async (input) => `hi ${input.name}`);
    const result = await definition({ name: "Ada" }, undefined, { tools: {} } as never);
    expect(result).toBe("hi Ada");
  });

  test("typed tool accepts a sync handler returning a raw value (not just async/Promise)", async () => {
    // The typed `handler` signature is `(input, ctx) => Promise<TResult>`, but the
    // declarative adapter just awaits whatever the handler returns. A sync
    // `() => value` happens to satisfy `Promise<TResult>` via the adapter's `await`
    // — pin that contract so a sync handler isn't spuriously rejected.
    interface Input { x: string }
    interface Output { length: number }
    const syncHandler = ((input: Input): Output => ({ length: input.x.length })) as never;
    const definition = tool<Input, Output>(syncHandler);
    const result = await definition({ x: "abc" }, undefined, { tools: {} } as never);
    expect(result).toEqual({ length: 3 });
  });

  test("typed tool<{}, {}>(handler) with empty interface generics returns a callable adapter", async () => {
    // No-arg / no-result typed tool — the boundary case the analyzer hits when an
    // author declares `trailblaze.tool<{}, {}>(async () => ({}))`. Pin that the
    // empty-interface generics are accepted and the returned adapter is invokable
    // through the legacy 3-arg call shape.
    const handler = async (_input: Record<string, never>): Promise<Record<string, never>> =>
      ({} as Record<string, never>);
    const definition = tool<Record<string, never>, Record<string, never>>(handler);
    const result = await definition({} as never, undefined, { tools: {} } as never);
    expect(result).toEqual({});
  });

  test("typed tool({}) — object first arg, no handler — falls through to the imperative path", () => {
    // `tool({})` with a SINGLE arg has no function second-arg, so the typed-with-spec
    // dispatch (which requires `typeof arg1 === "function"`) doesn't fire — the call
    // falls through to the imperative branch's `pendingTools.push`, which surfaces
    // later as an MCP registration error with the actual first-arg value. The
    // two-arg `tool({}, handler)` form is covered separately by the "typed with-spec"
    // suite below and routes to the typed branch.
    expect(() => tool({} as never)).not.toThrow();
  });

  test("typed tool(null) — null first arg — falls through to the imperative path", () => {
    // `null` isn't a function, so it doesn't enter the typed branch. Falls through to
    // imperative `pendingTools.push` with a name of `null`, which is caught downstream
    // at MCP registration time. The dispatch itself doesn't throw.
    expect(() => tool(null as never, {}, async () => ({ content: [] }))).not.toThrow();
  });

  test("typed tool([]) — array first arg — falls through to the imperative path", () => {
    // Arrays are `typeof === "object"` in JS, but the typed-with-spec dispatch
    // explicitly rejects arrays via `!Array.isArray(arg0)`. An array first arg
    // falls through to imperative — pin that this doesn't accidentally route to
    // the new typed-with-spec branch and trigger a typed-path error.
    expect(() => tool([] as never, {}, async () => ({ content: [] }))).not.toThrow();
  });
});

describe("tool() overload — typed with-spec authoring surface", () => {
  // The typed `tool<I, O>(spec, handler)` overload is the destination shape for
  // YAML-free authoring (#3352). Authors set structured config (supportedPlatforms,
  // requiresContext, requiresHost, supportedDrivers) on the spec object; the
  // build-time analyzer extracts those from the AST and synthesizes the runtime
  // `_meta` for MCP advertisement. At runtime the spec is discarded — these tests
  // pin (a) the dispatch routes correctly, (b) the handler runs as if no spec
  // were passed, and (c) the dedup pattern (factory helper wrapping
  // `trailblaze.tool`) compiles and runs end-to-end.

  test("tool<I, O>(spec, handler) returns a 3-arg adapter and does NOT queue a pending registration", async () => {
    interface Input { x: string }
    interface Output { y: number }
    const definition = tool<Input, Output>(
      { supportedPlatforms: ["web"], requiresContext: true },
      async (input) => ({ y: input.x.length }),
    );

    // Same adapter shape as the bare-handler form — the spec is invisible at
    // runtime, so a typed-with-spec definition is indistinguishable from a
    // typed bare-handler definition once returned.
    expect(typeof definition).toBe("function");
    expect(definition.length).toBe(3);
    const result = await definition({ x: "hello" }, undefined, { tools: {} } as never);
    expect(result).toEqual({ y: 5 });

    // Verify nothing was queued for MCP registration — the typed forms (both
    // bare-handler and with-spec) are declarative, not imperative.
    const { server, registered } = createCapturingServer();
    registerPendingTools(server as never);
    expect(Object.keys(registered)).toHaveLength(0);
  });

  test("tool<I, O>(spec, handler) — empty spec object — routes to typed branch (not imperative)", async () => {
    // `tool({}, handler)` is the "I want the typed surface but have no metadata to
    // declare" case. The dispatch must route to the typed-with-spec branch, NOT
    // fall through to imperative (which would treat `{}` as a tool name and
    // queue a misshapen registration). Pin the dispatch boundary.
    interface Input { x: string }
    const definition = tool<Input, string>(
      {},
      async (input) => `ok ${input.x}`,
    );
    expect(typeof definition).toBe("function");
    expect(definition.length).toBe(3);
    const result = await definition({ x: "hi" }, undefined, { tools: {} } as never);
    expect(result).toBe("ok hi");

    // Nothing queued for MCP registration.
    const { server, registered } = createCapturingServer();
    registerPendingTools(server as never);
    expect(Object.keys(registered)).toHaveLength(0);
  });

  test("tool<I, O>(spec, handler) handler still receives the typed ctx.tools namespace", async () => {
    // The with-spec form goes through the same `defineTypedTool` adapter as the
    // bare-handler form, so `ctx.tools.X(args)` composition must work identically.
    // Pin that the spec doesn't accidentally short-circuit the ToolContext
    // construction.
    interface Input { count: number }
    const calls: string[] = [];
    const definition = tool<Input, string>(
      { supportedPlatforms: ["web"] },
      async (input, ctx) => {
        for (let i = 0; i < input.count; i++) {
          await (ctx.tools as Record<string, (a: unknown) => Promise<unknown>>).tap(
            { i },
          );
        }
        return `tapped ${input.count}`;
      },
    );

    const fakeTools = new Proxy(
      {},
      {
        get(_target, name: string) {
          return async () => {
            calls.push(name);
          };
        },
      },
    );
    const fakeClient = { tools: fakeTools } as never;
    const result = await definition({ count: 2 }, undefined, fakeClient);
    expect(result).toBe("tapped 2");
    expect(calls).toEqual(["tap", "tap"]);
  });

  test("factory helper wrapping tool<I, O>(spec, handler) — the dedup pattern from #3352", async () => {
    // The load-bearing test for the issue's design: an author writes one helper
    // function in their trailmap's shared module that pre-fills common spec defaults
    // via spread, and every tool file imports it. This proves the spread / partial
    // pattern compiles and runs end-to-end through the typed-with-spec dispatch.
    //
    // The wikipedia trailmap's eventual `webTool` helper looks exactly like this.
    function webTool<I, O>(
      spec: Partial<TrailblazeTypedToolSpec>,
      handler: (input: I, ctx: { tools: Record<string, unknown> }) => Promise<O>,
    ) {
      return tool<I, O>(
        { supportedPlatforms: ["web"], requiresContext: true, ...spec },
        handler,
      );
    }

    interface Input { name: string }
    const definition = webTool<Input, string>(
      {},
      async (input) => `hello ${input.name}`,
    );
    const result = await definition({ name: "Ada" }, undefined, { tools: {} } as never);
    expect(result).toBe("hello Ada");
  });

  test("factory helper can override individual spec fields via spread", async () => {
    // The Partial<spec> pattern lets each tool file selectively override fields
    // its factory pre-filled. Pin that override semantics work (last-write-wins
    // via spread), which is how a "web trailmap" tool can opt out of requiresContext
    // for a specific tool without restating the whole spec.
    function webTool<I, O>(
      spec: Partial<TrailblazeTypedToolSpec>,
      handler: (input: I, ctx: { tools: Record<string, unknown> }) => Promise<O>,
    ) {
      return tool<I, O>(
        { supportedPlatforms: ["web"], requiresContext: true, ...spec },
        handler,
      );
    }

    // Override `requiresContext` to false on a single tool; verify the call still
    // produces a working definition (spec is discarded at runtime, but the
    // typed-with-spec dispatch must still route correctly).
    const definition = webTool<{ x: number }, number>(
      { requiresContext: false },
      async (input) => input.x * 2,
    );
    const result = await definition({ x: 21 }, undefined, { tools: {} } as never);
    expect(result).toBe(42);
  });

  test("tool(plainObject, function, extraArg) — 3-arg shape — falls through to imperative (not typed-with-spec)", () => {
    // Defends against accidental routing of ill-shaped 3-arg imperative calls
    // through the typed-with-spec branch. Without the explicit `arg2 === undefined`
    // guard, a malformed call like `tool({...}, async () => {...}, anything)` would
    // pattern-match `(plainObject, function)` and silently return a typed
    // definition that never registers — the tool would just disappear. Pin that
    // the 3-arg shape always reaches the imperative branch where it surfaces a
    // diagnostic at registration time (or is treated as a misshapen tool name).
    const handler = (async () => ({ content: [{ type: "text" as const, text: "ok" }] }));
    expect(() =>
      tool(
        { fakeNameField: "evil" } as never,
        handler as never,
        undefined as never,
      ),
    ).not.toThrow();
    // Reproduce the exact misroute case Copilot flagged: 3 args where arg0 is
    // an object and arg1 is a function. Without the guard, this returns a typed
    // definition (a function) — with the guard, it falls through to imperative
    // (which is `pendingTools.push(...)`, returning undefined).
    const result = tool(
      { fakeNameField: "evil" } as never,
      handler as never,
      handler as never,
    );
    expect(result).toBeUndefined();
  });

  test("tool<I, O>(spec, handler) is interchangeable with tool<I, O>(handler) at the call site", async () => {
    // Both typed overloads return the same `TypedToolDefinition<I, O>` shape, so
    // a function that accepts either form (e.g. a test harness, or a runtime that
    // iterates definitions) doesn't need to branch on which overload was used.
    // Pin that the two returned shapes are structurally identical.
    interface Input { v: number }
    const bare = tool<Input, number>(async (input) => input.v + 1);
    const withSpec = tool<Input, number>(
      { requiresContext: true },
      async (input) => input.v + 1,
    );
    expect(typeof bare).toBe("function");
    expect(typeof withSpec).toBe("function");
    expect(bare.length).toBe(withSpec.length);

    const fakeClient = { tools: {} } as never;
    const bareResult = await bare({ v: 1 }, undefined, fakeClient);
    const withSpecResult = await withSpec({ v: 1 }, undefined, fakeClient);
    expect(bareResult).toBe(withSpecResult);
  });
});

describe("ajv input validation at the dispatch boundary", () => {
  // Validation is wired at both dispatch points:
  //   - Imperative `server.registerTool` callback — schema is the zod-shaped
  //     `spec.inputSchema`, lowered to JSON Schema via `z.toJSONSchema` then
  //     compiled with ajv at registration time.
  //   - Typed `defineTypedTool` adapter — schema is the optional
  //     `TrailblazeTypedToolSpec.inputSchema` JSON Schema literal carried by the
  //     `tool<I, O>(spec, handler)` overload.
  // Both paths catch the same three failure modes (wrong-type, wrong-type-bool,
  // missing-required) and hard-fail with an `isError: true` envelope so the LLM
  // sees a field-level diagnostic to self-correct against.

  describe("imperative tool(name, spec, handler) — zod inputSchema", () => {
    test("happy path: valid args pass through to the handler", async () => {
      let receivedArgs: Record<string, unknown> | null = null;
      const result = await invokeTool(
        "search",
        async (args) => {
          receivedArgs = args;
          return { content: [{ type: "text", text: "ok" }] };
        },
        { query: "wikipedia", openFirstResult: true },
        {},
        // Spec carries a zod raw shape that mirrors the wikipedia trailmap's
        // `searchAndOpenFirstResult` failure scenarios verbatim.
        { inputSchema: { query: z.string(), openFirstResult: z.boolean() } },
      );
      expect(result.isError).toBeFalsy();
      expect(receivedArgs).toEqual({ query: "wikipedia", openFirstResult: true });
    });

    test("number where string is required surfaces a field-level isError envelope", async () => {
      // Failure mode #1 from the wikipedia trailmap: handler crashes deep inside the
      // body with `Cannot read properties of undefined (reading 'toLowerCase')`.
      // ajv at the boundary catches it before the handler runs.
      let handlerRan = false;
      const result = await invokeTool(
        "search",
        async () => {
          handlerRan = true;
          return { content: [{ type: "text", text: "should not reach" }] };
        },
        { query: 42 },
        {},
        { inputSchema: { query: z.string() } },
      );
      expect(result.isError).toBe(true);
      expect(handlerRan).toBe(false);
      const text = result.content[0].text;
      expect(text).toContain("ValidationError");
      expect(text).toContain("/query");
      expect(text).toContain("must be string");
    });

    test("string where boolean is required catches the truthy-string coercion bug", async () => {
      // Failure mode #2 from the wikipedia trailmap: `{ openFirstResult: "false" }`
      // (string) is truthy in JS, so `input.openFirstResult !== false` evaluates
      // `true` and submits the search — the opposite of what the LLM said. ajv's
      // strict boolean check rejects strings before the comparison runs.
      let handlerRan = false;
      const result = await invokeTool(
        "search",
        async () => {
          handlerRan = true;
          return { content: [{ type: "text", text: "should not reach" }] };
        },
        { query: "wikipedia", openFirstResult: "false" },
        {},
        { inputSchema: { query: z.string(), openFirstResult: z.boolean() } },
      );
      expect(result.isError).toBe(true);
      expect(handlerRan).toBe(false);
      const text = result.content[0].text;
      expect(text).toContain("/openFirstResult");
      expect(text).toContain("must be boolean");
    });

    test("missing required field surfaces before the handler's default masks it", async () => {
      // Failure mode #3 from the wikipedia trailmap: tool has a `nonEmptyString(input.query,
      // "Trailblazer")` safety net that silently runs against "Trailblazer" when the
      // LLM sends `{}`. ajv at the boundary makes the missing field loud so the LLM
      // sees the bug and self-corrects on the next turn instead of getting a
      // misleading "success" envelope.
      let handlerRan = false;
      const result = await invokeTool(
        "search",
        async () => {
          handlerRan = true;
          return { content: [{ type: "text", text: "default-result" }] };
        },
        {},
        {},
        { inputSchema: { query: z.string() } },
      );
      expect(result.isError).toBe(true);
      expect(handlerRan).toBe(false);
      const text = result.content[0].text;
      expect(text).toContain("ValidationError");
      expect(text).toContain("query");
      expect(text.toLowerCase()).toContain("required");
      // Missing-required errors have `instancePath: ""` in ajv, which the
      // formatter renders as `(root)`. Pin that label so a refactor that drops
      // the path-fallback branch in [formatAjvErrors] is caught. Lead-review #6.
      expect(text).toContain("(root):");
    });

    test("multiple validation errors are reported in a single envelope (allErrors mode)", async () => {
      // ajv is configured with `allErrors: true` so the LLM sees every field-level
      // mismatch in one round trip, not just the first one. Pin the cumulative
      // failure surface — both errors must appear in the envelope text.
      const result = await invokeTool(
        "search",
        async () => ({ content: [{ type: "text", text: "should not reach" }] }),
        { query: 42, openFirstResult: "false" },
        {},
        { inputSchema: { query: z.string(), openFirstResult: z.boolean() } },
      );
      expect(result.isError).toBe(true);
      const text = result.content[0].text;
      expect(text).toContain("/query");
      expect(text).toContain("/openFirstResult");
    });

    test("spec with no inputSchema skips validation entirely (today's escape hatch)", async () => {
      // Tools that intentionally take arbitrary args (proxy tools, dev hooks)
      // declare no `inputSchema:` in their spec. Validation is skipped so they
      // continue to dispatch unchanged.
      const result = await invokeTool(
        "passthrough",
        async (args) => ({ content: [{ type: "text", text: JSON.stringify(args) }] }),
        { anything: "goes", count: 42, nested: { deep: true } },
        {},
        // No inputSchema in the spec.
        {},
      );
      expect(result.isError).toBeFalsy();
      expect(result.content[0].text).toContain('"anything":"goes"');
    });

    test("zod schema toJSONSchema can't lower → tool registers without validation", async () => {
      // Some zod shapes (`z.function`, certain `z.custom` refinements, recursive
      // self-references) can't be lowered to JSON Schema by zod's
      // `toJSONSchema`. The fail-open policy: log a warning, skip validation
      // for that tool, but still register it so the daemon doesn't crash on
      // startup. Pin the contract by passing a function-typed schema (which
      // `toJSONSchema` rejects with "Non-representable type encountered:
      // function"). Lead-review #4.
      const result = await invokeTool(
        "fnschema_tool",
        async (args) => ({
          content: [{ type: "text", text: JSON.stringify(args) }],
        }),
        // Dispatch with args that wouldn't satisfy ANY validation; if
        // validation were active the call would isError. The point of this
        // test is that registration didn't abort and dispatch proceeded.
        { whatever: 123 },
        {},
        // `z.function()` is the canonical "toJSONSchema throws" shape.
        // Wrap it in a raw shape so the SDK's passthrough wrapping kicks in.
        { inputSchema: { handler: z.function() } },
      );
      expect(result.isError).toBeFalsy();
      // The handler ran with the raw args (no validation, no coercion).
      expect(result.content[0].text).toContain('"whatever":123');
    });

    test("_clearPendingTools clears the validator cache so re-registering with a new schema takes effect", async () => {
      // Validators are cached by tool name; a regression that drops the cache
      // clear in `_clearPendingTools` would let a stale validator from a
      // previous test see this test's dispatch. Pin: register "x" with a
      // boolean schema, clear, re-register "x" with a string schema, and
      // confirm the string-schema is the one applied. Lead-review #5.
      tool("revalidate_tool", { inputSchema: { v: z.boolean() } } as never, (async () => ({
        content: [{ type: "text", text: "first registration" }],
      })) as never);
      const first = createCapturingServer();
      registerPendingTools(first.server as never);

      _clearPendingTools();

      tool("revalidate_tool", { inputSchema: { v: z.string() } } as never, (async () => ({
        content: [{ type: "text", text: "second registration" }],
      })) as never);
      const second = createCapturingServer();
      registerPendingTools(second.server as never);

      // Dispatch with a string — accepted by the second schema, rejected by
      // the first if the cache were stale.
      const stringResult = (await second.registered["revalidate_tool"](
        { v: "hello" },
        {},
      )) as EnvelopeResult;
      expect(stringResult.isError).toBeFalsy();
      expect(stringResult.content[0].text).toBe("second registration");

      // And: dispatch with a boolean — would have been accepted by the first
      // schema, must be rejected by the second.
      const boolResult = (await second.registered["revalidate_tool"](
        { v: true },
        {},
      )) as EnvelopeResult;
      expect(boolResult.isError).toBe(true);
      expect(boolResult.content[0].text).toContain("/v");
      expect(boolResult.content[0].text).toContain("must be string");
    });

    test("dispatch-time validation failure emits a structured logger.warn alongside the envelope", async () => {
      // The validation pass mirrors its field-level error text through the
      // server-backed logger so an operator watching session logs can alert on
      // a spike of validation failures without parsing envelope text. Pin the
      // `sendLoggingMessage` call shape here so a regression that drops the
      // log line is caught — the silent-spike failure mode is the exact thing
      // this PR's review process surfaced as worth covering. Lead-review #2.
      const { result, loggingMessages } = await invokeToolWithLogCapture(
        "log_check_tool",
        async () => ({ content: [{ type: "text", text: "should not reach" }] }),
        { query: 42 },
        {},
        { inputSchema: { query: z.string() } },
      );
      expect(result.isError).toBe(true);
      // At least one log message captured at level "warning" (MCP's wire spelling
      // for `warn`) under the tool's logger namespace.
      const warns = loggingMessages.filter((m) => m.level === "warning");
      expect(warns.length).toBeGreaterThanOrEqual(1);
      const w = warns[0];
      expect(w.logger).toBe("log_check_tool");
      // `createLogger` passes the message string in `data` when no fields are
      // present. The text should carry the formatted field-level error so
      // operators see the same diagnostic in logs as the LLM sees in the
      // envelope.
      expect(String(w.data)).toContain("input validation failed");
      expect(String(w.data)).toContain("/query");
    });

  });

  describe("typed tool<I, O>(spec, handler) — JSON Schema inputSchema", () => {
    test("happy path: valid args pass through to the typed handler", async () => {
      interface Input { query: string; openFirstResult: boolean }
      let observed: Input | null = null;
      const definition = tool<Input, string>(
        {
          inputSchema: {
            type: "object",
            properties: {
              query: { type: "string" },
              openFirstResult: { type: "boolean" },
            },
            required: ["query", "openFirstResult"],
          },
        },
        async (input) => {
          observed = input;
          return "ok";
        },
      );
      const result = await definition(
        { query: "wikipedia", openFirstResult: true } as never,
        undefined,
        { tools: {} } as never,
      );
      expect(result).toBe("ok");
      expect(observed).toEqual({ query: "wikipedia", openFirstResult: true });
    });

    test("typed dispatch with wrong-type arg throws a ValidationError before the handler runs", async () => {
      interface Input { query: string }
      let handlerRan = false;
      const definition = tool<Input, string>(
        {
          inputSchema: {
            type: "object",
            properties: { query: { type: "string" } },
            required: ["query"],
          },
        },
        async () => {
          handlerRan = true;
          return "should not reach";
        },
      );
      // The typed adapter throws — `defineTypedTool`'s `TypedToolDefinition` shape
      // is `(args, ctx, client) => Promise<TResult>`, so we can't return an
      // envelope. The synthesized wrapper's host-side catch maps the throw onto
      // the same isError envelope shape — pin the throw + error name here, and
      // the integration tests cover the envelope mapping.
      await expect(
        definition({ query: 42 } as never, undefined, { tools: {} } as never),
      ).rejects.toMatchObject({
        name: "ValidationError",
        message: expect.stringContaining("/query"),
      });
      expect(handlerRan).toBe(false);
    });

    test("typed dispatch with missing required field is caught at the boundary", async () => {
      interface Input { query: string }
      let handlerRan = false;
      const definition = tool<Input, string>(
        {
          inputSchema: {
            type: "object",
            properties: { query: { type: "string" } },
            required: ["query"],
          },
        },
        async () => {
          handlerRan = true;
          return "should not reach";
        },
      );
      await expect(
        definition({} as never, undefined, { tools: {} } as never),
      ).rejects.toMatchObject({
        name: "ValidationError",
        message: expect.stringContaining("query"),
      });
      expect(handlerRan).toBe(false);
    });

    test("typed dispatch with string-where-boolean catches the truthy-coercion bug", async () => {
      interface Input { flag: boolean }
      let handlerRan = false;
      const definition = tool<Input, string>(
        {
          inputSchema: {
            type: "object",
            properties: { flag: { type: "boolean" } },
            required: ["flag"],
          },
        },
        async () => {
          handlerRan = true;
          return "should not reach";
        },
      );
      await expect(
        definition({ flag: "false" } as never, undefined, { tools: {} } as never),
      ).rejects.toMatchObject({
        name: "ValidationError",
        message: expect.stringContaining("must be boolean"),
      });
      expect(handlerRan).toBe(false);
    });

    test("typed tool<I, O>(handler) bare form (no spec) has no validation — escape hatch", async () => {
      // The bare-handler form provides no spec, so there's no inputSchema to
      // validate against. The adapter passes args through unchanged. Authors
      // who want validation reach for the `(spec, handler)` overload.
      const definition = tool<{ x: unknown }, string>(async (input) => String(input.x));
      const result = await definition(
        { x: 42 } as never,
        undefined,
        { tools: {} } as never,
      );
      expect(result).toBe("42");
    });

    test("typed spec WITHOUT inputSchema skips validation (other spec fields still apply)", async () => {
      // An author who declares `supportedPlatforms` / `requiresContext` but omits
      // `inputSchema` opts out of runtime validation. Pin that the dispatch still
      // works without a schema — the spec's other fields are captured by the
      // analyzer and don't gate runtime dispatch.
      interface Input { x: number }
      const definition = tool<Input, number>(
        { supportedPlatforms: ["web"], requiresContext: true },
        async (input) => input.x * 2,
      );
      const result = await definition({ x: 21 }, undefined, { tools: {} } as never);
      expect(result).toBe(42);
    });

    test("malformed inputSchema makes ajv.compile throw — typed tool definition succeeds without validation (fail-open)", async () => {
      // Pin the try/catch around `compileValidator` at the typed-definition
      // site (lead-review second pass #1). Without the catch, one bad
      // `inputSchema:` literal in module-scope code would throw at module
      // evaluation time and skip every typed-tool declaration after it. The
      // contract is: log + skip validation for THIS tool, return a working
      // adapter that dispatches its handler unconditionally.
      //
      // Use a `required` field with a malformed value (JSON Schema requires
      // an array; passing a string is a stable ajv-compile error across
      // versions). The same shape would have crashed `trailblaze.tool<I, O>(spec,
      // handler)` at evaluation before the try/catch was added.
      interface Input { x: string }
      let handlerRan = false;
      const definition = tool<Input, string>(
        {
          inputSchema: {
            type: "object",
            properties: { x: { type: "string" } },
            // `required: string` is not valid JSON Schema; ajv.compile throws.
            required: "not-an-array" as unknown as string[],
          },
        },
        async (input) => {
          handlerRan = true;
          return `dispatched with x=${input.x}`;
        },
      );
      // Definition succeeded (didn't throw at the call site).
      expect(typeof definition).toBe("function");
      // Validation was disabled for this tool — dispatch with args that would
      // FAIL validation if it were live (number instead of string) succeeds.
      const result = await definition(
        { x: 42 } as never,
        undefined,
        { tools: {} } as never,
      );
      expect(handlerRan).toBe(true);
      expect(result).toBe("dispatched with x=42");
    });
  });

  describe("useDefaults — JSDoc @default tags auto-fill at dispatch", () => {
    // ajv's `useDefaults: true` mutates the validated object in-place to fill
    // JSDoc `@default` values from the JSON Schema during validation. The
    // dispatch path passes that same (mutated) reference to the handler, so an
    // author can write `interface Args { /** @default "X" */ field?: string }`
    // and the handler sees `input.field === "X"` when the LLM sent `{}`. The
    // bug class this prevents: defensive code like
    // `nonEmptyString(input.query, "Trailblazer")` that silently masks missing
    // args so the LLM never learns it sent malformed data.

    test("default applied when LLM sends {} against a schema with default value", async () => {
      // The wikipedia-trailmap failure mode #3 from the original PR's motivation —
      // tool has a `nonEmptyString(input.query, "Trailblazer")` safety net that
      // runs against "Trailblazer" when the LLM sends `{}`. With `useDefaults`,
      // the JSON Schema's `default: "Trailblazer"` is applied during validation
      // and the handler sees `{ query: "Trailblazer" }` directly — no defensive
      // code needed.
      interface Args { query: string }
      let observed: Args | null = null;
      const definition = tool<Args, string>(
        {
          inputSchema: {
            type: "object",
            properties: {
              query: { type: "string", default: "Trailblazer", minLength: 1 },
            },
            // `query` is NOT required — its `default` is what fills the gap.
          },
        },
        async (input) => {
          observed = input;
          return `searched for ${input.query}`;
        },
      );
      const result = await definition({} as never, undefined, { tools: {} } as never);
      expect(observed).toEqual({ query: "Trailblazer" });
      expect(result).toBe("searched for Trailblazer");
    });

    test("minLength rejects empty string even when LLM sent it explicitly", async () => {
      // Pin that `useDefaults` doesn't override a value the LLM actually
      // supplied — only fills MISSING properties. An LLM that sends `{ query:
      // "" }` is asserting "I want this exact empty value"; the validator
      // sees the empty string and rejects it via `minLength: 1`.
      interface Args { query: string }
      let handlerRan = false;
      const definition = tool<Args, string>(
        {
          inputSchema: {
            type: "object",
            properties: { query: { type: "string", default: "Trailblazer", minLength: 1 } },
            required: ["query"],
          },
        },
        async (input) => {
          handlerRan = true;
          return `searched for ${input.query}`;
        },
      );
      await expect(
        definition({ query: "" } as never, undefined, { tools: {} } as never),
      ).rejects.toMatchObject({
        name: "ValidationError",
        message: expect.stringContaining("/query"),
      });
      expect(handlerRan).toBe(false);
    });

    test("coerceTypes:false makes string-where-boolean fail (the wikipedia openFirstResult bug)", async () => {
      // This pins that the `useDefaults: true` config didn't accidentally drag
      // in `coerceTypes: true`. With auto-coercion on, ajv would silently turn
      // `"false"` (string) → `false` (boolean) and pass validation; the
      // handler would then receive a real boolean, and the original
      // "string-truthy bug" never surfaces. The whole point of THIS validation
      // pass is to make that bug loud. Explicit pin so a future refactor that
      // flips `coerceTypes` is caught.
      interface Args { openFirstResult: boolean }
      let handlerRan = false;
      const definition = tool<Args, string>(
        {
          inputSchema: {
            type: "object",
            properties: { openFirstResult: { type: "boolean", default: true } },
            required: ["openFirstResult"],
          },
        },
        async () => {
          handlerRan = true;
          return "should not reach";
        },
      );
      await expect(
        definition(
          { openFirstResult: "false" } as never,
          undefined,
          { tools: {} } as never,
        ),
      ).rejects.toMatchObject({
        name: "ValidationError",
        message: expect.stringContaining("must be boolean"),
      });
      expect(handlerRan).toBe(false);
    });

    test("nested defaults apply when the parent object exists", async () => {
      // ajv recurses through `properties` chains. If the parent object is
      // present in the args (even as `{}`), defaults on its child properties
      // fill in. Pin this so the documented-as-supported nesting depth doesn't
      // silently break.
      interface Args { address: { country: string; city?: string } }
      let observed: Args | null = null;
      const definition = tool<Args, string>(
        {
          inputSchema: {
            type: "object",
            properties: {
              address: {
                type: "object",
                properties: {
                  country: { type: "string", default: "US" },
                  city: { type: "string", default: "Seattle" },
                },
              },
            },
          },
        },
        async (input) => {
          observed = input;
          return `${input.address.city}, ${input.address.country}`;
        },
      );
      // LLM sent the parent object as `{}` — ajv fills in BOTH nested defaults.
      const result = await definition(
        { address: {} } as never,
        undefined,
        { tools: {} } as never,
      );
      expect(observed).toEqual({ address: { country: "US", city: "Seattle" } });
      expect(result).toBe("Seattle, US");
    });

    test("CAVEAT: nested defaults do NOT materialize a missing parent object", async () => {
      // ajv's documented behavior: `useDefaults` only fills defaults on
      // properties of objects that ALREADY EXIST. A schema with `{ address: {
      // properties: { country: { default: "US" } } } }` does NOT create the
      // `address` object out of nowhere if the LLM omitted it. The schema
      // here is the same as the previous test, but the LLM sends `{}` instead
      // of `{ address: {} }` — `address` stays absent.
      //
      // Pin this contract so an author who relies on nested defaults doesn't
      // assume the parent will materialize. The workaround is to mark the
      // parent `required` (which ajv then fails validation on if missing) or
      // give the parent itself a `default: {}`.
      interface Args { address?: { country?: string } }
      let observed: Args | null = null;
      const definition = tool<Args, string>(
        {
          inputSchema: {
            type: "object",
            properties: {
              address: {
                type: "object",
                properties: { country: { type: "string", default: "US" } },
              },
            },
            // `address` is NOT required and has no top-level default.
          },
        },
        async (input) => {
          observed = input;
          return input.address?.country ?? "(no address)";
        },
      );
      const result = await definition({} as never, undefined, { tools: {} } as never);
      // The args reach the handler with `address` still missing — NOT `{
      // address: { country: "US" } }`. This is intentional behavior to
      // document, not a bug.
      expect(observed).toEqual({});
      expect(result).toBe("(no address)");
    });

    test("top-level default on a parent object DOES cause the children's defaults to apply", async () => {
      // The workaround for the previous test's caveat: give the parent
      // property itself a `default: {}` so it materializes, then ajv recurses
      // and fills the children. Pin the workaround so it's discoverable from
      // the test suite.
      interface Args { address: { country: string } }
      let observed: Args | null = null;
      const definition = tool<Args, string>(
        {
          inputSchema: {
            type: "object",
            properties: {
              address: {
                type: "object",
                default: {},
                properties: { country: { type: "string", default: "US" } },
              },
            },
          },
        },
        async (input) => {
          observed = input;
          return input.address.country;
        },
      );
      const result = await definition({} as never, undefined, { tools: {} } as never);
      expect(observed).toEqual({ address: { country: "US" } });
      expect(result).toBe("US");
    });
  });
});
