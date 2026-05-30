// TrailblazeClient — the third argument passed to a `trailblaze.tool(...)` handler, exposing
// `callTool(name, args)` so tools can compose other Trailblaze tools via the daemon's
// `/scripting/callback` endpoint.
//
// Wire contract lives in
// `trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/scripting/callback/JsScriptingCallbackContract.kt`;
// the TS types below are hand-written mirrors per D2 of the envelope-migration devlog
// (`2026-04-22-scripting-sdk-envelope-migration.md`) — JSON-first, no codegen. If a Kotlin-side
// field renames or a variant is added, update both sides in lockstep.

import type { TrailblazeContext } from "./context.js";

// ---- Wire types (mirror Kotlin @Serializable shapes) ------------------------------------------

/**
 * Single action variant for now — a follow-up can add `tap`, `inputText`, etc. when typed
 * commands land (status: deferred indefinitely). Authors construct this implicitly by calling
 * `client.callTool(name, args)`; the field shape matches `JsScriptingCallbackAction.CallTool` on the
 * Kotlin side (`@SerialName`s `tool_name` / `arguments_json`).
 */
export interface CallToolAction {
  type: "call_tool";
  tool_name: string;
  arguments_json: string;
}

export type JsScriptingCallbackAction = CallToolAction;

/**
 * Request envelope sent to `/scripting/callback`. Mirrors the Kotlin `JsScriptingCallbackRequest` exactly,
 * including snake-case keys — the Kotlin data class uses `@SerialName("session_id")` etc. to
 * project camelCase Kotlin fields onto snake-case JSON on the wire.
 */
export interface JsScriptingCallbackRequest {
  version: 1;
  session_id: string;
  invocation_id: string;
  action: JsScriptingCallbackAction;
}

/**
 * `JsScriptingCallbackResult` sealed interface — two variants discriminated by the `type` field. The
 * Kotlin `Json` instance uses `classDiscriminator = "type"` (see `ScriptingCallbackEndpoint`)
 * so a `CallToolResult` serializes as `{"type":"call_tool_result", ...}` and an `Error` as
 * `{"type":"error", ...}`.
 *
 * `structured_content` carries the tool's typed JSON return value when the producer populated
 * one (TS scripted tool whose handler returns a non-string typed value). Absent / null means
 * "this tool returns text only" — the public `client.tools.<name>(args)` proxy falls back to
 * `text_content` in that case so older tools keep their existing string-return shape. Mirror
 * of `JsScriptingCallbackResult.CallToolResult.structuredContent` on the Kotlin side.
 */
export interface CallToolResult {
  type: "call_tool_result";
  success: boolean;
  text_content: string;
  error_message: string;
  structured_content?: unknown;
}

export interface CallbackError {
  type: "error";
  message: string;
}

export type JsScriptingCallbackResult = CallToolResult | CallbackError;

/** Outer response envelope — just wraps a [JsScriptingCallbackResult] per the Kotlin `JsScriptingCallbackResponse`. */
export interface JsScriptingCallbackResponse {
  result: JsScriptingCallbackResult;
}

// ---- Client public surface --------------------------------------------------------------------

/**
 * Public author-facing result of a successful `callTool`. The author reads `textContent` and
 * parses it however the target tool produces output. Named differently from the wire type
 * ([CallToolResult]) so the wire's snake-case doesn't leak into the author's TS.
 *
 * `success` is redundant here — the client throws on any non-success response, so a value
 * returned from `callTool(...)` is always `success: true`. Keeping the field exposed anyway so
 * an author who reaches into the typed shape isn't surprised that it disappeared; costs nothing.
 *
 * `structuredContent` carries the tool's typed JSON return value when the producer populated
 * one. Most authors never read this field directly — the public `client.tools.<name>(args)`
 * proxy already unwraps it into the typed `result` declared in [TrailblazeToolMap]. It's
 * exposed here for the rare consumer that drops down to the low-level `callTool` escape hatch
 * (e.g. an SDK-internal site that needs both `textContent` and structured payload from the
 * same response).
 */
export interface TrailblazeCallToolResult {
  success: true;
  textContent: string;
  errorMessage: string;
  structuredContent?: unknown;
}

/**
 * Open type map of `tool name → { args; result }`. Empty by default; augmented by:
 *
 *  - The vendored `built-in-tools.d.ts` shipped with this SDK (well-known framework tools
 *    like `tapOnElementWithText`, `inputText`).
 *  - Per-trailmap `.d.ts` files emitted by the framework's `WorkspaceClientDtsGenerator`
 *    (one entry per scripted tool declared in the trailmap's resolved target manifest).
 *
 * Augmentation pattern (declaration merging):
 *
 * ```ts
 * declare module "@trailblaze/scripting" {
 *   interface TrailblazeToolMap {
 *     myScriptedTool: { args: { foo: string; bar?: number }; result: string };
 *   }
 * }
 * ```
 *
 * **`args` vs `result`.** `args` is the runtime-validated input shape (JSON-Schema-shaped
 * properties — typed against the matchers the daemon dispatcher actually checks).
 * `result` is the static-only typed return value — see the kdoc on
 * [TrailblazeToolMethods] for the type lie this currently carries.
 *
 * A tool listed here lights up `client.tools.<name>(args)` with autocomplete on the name
 * and type-checked args. Tools NOT listed are not reachable through the public client
 * surface — the lower-level `callTool` dispatch primitive is hidden from the exported
 * [TrailblazeClient] type so an author can't reintroduce an untyped escape hatch
 * (`client.callTool("anything", {...})` no longer compiles).
 *
 * **Single authoring surface.** `client.tools.<name>(args)` is the only call style
 * available to a `.ts` author. The runtime still dispatches everything through the
 * internal `callTool` method that the `tools` Proxy delegates to, but the type isn't
 * exposed publicly — every tool a trailmap can call must be declared in `TrailblazeToolMap`.
 *
 * **Multi-trailmap collision risk.** Within a single trailmap the codegen fails the build
 * if two scripted tools share a name. Across trailmaps (a TS consumer that imports two trailmap
 * roots' generated `.d.ts` files), TypeScript declaration merging will silently pick one
 * shape for the colliding key — the static type passes, the runtime mismatches. Tool names
 * MUST be globally unique across every trailmap a single consumer installs. There is no
 * automated cross-trailmap enforcement today; conventions and code review carry the load until
 * a multi-trailmap consumer demands one.
 */
