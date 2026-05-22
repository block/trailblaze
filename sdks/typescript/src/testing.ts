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
// continue to ignore test-only types in non-test builds without per-pack include shaping.

import type {
  TrailblazeCallToolResult,
  TrailblazeClient,
  TrailblazeToolMap,
  TrailblazeToolMethods,
} from "./client.js";
import type {
  TrailblazeContext,
  TrailblazeDevice,
  TrailblazeTarget,
  TrailblazeLogger,
} from "./context.js";

// Type-only imports above are erased at compile time, leaving this module with ZERO
// runtime dependencies on the rest of the SDK source. Two reasons:
//
//   1. The framework ships this file as a standalone runtime resource (alongside the
//      bundled `dist/testing.d.ts`) so a pack's `*.test.ts` can resolve
//      `@trailblaze/scripting/testing` via the per-pack tsconfig's `paths` mapping AND
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
 */
export interface MockStubResponse {
  textContent: string;
  errorMessage?: string;
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
   * `web_verify_text_visible` ends up with two entries whose `tool` fields equal those
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
      return (args: Record<string, unknown>) =>
        dispatch(prop, args ?? ({} as Record<string, unknown>));
    },
  });
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
    driverType?: string;
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
export function createMockContext(opts: CreateMockContextOptions): TrailblazeContext {
  const device: TrailblazeDevice = {
    platform: opts.platform,
    widthPixels: opts.device?.widthPixels ?? 1080,
    heightPixels: opts.device?.heightPixels ?? 2400,
    driverType: opts.device?.driverType ?? "mock-driver",
  };

  return {
    baseUrl: opts.baseUrl,
    runtime: opts.runtime === "ondevice" ? "ondevice" : undefined,
    sessionId: opts.sessionId ?? "mock-session",
    invocationId: opts.invocationId ?? "mock-invocation",
    device,
    target: opts.target,
    memory: opts.memory ?? {},
    logger: mockNoopLogger,
  };
}
