// Unit-test helpers for scripted-tool authors. The real `createClient` in `./client.ts`
// dispatches through HTTP or an in-process binding — neither is available in a `bun test`
// run, so authors need a drop-in fake that records the calls a tool makes and returns
// canned responses for the boolean "did it work" branching in handler bodies.
//
// Public API:
//
//   import { createMockClient, createMockContext } from "@trailblaze/scripting/testing";
//
//   const client = createMockClient();
//   const ctx = createMockContext({ platform: "web" });
//   await myTool({ ... }, ctx, client);
//   expect(client.calls[0].tool).toBe("web_navigate");
//
// Design constraints kept identical with the production client:
//   * The `tools` namespace is a Proxy with the same reserved-property guard. Without it,
//     `await client.tools` would silently record a `then` call into `calls` — same trap
//     the real client guards against (see [TOOLS_PROXY_RESERVED_PROPS] in client.ts and
//     its companion client.test.ts).
//   * Property-access errors (blank tool name) throw synchronously at the access site,
//     not on the eventual await, so the test failure points at the offending line.
//
// Not exported back through `index.ts` — consumers reach this module via
// `@trailblaze/scripting/testing`, which lets a future `tsc` pass over a `*.test.ts` file
// continue to ignore test-only types in non-test builds without per-trailmap include shaping.

import type {
  TrailblazeCallToolResult,
  TrailblazeClient,
  TrailblazeToolMap,
  TrailblazeToolMethods,
} from "./client.js";
import type { MatchDescriptor } from "./generated/selectors.js";
import type {
  TrailblazeContext,
  TrailblazeDevice,
  TrailblazeMemory,
  TrailblazeTarget,
  TrailblazeLogger,
} from "./context.js";

// Type-only imports above are erased at compile time, leaving this module with ZERO
// runtime dependencies on the rest of the SDK source. Two reasons:
//
//   1. The framework ships this file as a standalone runtime resource (alongside the
//      bundled `dist/testing.d.ts`) so a trailmap's `*.test.ts` can resolve
//      `@trailblaze/scripting/testing` via the per-trailmap tsconfig's `paths` mapping AND
//      have bun find an executable file at the same path. Adding a relative
//      `./logger.js` runtime import would force the framework to also ship the SDK's
//      internal source tree — a much larger surface and a coupling we don't want.
//   2. Mocks should have no production-runtime entanglement by design. A regression
//      anywhere else in the SDK can't break test isolation if the testing module never
//      reaches into the production code path.
//
// The trivial inline no-op below replaces the `noopLogger` we'd otherwise borrow from
// `./logger.js`; expanding the surface (adding `trace`, `fatal`, etc.) means updating
// this object in lockstep with the production logger.
const mockNoopLogger: TrailblazeLogger = {
  debug: () => {},
  info: () => {},
  warn: () => {},
  error: () => {},
};

/**
 * Inline mock `TrailblazeMemory` builder. Mirrors `createMemory` in `./memory.ts`
 * deliberately rather than importing it — `testing.ts` is transpile-only (not bundled)
 * and ships as a standalone runtime resource alongside `dist/testing.d.ts`, so a
 * relative runtime import would force the framework to ship the SDK's internal source
 * tree. Mirroring keeps the test-only fake independent of the production module graph
 * for the same reason `mockNoopLogger` is a sibling copy of the production no-op logger.
 *
 * **Sync obligation** — when `createMemory` in `./memory.ts` grows a new method on the
 * `TrailblazeMemory` interface, mirror it here in lockstep. The compiler enforces this
 * (returned object must satisfy `TrailblazeMemory`), but the BEHAVIOR can drift
 * (e.g. interpolate semantics) without a type error. If memory logic changes
 * frequently, consider graduating `testing.ts` to a real bundled artifact.
 *
 * The mock supports all 8 surface methods but its drain function is a no-op — tests
 * don't go through the `registerPendingTools` envelope-flush path, so capturing the
 * delta would be dead state.
 */