// eslint-disable-next-line @typescript-eslint/no-empty-interface
export interface TrailblazeToolMap {}

/**
 * Per-entry shape of [TrailblazeToolMap]. Authoring tools (vendored or per-trailmap) write
 * `{ args: <ArgsShape>; result: <ResultShape> }`; the `client.tools.<name>(args)` namespace
 * derives method signatures from these entries.
 *
 * Use `result: void` for tools that meaningfully return nothing (e.g. `hideKeyboard`),
 * `result: string` for tools whose result is a plain text payload (most current tools),
 * or a structured interface for tools that return JSON-shaped data.
 *
 * **Why this is exported.** The type isn't usually named at user-augmentation sites
 * (authors write the literal `{ args; result }` inline). It exists as a public hook for
 * upcoming codegen consumers — the static analyzer pass that walks
 * `trailblaze.tool<I, O>(handler)` declarations will assemble emitter output against
 * this type so emitter and runtime stay in lockstep when the entry shape evolves. Hide
 * with caution.
 */
export type TrailblazeToolEntry = { args: unknown; result: unknown };

/**
 * Method-style namespace derived from [TrailblazeToolMap]. Each augmented entry surfaces as
 * a typed method — given
 * `interface TrailblazeToolMap { inputText: { args: { text: string }; result: void } }`,
 * the namespace exposes `inputText(args: { text: string }): Promise<void>`.
 *
 * This is the sole authoring surface a `.ts` tool file has to compose other Trailblaze
 * tools: the IDE shows every available tool when you type `client.tools.`, hover gives
 * JSDoc on both the name and the args, and a wrong-keyed/missing-field args object errors
 * at compile time without any string-literal fiddling.
 *
 * **The `result` type matches the runtime value.** When the producer tool populates the
 * wire's `structured_content` field (TS scripted tool whose handler returns a typed
 * non-string value), the proxy unwraps that JSON value and returns it as `T[K]['result']`.
 * When the producer doesn't (Kotlin tools that return text, legacy tools), the proxy
 * returns `text_content` cast as `T[K]['result']` — correct when the declared `result` is
 * `string` (the per-trailmap codegen default), and accepted-as-is when the declared `result` is
 * `void` (the caller discards the return anyway). A producer that doesn't populate
 * `structured_content` while the consumer's `TrailblazeToolMap` declares a non-string
 * `result` is a static/runtime mismatch — surfaced as a typed string the consumer didn't
 * expect, traceable to either a missing producer migration or a stale per-trailmap `.d.ts`.
 *
 * Tools not represented in the map (dynamically-registered, trailmaps without generated
 * bindings) are unreachable from a typed `.ts` author — every tool a trailmap can call must be
 * declared in `TrailblazeToolMap` via the SDK's vendored built-ins or per-trailmap codegen.
 */
export type TrailblazeToolMethods = {
  [K in keyof TrailblazeToolMap]: K extends "web_evaluate"
    ? WebEvaluateMethod
    : (
        args: TrailblazeToolMap[K] extends { args: infer A } ? A : never,
      ) => Promise<TrailblazeToolMap[K] extends { result: infer R } ? R : never>;
};

