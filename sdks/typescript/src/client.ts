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
 */
export interface CallToolResult {
  type: "call_tool_result";
  success: boolean;
  text_content: string;
  error_message: string;
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
 */
export interface TrailblazeCallToolResult {
  success: true;
  textContent: string;
  errorMessage: string;
}

/**
 * Open type map of `tool name → arg shape`. Empty by default; augmented by:
 *
 *  - The vendored `built-in-tools.d.ts` shipped with this SDK (well-known framework tools
 *    like `tapOnElementWithText`, `inputText`).
 *  - Per-pack `.d.ts` files emitted by the `trailblaze.bundle` Gradle plugin
 *    (one entry per scripted tool declared in the pack's resolved target manifest).
 *
 * Augmentation pattern (declaration merging):
 *
 * ```ts
 * declare module "@trailblaze/scripting" {
 *   interface TrailblazeToolMap {
 *     myScriptedTool: { foo: string; bar?: number };
 *   }
 * }
 * ```
 *
 * A tool listed here lights up the strict `callTool` overload (autocomplete on the name,
 * type-checked args) and surfaces on `client.tools.<name>(...)`. Tools NOT listed fall
 * through to the untyped `(string, Record)` overload — fully backward-compatible with code
 * written against the original signature.
 *
 * **Preferred surface.** New code should use `client.tools.<name>(args)` for every tool
 * that has a typed entry — single autocomplete list across built-ins and per-pack scripted
 * tools, hover JSDoc on both the name and the args. `client.callTool(name, args)` is the
 * lower-level escape hatch for genuinely-dynamic tool names (e.g. names produced at
 * runtime). Both surfaces dispatch through the same wire path; the choice is purely about
 * type-safety ergonomics at the call site.
 *
 * **Multi-pack collision risk.** Within a single pack the Gradle generator fails the build
 * if two scripted tools share a name. Across packs (a TS consumer that imports two pack
 * roots' generated `.d.ts` files), TypeScript declaration merging will silently pick one
 * shape for the colliding key — the static type passes, the runtime mismatches. Tool names
 * MUST be globally unique across every pack a single consumer installs. There is no
 * automated cross-pack enforcement today; conventions and code review carry the load until
 * a multi-pack consumer demands one.
 */
// eslint-disable-next-line @typescript-eslint/no-empty-interface
export interface TrailblazeToolMap {}

/**
 * Method-style namespace derived from [TrailblazeToolMap]. Each augmented entry surfaces as
 * a typed method — e.g. given `interface TrailblazeToolMap { inputText: { text: string } }`,
 * the namespace exposes `inputText(args: { text: string }): Promise<TrailblazeCallToolResult>`.
 *
 * This is the preferred surface for tools that have a typed entry in the map: the IDE shows
 * every available tool when you type `client.tools.`, hover gives JSDoc on both the name and
 * the args, and a wrong-keyed/missing-field args object errors at compile time without any
 * string-literal fiddling.
 *
 * Tools not represented in the map (dynamically-registered, packs without generated
 * bindings) live on `client.callTool(name, args)` with the untyped fallback signature.
 */
export type TrailblazeToolMethods = {
  [K in keyof TrailblazeToolMap]: (
    args: TrailblazeToolMap[K],
  ) => Promise<TrailblazeCallToolResult>;
};

/**
 * Third handler argument on `trailblaze.tool(name, spec, handler)`. Exposes the callback
 * channel so tools can compose other Trailblaze tools. Always provided (never `undefined`) —
 * when the envelope is missing, the client's preflight check still runs and throws a clear
 * error instead of silently no-op'ing.
 */
export interface TrailblazeClient {
  /**
   * Dispatches Trailblaze tool [name] with [args] against the live Trailblaze session and
   * returns the result.
   *
   * **Typed args via [TrailblazeToolMap].** The arg shape is selected by a conditional type:
   *
   *  - Name IS a key of [TrailblazeToolMap] (vendored built-ins + per-pack-generated entries):
   *    `args` must match the typed shape. Wrong keys / missing required fields error at
   *    compile time. Autocomplete shows the tool's parameters.
   *  - Name is NOT a key (dynamically-registered tools, packs without generated bindings,
   *    ad-hoc one-offs): `args` is the fallback `Record<string, unknown>` — same as the
   *    pre-bindings signature. Existing untyped code keeps compiling.
   *
   * The single conditional signature (rather than two overloads) is deliberate: with two
   * overloads, `callTool("knownTool", { wrongShape })` silently falls through to the
   * fallback overload because `Record<string, unknown>` accepts any object. The conditional
   * picks one branch per call-site, so the strict branch's mismatch cannot be evaded.
   *
   * **Transports.** Picked automatically from the envelope — authors never branch on this:
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
   * cached screen state. Authors MUST avoid read-then-write patterns across a `callTool`
   * boundary:
   *
   * ```ts
   * // RACE — inner tool can mutate memory between the read and the write
   * const prev = memory.get("x");
   * await client.callTool("setFoo", {...});
   * memory.put("x", updated(prev));
   * ```
   *
   * The inner tool sees and can mutate any shared state (AgentMemory in particular is not
   * concurrent-safe today). Serialize the access locally or avoid composed mutations when
   * correctness matters.
   */
  callTool<K extends string>(
    name: K,
    args: K extends keyof TrailblazeToolMap ? TrailblazeToolMap[K] : Record<string, unknown>,
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
   * static type just won't show it. For genuinely-dynamic tool names use [callTool] with
   * the untyped fallback overload.
   */
  tools: TrailblazeToolMethods;
}

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
  const callToolImpl = <K extends string>(
    name: K,
    args: K extends keyof TrailblazeToolMap ? TrailblazeToolMap[K] : Record<string, unknown>,
  ): Promise<TrailblazeCallToolResult> => callTool(ctx, name, args as Record<string, unknown>);

  // `client.tools.<toolName>(args)` namespace. Built as a Proxy so the SDK doesn't need to
  // know which keys are augmented — any property access becomes a `callTool(propertyName,
  // args)` dispatch at runtime. The static type (TrailblazeToolMethods) constrains what the
  // IDE/tsc see; the runtime is intentionally permissive so a downstream that augments
  // TrailblazeToolMap without touching the SDK's runtime still works end-to-end.
  return { callTool: callToolImpl, tools: createToolsProxy(callToolImpl) };
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
      return (args: Record<string, unknown>) =>
        callToolImpl(prop, args ?? ({} as Record<string, unknown>));
    },
  });
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