function createMockMemory(snapshot: Record<string, unknown> | undefined): TrailblazeMemory {
  const map = new Map<string, string>();
  if (snapshot) {
    for (const [k, v] of Object.entries(snapshot)) {
      if (typeof v === "string") map.set(k, v);
    }
  }
  const get = (key: string): string | undefined => map.get(key);
  const memory: TrailblazeMemory & { toJSON: () => Record<string, string> } = {
    get,
    set(key, value) { map.set(key, value); },
    has(key) { return map.has(key); },
    keys() { return [...map.keys()]; },
    delete(key) { map.delete(key); },
    interpolate(template) {
      let result = template;
      for (const re of [/\$\{([^}]+)\}/g, /\{\{([^}]+)\}\}/g]) {
        result = result.replace(re, (_match, name: string) => get(name) ?? "");
      }
      return result;
    },
    setJson(key, value) { map.set(key, JSON.stringify(value)); },
    getJson<T,>(key: string): T | undefined {
      const raw = map.get(key);
      if (raw === undefined) return undefined;
      try { return JSON.parse(raw) as T; } catch { return undefined; }
    },
    toJSON() { return Object.fromEntries(map); },
  };
  return memory;
}

/**
 * One recorded call from a [MockTrailblazeClient]. The runtime sees raw `string` /
 * `Record<string, unknown>` for `tool`/`args` because the Proxy can't know which key in
 * [TrailblazeToolMap] (if any) the property access was meant to resolve to — tests assert
 * via name equality (`expect(client.calls[0].tool).toBe("web_navigate")`) rather than
 * keyof-narrowing, so the loose runtime type is intentional.
 */
export interface MockCall {
  tool: string;
  args: Record<string, unknown>;
}

/**
 * Canned response a test can register for a specific tool name. `errorMessage` mirrors the
 * production [TrailblazeCallToolResult]'s field — supplying a non-empty value causes the
 * mock to throw with the production client's `"tool failed: <message>"` wording so a tool
 * under test that wraps its `client.tools.X(...)` call in `try/catch` exercises the same
 * code path against the mock as against a live daemon.
 *
 * `structuredContent` carries the typed JSON payload a producer would set for a non-string
 * `result` (TS scripted tool with `trailblaze.tool<I, O>(handler)`). When non-null, the
 * mock client's `tools.<name>(args)` unwrap returns it verbatim as the typed `result` —
 * matching the production [createClient]'s behavior so a test that asserts on the unwrapped
 * value sees the same shape against the mock as it would against a live daemon. Leave
 * undefined for tools whose declared `result` is `string` (the per-trailmap codegen default).
 *
 * **Three equivalent ways to say "no structured payload."** Omitting the field entirely,
 * setting it to `undefined`, and setting it explicitly to `null` all collapse to the same
 * "fall back to `textContent`" branch in the unwrap. The mock cannot distinguish them —
 * matching production's `_unwrapToolResult`, which treats `undefined` and `null` identically
 * via `!== undefined && !== null`. If a test wants to model an explicit wire-null vs an
 * omitted-key for some future test scenario, it would need a different fixture (e.g., a raw
 * envelope passed to `_unwrapToolResult`) rather than the stub API.
 */
export interface MockStubResponse {
  textContent: string;
  errorMessage?: string;
  structuredContent?: unknown;
}

/**
 * Mock client surface. Extends the public [TrailblazeClient] (so a tool's typed handler
 * signature accepts it without a cast) and adds the test-only inspection / setup methods.
 *
 * `calls` is mutable so tests can splice / clear ad-hoc; `stub` and `reset` are the
 * conventional entry points.
 */
export type MockTrailblazeClient = TrailblazeClient & {
  /**
   * Recorded calls in invocation order. A tool that fires `web_navigate` then
   * `web_verifyTextVisible` ends up with two entries whose `tool` fields equal those
   * names and whose `args` mirror what the tool passed in.
   */
  calls: MockCall[];

  /**
   * Register a canned response for [toolName]. Subsequent `client.tools[toolName](...)`
   * calls return the stubbed shape (or throw with `errorMessage` when set, matching the
   * production client's behavior on a `success: false` result). Stubs persist until
   * [reset] is called.
   */
  stub(toolName: string, response: MockStubResponse): void;

  /** Clear both recorded calls and registered stubs. */
  reset(): void;
};