/**
 * Function-overload shape for `client.tools.web_evaluate(...)`. Borrowed from Playwright
 * Java's `Page.evaluate(pageFunction, args)` ergonomic — lets a Trailblaze trailmap author
 * write a JS expression as a TypeScript arrow function (with type-checked args!) instead
 * of hand-stringifying it into the `{ script: "..." }` wire shape the Kotlin tool actually
 * consumes.
 *
 * Three call styles, all dispatch to the same `web_evaluate` Kotlin tool:
 *
 *  1. `web_evaluate(fn, ...args)` — the ergonomic shape. The Proxy calls
 *     `Function.prototype.toString()` on the arrow, JSON-serializes the args array, and
 *     emits `(<fnSrc>).apply(null, <argsJson>)` as the script payload. The function
 *     evaluates in the PAGE context (not the host runtime) with the deserialized args
 *     bound positionally.
 *  2. `web_evaluate(script)` — bare string expression. Compatibility surface for authors
 *     who want full control over the script (e.g. multi-statement IIFE).
 *  3. `web_evaluate({ script })` — the literal args-object shape that matches the Kotlin
 *     tool's wire contract directly. Useful when the script is computed dynamically and
 *     the caller already has it as a string field on a config object.
 *
 * **Closure caveat.** Functions cross the host→page boundary via `Function.prototype.toString()`,
 * so closure variables from the calling scope DON'T survive serialization — pass them
 * positionally via `...args` so they're JSON-encoded into the wire script. The function
 * body must be self-contained (no Node-side imports, no references to outer-scope names).
 *
 * **Return type — type lie warning.** The function form preserves the inferred `TResult`
 * at the typed surface, but the runtime always returns a string. Today's Kotlin
 * `web_evaluate` populates `textContent` with the `toString()` of the JS result —
 * primitives serialize as text (`"42"`, `"hi"`, `"true"`), objects collapse to
 * `[object Object]`, arrays serialize as comma-joined elements. The Proxy returns that
 * raw text verbatim; no JSON.parse heuristic is applied (a previous revision tried, but
 * the heuristic corrupted legitimate string returns whose textual form happened to look
 * like JSON literals — e.g. `"42"` would round-trip as the number `42`, breaking the
 * declared string contract). Authors that need a typed value should `Number(...)` /
 * `JSON.parse(...)` the result themselves, or wait for the structured-content path that
 * populates `structured_content` from the Kotlin side. Until then, return shapes more
 * complex than primitives need `JSON.stringify` on the page side and `JSON.parse` on
 * the host.
 */
type WebEvaluateMethod = {
  <TArgs extends readonly unknown[], TResult>(
    fn: (...args: TArgs) => TResult | Promise<TResult>,
    ...args: TArgs
  ): Promise<TResult>;
  (script: string): Promise<string>;
  (args: { script: string }): Promise<string>;
};

/**
 * Internal client surface — includes the low-level `callTool(name, args)` dispatch
 * primitive that the `tools` Proxy delegates to. File-local (no `export`) and not
 * re-exported from `index.ts` so a downstream author can't name this interface and
 * bypass the lockdown by typing a variable as `TrailblazeClientImpl`. The public
 * [TrailblazeClient] type uses `Omit<..., "callTool">` against this interface in the
 * same file; that's the only consumer that needs to see it.
 */
interface TrailblazeClientImpl {
  /**
   * Dispatches Trailblaze tool [name] with [args] against the live Trailblaze session and
   * returns the result. Internal — see [TrailblazeClient] for the public surface.
   *
   * **Typed args via [TrailblazeToolMap].** `name` is constrained to `keyof TrailblazeToolMap`
   * (vendored built-ins + per-trailmap-generated entries); `args` must match the entry's `args`
   * half (`TrailblazeToolMap[name]["args"]`). Wrong keys / missing required fields error at
   * compile time. The return type stays `TrailblazeCallToolResult` here — `callTool` is the
   * internal envelope-returning primitive that keeps `textContent` + `structuredContent`
   * separate. The public [tools] namespace is where the typed `result` half is exposed —
   * the Proxy unwraps the envelope internally (see [TrailblazeToolMethods] for the unwrap
   * semantics).
   *
   * **Transports.** Picked automatically from the envelope — callers never branch on this:
   *
   *  - **Host** (tool runs as a daemon-spawned subprocess): HTTP POST to
   *    `${ctx.baseUrl}/scripting/callback`.
   *  - **On-device** (tool runs inside the Android QuickJS bundle — `ctx.runtime === "ondevice"`):
   *    in-process `globalThis.__trailblazeCallback` binding. No HTTP server is involved.
   *
   * The request/response shape is identical across transports; only the framing differs.
   * Error messages surface the transport source (HTTP URL or `__trailblazeCallback`) so you
   * can tell at a glance which path failed.
   *
   * **Throws** on any non-success outcome:
   *
   *  - **No envelope:** the outer tool invocation didn't carry `_meta.trailblaze`, so there's
   *    no session/invocation to attach the callback to. Typical cause: unit-testing the tool
   *    without a live Trailblaze session. Throw message includes a hint.
   *  - **No transport:** envelope present but neither `baseUrl` (HTTP) nor `runtime="ondevice"`
   *    + installed binding (in-process) is available. Rare — usually a host setup bug.
   *  - **HTTP non-2xx** (host only): protocol-level framing error (malformed request etc.).
   *    Throws with the daemon's body text for diagnosis.
   *  - **`JsScriptingCallbackResult.Error`:** invocation-unknown, session mismatch, version unsupported.
   *    Throws with the dispatcher's `message`.
   *  - **`JsScriptingCallbackResult.CallToolResult(success: false, ...)`:** tool ran but failed (tool
   *    threw, deserialization failed, dispatch timed out, reentrance cap hit). Throws with
   *    `errorMessage`. Authors who want to handle a tool's boolean "did it work" branching
   *    can wrap the call in `try/catch`; that's the one lowest-common-denominator surface
   *    that works identically for Python / QuickJS consumers later.
   *
   * **Shared state warning.** The dispatched inner tool runs against the same Trailblaze
   * execution context as the outer tool — including the same AgentMemory, driver handle, and
   * cached screen state. Authors MUST avoid read-then-write patterns across a tool-call
   * boundary:
   *
   * ```ts
   * // RACE — inner tool can mutate memory between the read and the write
   * const prev = memory.get("x");
   * await client.tools.setFoo({...});
   * memory.put("x", updated(prev));
   * ```
   *
   * The inner tool sees and can mutate any shared state (AgentMemory in particular is not
   * concurrent-safe today). Serialize the access locally or avoid composed mutations when
   * correctness matters.
   */
  callTool<K extends keyof TrailblazeToolMap>(
    name: K,
    args: TrailblazeToolMap[K] extends { args: infer A } ? A : never,
  ): Promise<TrailblazeCallToolResult>;

  /**
   * Method-style namespace — `client.tools.inputText({ text: "hi" })`. Each property is a
   * typed method derived from a [TrailblazeToolMap] augmentation. `client.tools.<TAB>` in
   * an IDE lists every known tool; mistype an arg key or miss a required field and `tsc`
   * errors at compile time. See [TrailblazeToolMethods] for the type derivation.
   *
   * The runtime is a `Proxy` — any property access becomes a `callTool(propertyName, args)`
   * dispatch. That means a tool not in the map (e.g. typed against an old SDK, or augmented
   * by a bindings file the consumer hasn't pulled in yet) still dispatches at runtime; the
   * static type just won't show it.
   */
  tools: TrailblazeToolMethods;
}

/**
 * Third handler argument on the imperative `trailblaze.tool(name, spec, handler)` signature
 * (and reachable via `ctx.tools` on the typed `trailblaze.tool<I, O>(handler)` surface
 * via [ToolContext]). Exposes the callback channel so tools can compose other Trailblaze
 * tools. Always provided (never `undefined`) — when the envelope is missing, the client's
 * preflight check still runs and throws a clear error instead of silently no-op'ing.
 *
 * **Surface narrowing.** `callTool` is hidden from the public type via `Omit` — `.ts`
 * authors can only compose tools through `client.tools.<name>(args)`, which forces every
 * call through a typed entry in [TrailblazeToolMap]. The runtime keeps `callTool` as the
 * internal dispatch primitive the `tools` Proxy delegates to.
 *
 * **Type-only lockdown.** The narrowing is a compile-time guarantee, not a runtime one.
 * At runtime, the object still carries `callTool` and the `tools` Proxy accepts any string
 * property — `(client as unknown as Record<string, unknown>).callTool("anything", {...})`
 * or `(client.tools as Record<string, unknown>)["anything"]` still dispatch. The lockdown
 * exists to catch honest mistakes at `tsc` time, not to prevent a determined caller from
 * bypassing it. Authorization / tool-availability boundaries live at the daemon (allowlist,
 * selector validation, envelope checks), not at the SDK type layer.
 */
export type TrailblazeClient = Omit<TrailblazeClientImpl, "callTool">;

// ---- Implementation ---------------------------------------------------------------------------

/**
 * Default client-side fetch timeout. Matches the daemon's default
 * `DEFAULT_CALLBACK_TIMEOUT_MS` with a 2 s buffer so the daemon is normally the one that
 * surfaces a structured timeout error — keeps the failure mode readable
 * ("Tool X timed out after 30000ms") rather than a generic AbortError from the fetch.
 *
 * Deployments that override the daemon-side timeout via `-Dtrailblaze.callback.timeoutMs`
 * should also override this client timeout via `TRAILBLAZE_CLIENT_FETCH_TIMEOUT_MS` so the
 * two stay in sync — otherwise the client aborts before the daemon can return and the
 * override is effectively defeated.
 *
 * **Env var is sampled once at module load.** `CLIENT_FETCH_TIMEOUT_MS` below is computed
 * when this module is imported, so `TRAILBLAZE_CLIENT_FETCH_TIMEOUT_MS` must be set *before*
 * the SDK is imported to take effect. Trailblaze-spawned subprocesses already set env vars
 * at spawn time; manual callers importing the SDK need to set the var in their process
 * environment before `import { trailblaze } from "@trailblaze/scripting"`.
 */
const DEFAULT_CLIENT_FETCH_TIMEOUT_MS = 32_000;

/**
 * Env var name consulted for the client-side fetch timeout. Parsed once at module load; bad
 * values (non-positive, non-numeric) fall back to the default rather than crashing the
 * subprocess at tool-call time.
 */
const CLIENT_FETCH_TIMEOUT_MS_ENV = "TRAILBLAZE_CLIENT_FETCH_TIMEOUT_MS";

function resolveClientFetchTimeoutMs(): number {
  // `globalThis.process` because this file has to compile without a hard `@types/node`
  // dependency on downstream authors — reading through globalThis lets us tolerate runtimes
  // where `process` isn't defined (e.g. the future on-device QuickJS bundle).
  const rawValue = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env?.[
    CLIENT_FETCH_TIMEOUT_MS_ENV
  ];
  if (rawValue == null || rawValue.trim() === "") {
    return DEFAULT_CLIENT_FETCH_TIMEOUT_MS;
  }
  const parsed = Number.parseInt(rawValue, 10);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    return DEFAULT_CLIENT_FETCH_TIMEOUT_MS;
  }
  return parsed;
}

const CLIENT_FETCH_TIMEOUT_MS = resolveClientFetchTimeoutMs();

/**
 * Factory for [TrailblazeClient]. Captures the envelope at handler-entry — the client never
 * looks at global state, so a tool registered twice with different envelopes still dispatches
 * through the right one.
 */