/**
 * Build a mock [TrailblazeClient] that records every `client.tools.<name>(args)` invocation
 * into `calls` and returns either the default success response or a per-tool stub registered
 * via [MockTrailblazeClient.stub].
 *
 * The default response is `{ success: true, textContent: "", errorMessage: "" }` — matches
 * what the daemon returns when a tool completes without a body, the most common case authors
 * hit in `await`-and-discard composition.
 */
export function createMockClient(): MockTrailblazeClient {
  const calls: MockCall[] = [];
  const stubs = new Map<string, MockStubResponse>();

  const dispatch = async (
    name: string,
    args: Record<string, unknown>,
  ): Promise<TrailblazeCallToolResult> => {
    calls.push({ tool: name, args });
    const stub = stubs.get(name);
    if (stub && stub.errorMessage && stub.errorMessage.length > 0) {
      // Mirror the production client's error wording so a test that asserts on caught
      // error messages doesn't drift from production behavior. See `unwrapCallbackResponse`
      // in client.ts for the source string.
      throw new Error(
        `trailblaze.client.callTool("${name}") tool failed: ${stub.errorMessage}`,
      );
    }
    return {
      success: true,
      textContent: stub?.textContent ?? "",
      errorMessage: "",
      structuredContent: stub?.structuredContent,
    };
  };

  const tools = createMockToolsProxy(dispatch);

  const mock: MockTrailblazeClient = {
    tools,
    calls,
    stub(toolName: string, response: MockStubResponse): void {
      if (toolName.trim() === "") {
        throw new Error(
          "createMockClient: stub() tool name must not be empty or whitespace-only.",
        );
      }
      stubs.set(toolName, response);
    },
    reset(): void {
      // Mutate `calls` in place (rather than reassigning) so a test that captured the array
      // reference earlier sees the cleared state instead of holding a stale snapshot.
      calls.length = 0;
      stubs.clear();
    },
  };
  return mock;
}

/**
 * Mirrors the production [TOOLS_PROXY_RESERVED_PROPS] in client.ts. Kept as a sibling copy
 * rather than imported across module boundaries because the production set is `file-local`
 * (not exported) — and inlining keeps this module independent of internal client-file
 * structure. If the production set grows, mirror the addition here.
 */
const MOCK_TOOLS_PROXY_RESERVED_PROPS = new Set<string>([
  "then",
  "catch",
  "finally",
  "constructor",
  "prototype",
  "__proto__",
  "toString",
  "valueOf",
  "toJSON",
]);

/**
 * Mirrors the production [SCRIPT_OVERLOAD_TOOLS] in client.ts — tool names whose proxy
 * dispatch accepts the `(fn, ...args)` / `(scriptString)` / `({ script })` overload shape.
 * Kept as a sibling copy for the same module-independence reason as the reserved-props
 * set; if the production set grows, mirror the addition here so mock-client tests exercise
 * the same input-translation path that production does.
 */
const MOCK_SCRIPT_OVERLOAD_TOOLS = new Set<string>(["web_evaluate"]);

/** Mirrors the production [buildScriptOverloadArgs] in client.ts. See that helper's kdoc. */
function mockBuildScriptOverloadArgs(
  toolName: string,
  firstArg: unknown,
  rest: unknown[],
): Record<string, unknown> {
  if (typeof firstArg === "function") {
    const fnSrc = (firstArg as (...a: unknown[]) => unknown).toString();
    const argsJson = JSON.stringify(rest);
    return { script: `(${fnSrc}).apply(null, ${argsJson})` };
  }
  if (typeof firstArg === "string") {
    return { script: firstArg };
  }
  if (firstArg !== null && typeof firstArg === "object") {
    return firstArg as Record<string, unknown>;
  }
  throw new Error(
    `mockClient.tools.${toolName}: expected a function, script string, or { script } object as ` +
      `the first argument; got ${firstArg === null ? "null" : typeof firstArg}.`,
  );
}