export function createClient(ctx: TrailblazeContext | undefined): TrailblazeClient {
  // Loose-typed dispatcher fed to the `tools` Proxy. The Proxy's `get` handler receives
  // arbitrary property-name strings at the JS-runtime layer (TS sees mapped-type keys, but
  // the runtime sees raw `string`), so the impl that backs it must accept `string`/`Record`
  // permissively. The public `TrailblazeClientImpl.callTool` narrows this to
  // `K extends keyof TrailblazeToolMap` for the type-checker.
  const dispatch = (name: string, args: Record<string, unknown>): Promise<TrailblazeCallToolResult> =>
    callTool(ctx, name, args);

  // `client.tools.<toolName>(args)` namespace. Built as a Proxy so the SDK doesn't need to
  // know which keys are augmented — any property access becomes a `callTool(propertyName,
  // args)` dispatch at runtime. The static type (TrailblazeToolMethods) constrains what the
  // IDE/tsc see; the runtime is intentionally permissive so a downstream that augments
  // TrailblazeToolMap without touching the SDK's runtime still works end-to-end.
  //
  // The runtime object carries `callTool` (the internal dispatcher) plus `tools`, but the
  // exported `TrailblazeClient` type omits `callTool` — so a `.ts` tool author sees only
  // the `tools` namespace. The cast on `dispatch` is intentional: the runtime function is
  // permissive on (string, Record), and `TrailblazeClientImpl.callTool` is the public
  // narrowed view onto the same runtime callable.
  const impl: TrailblazeClientImpl = {
    callTool: dispatch as TrailblazeClientImpl["callTool"],
    tools: createToolsProxy(dispatch),
  };
  return impl;
}

/**
 * Property names the JavaScript runtime probes implicitly. Returning a callable for any of
 * these from `client.tools` would trigger silent `callTool` dispatches against the daemon —
 * the worst offender being `then`, which `await client.tools` (or any code path that
 * resolves the namespace as a possibly-thenable value) reads to detect Promise-likes. Block
 * the whole well-known set so introspection sees `undefined` instead of a tool dispatcher.
 *
 * The list mirrors what eg. Node's `util.inspect`, Promise.resolve, and structuredClone
 * touch on plain objects; not exhaustive, but covers every probe path observed in practice
 * for similar Proxy-as-namespace patterns.
 */
/**
 * Tool names whose `client.tools.<name>(...)` dispatch accepts the function-overload shape
 * documented on [WebEvaluateMethod]. The Proxy detects these names at access time and
 * routes through [buildScriptOverloadArgs] before calling into the standard `callToolImpl`
 * dispatch.
 *
 * Add a tool here when both sides line up:
 *  - The Kotlin tool accepts a `{ script: String }` arg shape.
 *  - The typed surface in [TrailblazeToolMethods] declares the overload signature.
 *
 * Mismatch — e.g. adding the name here without the typed-surface conditional, or vice
 * versa — produces either a runtime mistranslation (Proxy emits a `script` field the tool
 * doesn't expect) or a static/runtime mismatch (TS thinks `web_X(fn)` compiles but the
 * Proxy passes the function through as if it were an args object). Keep the two in
 * lockstep.
 *
 * **Today's membership is `web_evaluate` only.** When the Playwright-tool-shim codegen
 * lands (see the `2026-05-26-playwright-tool-shim-codegen.md` devlog), siblings like
 * `web_addInitScript` and `web_setExtraHeaders` will join via that generator's
 * code-generated entries — not by hand-edit here.
 */
const SCRIPT_OVERLOAD_TOOLS = new Set<string>(["web_evaluate"]);

/**
 * Translates a function-overload call (`tool(fn, ...args)`, `tool(scriptString)`, or
 * `tool({ script })`) into the `{ script }` args-object shape the Kotlin tool consumes.
 *
 * Discriminator order matters — the function branch fires before the object branch so an
 * authored `function ...` doesn't accidentally match the `firstArg !== null && typeof ===
 * "object"` check (functions are objects in JS, but `typeof fn === "function"`). The
 * string branch handles the `tool("expr")` shape; the args-object branch handles the
 * `tool({ script })` shape and is also the fallback for any other shape (let the daemon
 * reject malformed args with a clear message rather than this helper second-guessing).
 *
 * **Function-form payload.** `(<fn.toString()>).apply(null, <JSON.stringify(args)>)`
 * — `apply` with a null `this` keeps the call indifferent to the call site (no leaking
 * `globalThis`), and the JSON-roundtrip means closure-variable misuse fails loudly at
 * serialization time rather than producing a subtly-wrong script. `JSON.stringify`
 * preserves primitives, plain objects, and arrays; functions/Symbol/undefined fields
 * disappear silently per the standard (consistent with what Playwright's own
 * `page.evaluate` does on the wire, so authors carrying habits from there see no surprises).
 */
function buildScriptOverloadArgs(
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
    `client.tools.${toolName}: expected a function, script string, or { script } object as ` +
      `the first argument; got ${firstArg === null ? "null" : typeof firstArg}.`,
  );
}

const TOOLS_PROXY_RESERVED_PROPS = new Set<string>([
  // Thenable detection — the critical one. Without this, `await client.tools` (or any
  // value-coercion path) reads `.then`, gets back a fn, calls it, and dispatches a
  // `callTool("then", { ... })` to the daemon. Has bitten Proxy-as-namespace patterns in
  // multiple SDKs.
  "then",
  "catch",
  "finally",
  // Object-protocol introspection — `client.tools.constructor.name`, `String(client.tools)`,
  // `+client.tools`, `JSON.stringify(client.tools)`, `Object.getPrototypeOf(...)`, etc. All
  // would otherwise resolve to a tool dispatcher.
  "constructor",
  "prototype",
  "__proto__",
  "toString",
  "valueOf",
  "toJSON",
]);

/**
 * Builds the `client.tools.<name>(args)` Proxy. Each property access returns an async
 * per-call wrapper that dispatches via [callToolImpl] and unwraps the envelope via
 * [_unwrapToolResult] so the caller sees the typed `result` rather than the raw envelope.
 *
 * **Why per-call wrapping is intentional.** The pre-PR shape was `(args) => callToolImpl(...)`
 * — one promise per call, no extra frame. After structured-content lands, the wrapper has
 * to `await` the envelope, run unwrap, and re-return — adding one promise + one frame per
 * dispatch. Per-call cost is negligible (<1µs); flagging here so a future micro-optimizer
 * doesn't try to inline the unwrap away and re-introduce the envelope-leak the unwrap
 * exists to prevent.
 */
function createToolsProxy(
  callToolImpl: (name: string, args: Record<string, unknown>) => Promise<TrailblazeCallToolResult>,
): TrailblazeToolMethods {
  return new Proxy({} as TrailblazeToolMethods, {
    get(_target, prop, _receiver) {
      // Symbol-keyed access (Symbol.iterator, Symbol.toPrimitive, util.inspect.custom, etc.)
      // — never a tool name, always a runtime probe. Return undefined so the runtime sees
      // "no, this object doesn't have that protocol" rather than a callable.
      if (typeof prop !== "string") return undefined;
      // Reserved JS-protocol names — see [TOOLS_PROXY_RESERVED_PROPS] kdoc.
      if (TOOLS_PROXY_RESERVED_PROPS.has(prop)) return undefined;
      // Blank / whitespace-only names can't possibly be valid tools. Returning a callable
      // and letting the daemon reject `callTool("", ...)` is correct but wastes a round-trip
      // and surfaces the failure far from the typo. Throw at the access site instead so the
      // stack trace points at the offending `client.tools[someBadKey]` line.
      if (prop.trim() === "") {
        throw new Error(
          `client.tools[${JSON.stringify(prop)}]: tool name must not be empty or whitespace-only.`,
        );
      }
      // Script-overload tools (today: `web_evaluate`) accept either a function, a bare
      // string, or a `{ script }` args object. The proxy normalizes the input to the
      // standard `{ script }` wire shape before dispatch — see the kdoc on
      // [SCRIPT_OVERLOAD_TOOLS] and [buildScriptOverloadArgs] for the discriminator
      // semantics.
      //
      // The envelope passes through the standard `_unwrapToolResult`. The Kotlin tool
      // populates textContent via `result?.toString()`, so the consumer receives the
      // page's stringified return value. The function-overload's `TResult` type-inference
      // is a documented type lie for non-string returns; authors that need a typed value
      // should JSON.parse themselves until `structured_content` lands (kdoc on
      // [WebEvaluateMethod] spells this out). An earlier revision JSON.parse'd textContent
      // for the function form to try to recover primitives — that corrupted any string
      // return that happened to parse as JSON (e.g. `"42"` becoming the number `42`) and
      // lost legitimate empty-string returns by mapping them to `undefined`. Dropping the
      // heuristic is the honest reflection of the wire reality.
      //
      // Per-tool return-value transforms (e.g. discarding textContent for `Promise<void>`
      // tools like a future `web_addInitScript`) belong in the per-tool generated wrapper
      // emitted by the Playwright-tool-shim codegen, NOT in this hand-edited branch.
      if (SCRIPT_OVERLOAD_TOOLS.has(prop)) {
        const toolName = prop;
        return async (firstArg: unknown, ...rest: unknown[]) => {
          const args = buildScriptOverloadArgs(toolName, firstArg, rest);
          const envelope = await callToolImpl(toolName, args);
          return _unwrapToolResult(envelope);
        };
      }
      return async (args: Record<string, unknown>) => {
        const envelope = await callToolImpl(prop, args ?? ({} as Record<string, unknown>));
        return _unwrapToolResult(envelope);
      };
    },
  });
}

/**
 * Unwraps a [TrailblazeCallToolResult] envelope into the typed `result` declared in
 * [TrailblazeToolMap] for the calling tool. Two branches:
 *
 *  - **Structured payload present.** The producer (TS scripted tool with a non-string typed
 *    return, or a Kotlin tool that populated `structured_content`) supplied a JSON value —
 *    return that verbatim. The TS type contract guarantees the shape matches the declared
 *    `result`; runtime validation at the dispatch boundary is a deferred follow-up that
 *    will consume the JSON Schema emitted by the analyzer in #3323. Until that lands, a
 *    producer that emits a malformed structured payload (e.g. wrong nested type) surfaces
 *    as a downstream TypeScript type-mismatch at the consumer call site, not a runtime
 *    diagnostic at the unwrap boundary.
 *  - **No structured payload.** Fall back to `textContent`. Correct when the declared
 *    `result` is `string` (most tools today, the per-trailmap codegen default). When `result`
 *    is `void`, the caller discards the return value anyway. When `result` is anything else
 *    and the producer hasn't migrated, the caller sees a string they didn't expect —
 *    surfaced as a downstream type mismatch rather than a silent runtime failure.
 *
 * **Internal.** The `_` prefix matches `_clearPendingTools` in `./tool.ts` — this is an
 * SDK-internal helper, exported from `./client.js` only so [createMockToolsProxy] in
 * `./testing.ts` and the unit tests in `./client.test.ts` can exercise it directly. Not
 * re-exported from `./index.ts`; SDK consumers reach the unwrap implicitly via the
 * `client.tools.<name>(args)` Proxy.
 */