function createMockToolsProxy(
  dispatch: (name: string, args: Record<string, unknown>) => Promise<TrailblazeCallToolResult>,
): TrailblazeToolMethods {
  return new Proxy({} as TrailblazeToolMethods, {
    get(_target, prop, _receiver) {
      if (typeof prop !== "string") return undefined;
      if (MOCK_TOOLS_PROXY_RESERVED_PROPS.has(prop)) return undefined;
      if (prop.trim() === "") {
        throw new Error(
          `mockClient.tools[${JSON.stringify(prop)}]: tool name must not be empty or whitespace-only.`,
        );
      }
      if (MOCK_SCRIPT_OVERLOAD_TOOLS.has(prop)) {
        const toolName = prop;
        return async (firstArg: unknown, ...rest: unknown[]) => {
          const args = mockBuildScriptOverloadArgs(toolName, firstArg, rest);
          const envelope = await dispatch(toolName, args);
          return mockUnwrapToolResult(envelope);
        };
      }
      return async (args: Record<string, unknown>) => {
        const envelope = await dispatch(prop, args ?? ({} as Record<string, unknown>));
        return mockUnwrapToolResult(envelope);
      };
    },
  });
}

/**
 * Mirrors the production [`_unwrapToolResult`][./client.ts] semantics exactly. Inlined here
 * so this module keeps zero runtime imports from `./client.js` — see the kdoc at the top of
 * this file for why testing.ts must stay runtime-independent. If [`_unwrapToolResult`]'s
 * logic ever changes, mirror the change here in lockstep so a test that asserts on the
 * unwrapped value can't drift from production behavior.
 */
function mockUnwrapToolResult<R>(envelope: TrailblazeCallToolResult): R {
  if (envelope.structuredContent !== undefined && envelope.structuredContent !== null) {
    return envelope.structuredContent as R;
  }
  return envelope.textContent as unknown as R;
}

/**
 * Options accepted by [createMockContext]. Every field has a sensible default so a test
 * that only cares about platform can write `createMockContext({ platform: "web" })`.
 *
 * `runtime: "host"` resolves to `undefined` on the produced context (matching the
 * production envelope's convention — `runtime` is only emitted on the on-device path).
 * `runtime: "ondevice"` passes through verbatim.
 */
export interface CreateMockContextOptions {
  platform: TrailblazeDevice["platform"];
  sessionId?: string;
  invocationId?: string;
  baseUrl?: string;
  runtime?: "host" | "ondevice";
  device?: {
    widthPixels?: number;
    heightPixels?: number;
    /** MCP/subprocess-shaped driver yamlKey (`TrailblazeDevice.driverType`). */
    driverType?: string;
    /**
     * On-device QuickJS-shaped driver yamlKey (`TrailblazeDevice.driver`). Set this to model the
     * `runtime: inProcess` envelope, where the driver arrives under `driver` rather than
     * `driverType` — so a test can exercise a tool's driver branching on the in-process path.
     */
    driver?: string;
  };
  target?: TrailblazeTarget;
  memory?: Record<string, unknown>;
}

/**
 * Build a [TrailblazeContext] with test-friendly defaults. Use as the second argument when
 * invoking a scripted tool handler from a unit test.
 *
 * Defaults pick values that won't accidentally collide with anything a tool reads — IDs
 * are deterministic strings, device dimensions are a non-zero placeholder, driver type is
 * a clearly-marked test sentinel.
 */
/**
 * Test client surface for scenarios that need sequenced, per-call responses from
 * `findMatches` — different from [createMockClient]'s single-static-stub-per-tool model.
 *
 * Built for `ConditionalAction` + `captureViewHierarchy` tests, where a flow typically
 * needs distinct match sets across the initial snapshot and the post-action verify
 * snapshot (or per-selector). The same primitive is useful for future waypoint detection
 * tests that need to model a moving UI across multiple `findMatches` callbacks.
 *
 * `calls` records every dispatched tool name + args in insertion order (mirrors
 * [createMockClient]). `queueFindMatches(responses)` appends to the queue; each
 * `findMatches` callback dequeues the next response. Non-`findMatches` tools resolve to
 * `""` (mirrors the production `textContent: ""` happy-path default).
 *
 * Queue exhaustion is loud — if `findMatches` is called more times than the test queued
 * responses, the dispatcher throws with the offending args so the test failure points at
 * the unexpected dispatch.
 */