export function _unwrapToolResult<R>(envelope: TrailblazeCallToolResult): R {
  if (envelope.structuredContent !== undefined && envelope.structuredContent !== null) {
    return envelope.structuredContent as R;
    // TODO(#3323-followup): post-unwrap JSON Schema validation hook lands here once the
    // analyzer emits per-tool schemas — wrap the return in `validate(envelope.structuredContent)`
    // to convert wire/declared shape mismatches into a runtime diagnostic at this boundary
    // rather than surfacing as a downstream TS type mismatch.
  }
  return envelope.textContent as unknown as R;
}

async function callTool(
  ctx: TrailblazeContext | undefined,
  name: string,
  args: Record<string, unknown>,
): Promise<TrailblazeCallToolResult> {
  if (ctx === undefined) {
    // Preflight: without the envelope we can't build a valid JsScriptingCallbackRequest. Throw at call
    // time rather than registration time so a tool that only conditionally calls back (e.g.
    // checks something, returns early on a happy path) can still register and run in
    // environments without an envelope.
    throw new Error(
      `trailblaze.client.callTool("${name}") requires a TrailblazeContext envelope, but the ` +
        `tool was invoked without one. This usually means the tool is being called outside a ` +
        `live Trailblaze session (ad-hoc MCP client, unit test). Check that the invoking ` +
        `environment injects \`_meta.trailblaze\` on \`tools/call\`.`,
    );
  }

  const request: JsScriptingCallbackRequest = {
    version: 1,
    session_id: ctx.sessionId,
    invocation_id: ctx.invocationId,
    action: {
      type: "call_tool",
      tool_name: name,
      // Per the wire contract — `arguments_json` is a JSON STRING (not a nested object). Keeps
      // tool schemas Kotlin-authoritative and avoids round-trip drift when args have non-trivial
      // structure (numbers vs. strings etc.).
      arguments_json: JSON.stringify(args),
    },
  };

  // Transport branch. The on-device QuickJS bundle runtime stamps `runtime: "ondevice"` on
  // the envelope and installs a `globalThis.__trailblazeCallback` binding; both must be
  // present before we take the in-process path (an older Kotlin runtime without the
  // binding would otherwise throw a generic "not a function" error several frames down).
  // Subprocess / daemon paths leave `runtime` absent and populate `baseUrl` instead.
  if (ctx.runtime === "ondevice" && hasInProcessBinding()) {
    return dispatchViaInProcessBinding(request, name);
  }

  if (!ctx.baseUrl) {
    throw new Error(
      `trailblaze.client.callTool("${name}") requires a ctx.baseUrl (HTTP path) or ` +
        `ctx.runtime="ondevice" + globalThis.__trailblazeCallback (in-process path), ` +
        `but neither was available. Check that the Trailblaze runtime populated the ` +
        `envelope correctly — in tests, inject a valid \`_meta.trailblaze\`.`,
    );
  }
  return dispatchViaHttp(request, name, ctx.baseUrl);
}

/**
 * Surface of the in-process binding the bundle runtime installs. Typed minimally so this
 * file compiles without a QuickJS / JS-runtime dependency — downstream authors pulling the
 * SDK into their own test setups never see the Kotlin-side type.
 */
type TrailblazeCallbackBinding = (requestJson: string) => Promise<string>;

function hasInProcessBinding(): boolean {
  return typeof (globalThis as { __trailblazeCallback?: unknown }).__trailblazeCallback === "function";
}

async function dispatchViaInProcessBinding(
  request: JsScriptingCallbackRequest,
  toolName: string,
): Promise<TrailblazeCallToolResult> {
  const binding = (globalThis as { __trailblazeCallback?: TrailblazeCallbackBinding }).__trailblazeCallback;
  // `hasInProcessBinding` was checked in the caller, but re-read here so a consumer that
  // spoofed the flag without installing the binding still sees a clear error, not a
  // TypeError about calling undefined.
  if (typeof binding !== "function") {
    throw new Error(
      `trailblaze.client.callTool("${toolName}") picked the in-process transport but ` +
        `globalThis.__trailblazeCallback isn't installed. This should never happen in a ` +
        `real bundle runtime — report as a Trailblaze bug.`,
    );
  }

  let responseJson: string;
  try {
    responseJson = await binding(JSON.stringify(request));
  } catch (e) {
    // QuickJS→Kotlin binding failures surface here (should be unreachable in practice —
    // the Kotlin-side binding catches and returns a JsScriptingCallbackResult.Error envelope). Wrap
    // with tool-name context so a regression in the binding is traceable.
    const message = e instanceof Error ? e.message : String(e);
    throw new Error(
      `trailblaze.client.callTool("${toolName}") in-process binding threw: ${message}`,
    );
  }

  let envelope: JsScriptingCallbackResponse;
  try {
    envelope = JSON.parse(responseJson) as JsScriptingCallbackResponse;
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e);
    throw new Error(
      `trailblaze.client.callTool("${toolName}") failed to parse in-process binding response as JSON: ${message}`,
    );
  }
  return unwrapCallbackResponse(envelope, toolName, "__trailblazeCallback");
}