export interface QueuedFindMatchesClient extends TrailblazeClient {
  calls: Array<{ tool: string; args: Record<string, unknown> }>;
  queueFindMatches(responses: Array<MatchDescriptor[]>): void;
  /**
   * Queue the per-call boolean results for `waitUntilNotVisible` — the non-throwing
   * disappearance probe (see `WaitUntilNotVisibleTrailblazeTool` / `built-in-tools.ts`). Each
   * `client.tools.waitUntilNotVisible(...)` call dequeues the next boolean. Mirrors
   * [queueFindMatches] so a flow that interleaves appearance probes (`findMatches`) and
   * disappearance probes (`waitUntilNotVisible`) — the shape every launch step has — can model a
   * moving UI across both. Like the findMatches queue, exhaustion is loud (throws with the args).
   */
  queueWaitUntilNotVisible(responses: boolean[]): void;
}

/**
 * Build a [QueuedFindMatchesClient]. The minimal `TrailblazeClient` surface plus a queue
 * for `findMatches` responses — see [QueuedFindMatchesClient] for the semantics.
 *
 * Use for any test that exercises multi-call `findMatches` flows (catalog-driven
 * conditional actions, multi-snapshot verify refreshes, waypoint detection iterations).
 * The [createMockClient] mock is simpler when each tool is called at most once per test.
 */
export function createQueuedFindMatchesClient(): QueuedFindMatchesClient {
  const calls: Array<{ tool: string; args: Record<string, unknown> }> = [];
  const findMatchesQueue: Array<MatchDescriptor[]> = [];
  const waitUntilNotVisibleQueue: boolean[] = [];

  const dispatch = (name: string, args: Record<string, unknown>): unknown => {
    calls.push({ tool: name, args });
    if (name === "findMatches") {
      if (findMatchesQueue.length === 0) {
        throw new Error(
          `createQueuedFindMatchesClient: no more findMatches responses queued; received ` +
            `call with args=${JSON.stringify(args)}. Did the test forget a queueFindMatches?`,
        );
      }
      return findMatchesQueue.shift();
    }
    if (name === "waitUntilNotVisible") {
      if (waitUntilNotVisibleQueue.length === 0) {
        throw new Error(
          `createQueuedFindMatchesClient: no more waitUntilNotVisible responses queued; received ` +
            `call with args=${JSON.stringify(args)}. Did the test forget a queueWaitUntilNotVisible?`,
        );
      }
      return waitUntilNotVisibleQueue.shift();
    }
    return "";
  };

  const tools = new Proxy({} as TrailblazeToolMethods, {
    get(_target, prop, _receiver) {
      if (typeof prop !== "string") return undefined;
      // Mirror the production reserved-props guard so `await client.tools` doesn't
      // accidentally dispatch a `then` call.
      if (prop === "then" || prop === "catch" || prop === "finally") return undefined;
      return async (args: Record<string, unknown>) => dispatch(prop, args ?? {});
    },
  });

  return {
    tools,
    calls,
    queueFindMatches(responses) {
      findMatchesQueue.push(...responses);
    },
    queueWaitUntilNotVisible(responses) {
      waitUntilNotVisibleQueue.push(...responses);
    },
  };
}

export function createMockContext(opts: CreateMockContextOptions): TrailblazeContext {
  const device: TrailblazeDevice = {
    platform: opts.platform,
    widthPixels: opts.device?.widthPixels ?? 1080,
    heightPixels: opts.device?.heightPixels ?? 2400,
    driverType: opts.device?.driverType ?? "mock-driver",
    // Mirrors the on-device QuickJS envelope's `driver` field; left undefined unless a test sets it.
    driver: opts.device?.driver,
  };

  return {
    baseUrl: opts.baseUrl,
    runtime: opts.runtime === "ondevice" ? "ondevice" : undefined,
    sessionId: opts.sessionId ?? "mock-session",
    invocationId: opts.invocationId ?? "mock-invocation",
    device,
    target: opts.target,
    memory: createMockMemory(opts.memory),
    logger: mockNoopLogger,
  };
}