async function dispatchViaHttp(
  request: JsScriptingCallbackRequest,
  toolName: string,
  baseUrl: string,
): Promise<TrailblazeCallToolResult> {
  // `new URL(path, baseUrl)` handles trailing-slash and absolute-path joining per WHATWG — no
  // double-slashing, no missed-slash, works regardless of whether `ctx.baseUrl` ends in `/`.
  // Simpler than a hand-rolled `joinUrl` helper that needed branch coverage of its own.
  //
  // Wrapped in try/catch so a malformed `ctx.baseUrl` (daemon bug surfacing through the envelope)
  // throws with the tool-name prefix instead of a bare `TypeError` — matches the rest of the
  // client's error style and keeps the failure traceable back to the offending callTool invocation.
  let url: string;
  try {
    url = new URL("/scripting/callback", baseUrl).toString();
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e);
    throw new Error(
      `trailblaze.client.callTool("${toolName}") failed to build request URL from baseUrl "${baseUrl}": ${message}`,
    );
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), CLIENT_FETCH_TIMEOUT_MS);
  let response: Response;
  try {
    response = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(request),
      signal: controller.signal,
    });
  } catch (e) {
    // Surface abort as a clearer error than the raw DOMException string. Other fetch failures
    // (connection refused, DNS, etc.) stay wrapped with the tool-name context so a debugger
    // can tell what was being called.
    //
    // Identify timeouts via the `AbortError` DOMException name rather than `controller.signal.aborted`.
    // If a network error (e.g. ECONNREFUSED) fires at the same instant the timeout aborts the signal,
    // `signal.aborted` can be `true` even though the real cause was the network — the DOMException
    // name is the authoritative distinction because the runtime only sets it on an abort-originated
    // rejection.
    const message = e instanceof Error ? e.message : String(e);
    if (e instanceof DOMException && e.name === "AbortError") {
      throw new Error(
        `trailblaze.client.callTool("${toolName}") aborted after ${CLIENT_FETCH_TIMEOUT_MS}ms waiting for ${url}`,
      );
    }
    throw new Error(`trailblaze.client.callTool("${toolName}") fetch failed: ${message}`);
  } finally {
    clearTimeout(timer);
  }

  if (!response.ok) {
    // Non-2xx = protocol-level framing error from the daemon (`400 Malformed`, `403 loopback
    // only`, etc.). The body has the human-readable reason.
    const body = await safeReadText(response);
    throw new Error(
      `trailblaze.client.callTool("${toolName}") HTTP ${response.status} from ${url}: ${body}`,
    );
  }

  let envelope: JsScriptingCallbackResponse;
  try {
    envelope = (await response.json()) as JsScriptingCallbackResponse;
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e);
    throw new Error(
      `trailblaze.client.callTool("${toolName}") failed to parse response body as JSON: ${message}`,
    );
  }
  return unwrapCallbackResponse(envelope, toolName, url);
}

/**
 * Shared result-envelope unwrapper for both transports. [source] is `__trailblazeCallback`
 * on the in-process path or the full HTTP URL on the fetch path — surfaced in the
 * missing-result error so a debugger can tell which transport produced the bad envelope.
 * Semantics are identical between the two transports by design (see the 2026-04-23
 * on-device callback devlog).
 */
function unwrapCallbackResponse(
  envelope: JsScriptingCallbackResponse | undefined | null,
  toolName: string,
  source: string,
): TrailblazeCallToolResult {
  const result = envelope?.result as JsScriptingCallbackResult | undefined;
  if (result == null) {
    throw new Error(
      `trailblaze.client.callTool("${toolName}") response from ${source} is missing the "result" ` +
        `field — expected a JsScriptingCallbackResponse, got: ${JSON.stringify(envelope)}`,
    );
  }
  // Shape-check before touching .type — an upstream that returns a primitive result (e.g.
  // a misconfigured proxy responding `{"result":"error"}`) would otherwise throw a generic
  // TypeError from `result.type`, defeating the whole point of this function being the
  // structured-error gate. Narrow to "object" (arrays are objects in JS; a malformed array
  // would still produce a readable error below when none of the variant branches match).
  if (typeof result !== "object") {
    throw new Error(
      `trailblaze.client.callTool("${toolName}") response from ${source} has a non-object "result" ` +
        `(got ${typeof result}) — expected a JsScriptingCallbackResult variant: ${JSON.stringify(envelope)}`,
    );
  }
  if (result.type === "error") {
    // Transport-neutral wording — `source` is the HTTP URL on the daemon path or
    // `__trailblazeCallback` on the in-process path. "daemon error" would be misleading
    // on-device where there's no daemon involved at all.
    throw new Error(
      `trailblaze.client.callTool("${toolName}") callback error from ${source}: ${result.message}`,
    );
  }
  if (!result.success) {
    throw new Error(
      `trailblaze.client.callTool("${toolName}") tool failed: ${result.error_message || "(no message)"}`,
    );
  }
  return {
    success: true,
    textContent: result.text_content,
    errorMessage: result.error_message,
    structuredContent: result.structured_content,
  };
}

/**
 * Read `response.text()` but never throw — we're already in an error-reporting path and a
 * secondary failure would mask the primary HTTP error. Returns a sentinel if text extraction
 * itself fails.
 */
async function safeReadText(response: Response): Promise<string> {
  try {
    return await response.text();
  } catch {
    return "(could not read response body)";
  }
}
