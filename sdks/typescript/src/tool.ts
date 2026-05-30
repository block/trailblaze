// `trailblaze.tool(name, spec, handler)` — the SDK's authoring surface for declaring an MCP
// tool. Wraps `server.registerTool` so authors never touch the raw MCP SDK unless they want
// to, and extracts the `TrailblazeContext` envelope up-front so every handler sees the same
// typed shape regardless of how the host is invoking it.
//
// Tools authored with this helper buffer until `trailblaze.run()` starts the stdio server —
// that ordering lets authors declare all their tools at module top-level without worrying
// about whether the MCP server has been created yet.

import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { AnySchema, ZodRawShapeCompat } from "@modelcontextprotocol/sdk/server/zod-compat.js";
import { Ajv2020, type ErrorObject, type ValidateFunction } from "ajv/dist/2020.js";
import { z } from "zod";

import { createClient, type TrailblazeClient, type TrailblazeToolMethods } from "./client.js";
import { fromMeta, type TrailblazeContext, type TrailblazeTarget } from "./context.js";
import { createLogger } from "./logger.js";
import {
  createMemory,
  DRAIN_DELTA,
  META_KEY_MEMORY_DELETIONS,
  META_KEY_MEMORY_DELTA,
  META_KEY_TRAILBLAZE,
  type DrainableMemory,
  type TrailblazeMemory,
} from "./memory.js";

/**
 * Spec for a Trailblaze-authored tool authored via the **imperative**
 * `trailblaze.tool(name, spec, handler)` overload — a shallow wrapper over the raw
 * MCP SDK's `registerTool` spec shape. Carries `description` / `inputSchema` /
 * `outputSchema` / raw `_meta` because the imperative path doesn't have access to
 * `<I, O>` generics or TSDoc, so every piece of registration metadata is authored
 * by hand.
 *
 * **For the typed declarative overload — `trailblaze.tool<I, O>(spec, handler)` —
 * use [TrailblazeTypedToolSpec] instead.** The typed spec carries structured
 * registration-gate fields (`supportedPlatforms`, `requiresContext`,
 * `requiresHost`, `supportedDrivers`) but **no `description`** — descriptions
 * for typed tools live in the TSDoc above the `export const`, where the analyzer
 * reads them.
 *
 * The surface is intentionally narrow. Fields that aren't wired here can be added as authors
 * ask for them — nothing about `registerPendingTools` prevents forwarding additional keys.
 */
export interface TrailblazeToolSpec {
  /** Human-readable description surfaced to the LLM. */
  description?: string;
  /**
   * MCP input schema — a zod raw shape (`{ text: z.string() }`), a zod schema instance
   * (`z.object({...})`), or an empty raw shape (`{}`) for no-args tools.
   *
   * Typed against the MCP SDK's own [ZodRawShapeCompat] / [AnySchema] union — the same
   * surface `server.registerTool` accepts. The Trailblaze wrapper in
   * [withPassthroughInputSchema] narrows raw shapes to `z.object(shape).passthrough()`
   * before handing off, so unknown keys from the wire survive validation.
   */
  inputSchema?: ZodRawShapeCompat | AnySchema;
  /**
   * Output schema, if your tool returns structured data. Same surface rules as
   * [inputSchema] — zod raw shape, zod schema instance, or undefined.
   */
  outputSchema?: ZodRawShapeCompat | AnySchema;
  /**
   * `_meta` for the tool advertisement — this is where `_meta["trailblaze/*"]` keys go (e.g.
   * `"trailblaze/supportedDrivers": ["android-ondevice-accessibility"]`). The conventions
   * devlog (`2026-04-20-scripted-tools-mcp-conventions.md § 1`) is canonical.
   */
  _meta?: Record<string, unknown>;
}

/**
 * Author-facing handler signature.
 *
 * - [args] — tool arguments as the LLM / caller sent them.
 * - [ctx] — Trailblaze envelope extracted from `_meta.trailblaze`. Present on every in-session
 *   invocation; undefined when the tool was invoked outside a Trailblaze session (ad-hoc MCP
 *   client, unit test).
 * - [client] — always provided, even when [ctx] is undefined. Exposes the typed
 *   `client.tools.<name>(args)` namespace for composing other Trailblaze tools via the
 *   daemon's `/scripting/callback` endpoint. When [ctx] is undefined the client throws on
 *   any tool dispatch with a clear preflight error — handlers that never call back simply
 *   ignore the argument.
 *
 * **Backwards compatibility.** Pre-existing 2-arg handlers (`async (args) => ...` or
 * `async (args, ctx) => ...`) remain compatible — TypeScript accepts them when assigned to this
 * 3-arg signature because structural typing tolerates handlers that ignore trailing parameters.
 * Extra parameters are only consumed when the handler explicitly declares them, so tool files
 * written against older SDK versions keep compiling without changes.
 *
 * The return value matches the raw MCP SDK's tool-result shape; nothing special to learn.
 */
export type TrailblazeToolHandler = (
  args: Record<string, unknown>,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
) => Promise<TrailblazeToolResult> | TrailblazeToolResult;

/**
 * MCP tool result — mirrors `@modelcontextprotocol/sdk`'s public shape but re-declared here so
 * the SDK doesn't impose its import graph on every tool file. Authors can return literal
 * objects matching this shape or re-import from the MCP SDK for typed variants they want.
 */
export interface TrailblazeToolResult {
  content: Array<{ type: "text"; text: string } | Record<string, unknown>>;
  isError?: boolean;
  structuredContent?: unknown;
}

/** Internal record of a tool declared via [tool], awaiting registration by [run]. */
export interface PendingToolRegistration {
  name: string;
  spec: TrailblazeToolSpec;
  handler: TrailblazeToolHandler;
}

const pendingTools: PendingToolRegistration[] = [];

// ---- Typed tool-authoring surface -------------------------------------------------------------

/**
 * Minimal handler context for the typed `trailblaze.tool<I, O>(handler)` authoring
 * surface. Exposes the cross-tool primitives a typed handler can reach today:
 * [tools], [memory], and [target].
 *
 * Deliberately narrower than [TrailblazeClient] / [TrailblazeContext]. Per-need fields
 * (device, logger) can still be added later when a concrete typed-authored tool needs
 * them; the goal is to grow the surface in lockstep with real demand rather than
 * blanket-mirroring every field a Kotlin handler sees.
 *
 * ## Inclusion policy
 *
 * The bar for adding a field here is intentionally higher than "it's on `TrailblazeContext`."
 * A field gets added when (a) **multiple** typed tools in the repo have demonstrated demand
 * (one-off needs are usually a sign the tool wants the imperative `tool(name, spec, handler)`
 * form, not a permanent SDK expansion), and (b) the field's lifecycle matches the existing
 * set — injected per-call by `defineTypedTool`, originated from the host envelope.
 *
 * Concrete deliberate exclusion: **`device`** (platform / driver / screen dimensions). It's
 * on `TrailblazeContext` and the next obvious extension, but no typed tool in the repo today
 * needs it (the few that branch on platform do so via the spec's `supportedPlatforms` gate
 * instead). Open a PR-level discussion before adding it so the bar above is satisfied.
 *
 * **Memory on the bundle path.** When a typed tool runs in the on-device QuickJS bundle
 * runtime, the host ctx envelope doesn't yet carry a memory snapshot — `ctx.memory`
 * on that path is a no-op surface whose writes never flush back to the host. Subprocess
 * sessions get a fully-wired memory; bundle-path support lands in a follow-up.
 *
 * **Target on the bundle path.** Same caveat: `ctx.target` is populated whenever the
 * host envelope carries a resolved-target descriptor (both subprocess and on-device
 * paths emit it via their respective envelope builders), but it can still be
 * `undefined` for sessions with no target (web-only scratch tools, unit-test
 * fixtures). Typed handlers should optional-chain (`ctx.target?.resolveAppId()`)
 * when the surrounding tool ought to work outside a target-aware session.
 */
export interface ToolContext {
  /** Compose other Trailblaze tools through the typed `tools.<name>(args)` namespace. */
  tools: TrailblazeToolMethods;
  /**
   * Per-invocation memory surface mirroring [TrailblazeContext.memory]. Reads see the
   * host snapshot + this invocation's writes (read-your-own-writes); writes are flushed
   * back to the host on a successful return via the result envelope's
   * `_meta.trailblaze.memoryDelta`.
   */
  memory: TrailblazeMemory;
  /**
   * Resolved-target descriptor — the trailmap manifest's `target.platforms.<platform>`
   * data after the framework has consulted the connected device for which app id to
   * actually use. Provides [TrailblazeTarget.resolveAppId] (Android/iOS) and
   * [TrailblazeTarget.resolveBaseUrl] (web) for tools that need to compose
   * platform-specific package / URL references without hard-coding them.
   *
   * `undefined` when the session has no target configured (web-only scratch tools,
   * unit-test fixtures, envelopes from older daemons that predate the field).
   * Optional-chain when the tool should still degrade gracefully — typed handlers
   * that strictly require a target should throw a clear "no target" error on the
   * undefined branch rather than silently no-op.
   */
  target?: TrailblazeTarget;
}

/**
 * Structured-config spec for the typed `trailblaze.tool<I, O>(spec, handler)` overload.
 *
 * Carries the namespaced framework hints that, in the YAML-descriptor world, were authored
 * under `_meta: { trailblaze/... }`. With the typed authoring surface, authors set them
 * directly as typed object fields and the build-time analyzer
 * (`ScriptedToolDefinitionAnalyzer`) extracts them from each `trailblaze.tool(...)` call
 * site — the runtime `_meta` JSON is synthesized downstream, never hand-authored.
 *
 * **No `description` field.** Tool descriptions live in the TSDoc block above each
 * `export const X = trailblaze.tool(...)` binding. The analyzer reads it via the
 * TypeScript compiler's `getJSDocCommentsAndTags()`. Forcing prose into TSDoc keeps the
 * IDE-hover text and the LLM-facing description as the same single source of truth and
 * eliminates the dual-source-of-truth question by construction — the compiler refuses to
 * accept a `description` field here, so there's no place else to put prose.
 *
 * Every field is optional; omitted means "use the framework default" (`false` for
 * booleans, empty list for the platform/driver gates which the runtime treats as
 * "unrestricted").
 *
 * ## Field roles — "registration gate" vs. "metadata hint"
 *
 * Fields on this spec fall into two categories. The categorization matters when
 * adding a new field — picking the wrong path means the runtime either silently
 * ignores the value or routes it through the wrong layer:
 *
 *  - **Registration gates** decide whether the tool is even registered for a
 *    given session. The runtime consults these BEFORE the tool reaches the
 *    LLM's tool list — a tool that fails its gate is invisible. Examples:
 *    [supportedPlatforms], [requiresHost], [supportedDrivers]. The on-device
 *    dispatch path reads `requiresHost` *before* `_meta` is loaded, so it's
 *    additionally promoted to the typed `InlineScriptToolConfig.requiresHost`
 *    slot at enrichment time.
 *  - **Metadata hints** are informational — they flow into the runtime `_meta`
 *    JSON and are surfaced in tool catalogs, agent warnings, and downstream
 *    consumers, but they do NOT gate registration. Example: [requiresContext].
 *    The runtime registers the tool either way; the hint just helps explain
 *    *why* the tool needs a live session.
 *
 * Adding a new field: decide which bucket it belongs in first. Gates need
 * coverage in `TrailblazeToolMeta.shouldRegister` and (when the gate is read
 * before `_meta`) a typed `InlineScriptToolConfig` slot. Hints just need the
 * namespaced `_meta` projection in `AnalyzerScriptedToolEnrichment`.
 *
 * @see TrailblazeToolSpec for the imperative `tool(name, spec, handler)` form's spec —
 *   distinct shape because the imperative path doesn't have access to TSDoc or `<I, O>`
 *   generics, so it carries `description` / `inputSchema` / `outputSchema` / raw `_meta`
 *   directly.
 */
export interface TrailblazeTypedToolSpec {
  /**
   * Platforms this tool may register on. Empty / omitted = all platforms. Lowercase
   * platform names — the runtime (`TrailblazeToolMeta.fromJsonObject`) normalizes to
   * uppercase before comparison against `TrailblazeDevicePlatform.name`.
   */
  supportedPlatforms?: ReadonlyArray<"web" | "android" | "ios" | "desktop">;
  /**
   * UX hint: this tool depends on a live driver context (a running target/session). The
   * agent surfaces this in tool catalogs and warnings. **Not a registration filter** —
   * the runtime registers the tool either way; the field is purely informational. See
   * `TrailblazeToolMeta.shouldRegister` for the filter set (drivers, platforms, host).
   */
  requiresContext?: boolean;
  /**
   * Host-only — skip registration on-device. Use for tools that need Node/Bun APIs
   * (`node:fs`, `node:child_process`, file locks) or otherwise can't run inside the
   * on-device QuickJS bundle. The on-device launcher passes `preferHostAgent=false` so a
   * `requiresHost: true` tool skips at registration without any extra branching.
   */
  requiresHost?: boolean;
  /**
   * Drivers this tool may register on. Empty / omitted = all drivers. Driver identifiers
   * as the runtime emits them — e.g. `"playwright-native"`, `"playwright-electron"`,
   * `"android-ondevice-accessibility"`. Finer-grained than [supportedPlatforms]; use this
   * when a tool depends on driver-specific capabilities that other drivers on the same
   * platform don't have.
   */
  supportedDrivers?: readonly string[];
  /**
   * Optional JSON Schema for the typed tool's input. When present, the runtime adapter
   * compiles it via ajv and validates the incoming `args` BEFORE invoking the handler;
   * a validation failure short-circuits dispatch by throwing a
   * `TypedToolValidationError` (`name = "ValidationError"`). The synthesized
   * host-side wrapper (`DaemonScriptedToolBundler.synthesizeWrapper` →
   * `QuickJsToolHost.callTool`) catches the throw and maps it onto the same
   * `isError: true` envelope shape any handler-thrown error rides through — so a
   * session-log reader sees one consistent error format regardless of failure
   * mode. (Direct callers of the returned `TypedToolDefinition` see the throw
   * unwrapped, useful for unit tests that pin the validation behavior — see the
   * tests in `tool.test.ts` that `await expect(...).rejects.toMatchObject(...)`.)
   * The envelope text content names the offending fields, so the LLM can
   * self-correct without crashing inside the handler.
   *
   * **Source of truth.** The canonical input type for a typed tool is the `<TInput>`
   * generic on the call (`trailblaze.tool<MyInput>(...)`), extracted at build time by
   * `ScriptedToolDefinitionAnalyzer`. The analyzer-derived schema is what populates
   * the runtime tool descriptor + MCP `_meta` advertisement, and it is what the LLM
   * sees. Setting `inputSchema:` here is an opt-in escape hatch for authors who want
   * the same schema reachable at the JS dispatch boundary — useful for catching the
   * "LLM sent malformed args" failure mode in environments where the static-analysis
   * pipeline hasn't injected the schema yet, OR for authors who want a narrower
   * runtime contract than the TS interface expresses.
   *
   * **Shape.** A JSON Schema object — e.g. `{ type: "object", properties: { q: { type:
   * "string" } }, required: ["q"] }`. Plain literal, not a zod schema; the typed
   * authoring surface intentionally does not depend on zod at the dispatch boundary
   * (zod's value lives in interface authoring, which the analyzer reads statically).
   *
   * **When to omit.** Bare-handler `trailblaze.tool<I, O>(handler)` form. No spec, no
   * runtime validation — relies on the analyzer + MCP advertisement to keep the LLM
   * honest.
   *
   * **NOT a SISTER-IMPL-TAG field.** Every other field on this spec
   * (`supportedPlatforms`, `requiresContext`, `requiresHost`, `supportedDrivers`)
   * is extracted by the analyzer's `RECOGNIZED_SPEC_FIELDS` set and projected
   * into namespaced `_meta` keys by the Kotlin side's `projectAnalyzerSpec`. This
   * field is deliberately the exception: the analyzer's job is to extract the
   * `<TInput>` interface into a JSON Schema for MCP advertisement, so flowing
   * `inputSchema:` *also* through the analyzer would either duplicate the
   * `<TInput>` schema (if both are present) or override it (if the author
   * intentionally narrowed). Today the field is consumed at the TS dispatch
   * boundary directly; the analyzer ignores it intentionally. If a future change
   * wants to surface this field in `_meta`, decide the precedence-vs-interface
   * rule first, then update `RECOGNIZED_SPEC_FIELDS` and `projectAnalyzerSpec` in
   * lockstep — see the SISTER-IMPL-TAG comment in
   * `sdks/typescript/tools/extract-tool-defs.mjs:RECOGNIZED_SPEC_FIELDS`.
   *
   * **Long-term plan.** Once the analyzer-injected sidecar lands (its schema
   * flows to the JS runtime via the synthesized QuickJS wrapper), this field
   * becomes redundant for typical tools — authors will get validation from the
   * `<TInput>` interface alone. The field will stick around as the narrower-
   * runtime-contract escape hatch but stops being the recommended way to wire
   * runtime validation.
   */
  inputSchema?: Record<string, unknown>;
}

/**
 * Public marker for "this tool takes no input." TypeScript generic defaults are
 * positional, so an author who wants a typed result with NO input can't skip the
 * first type argument; they have to spell it. `EmptyInput` is the readable form of
 * that ceremony:
 *
 *   trailblaze.tool(async () => "ok")                                  // 0 args
 *   trailblaze.tool<MyInput>(async (i, ctx) => "ok")                   // typed input + string
 *   trailblaze.tool<EmptyInput, MyResult>(async (_, ctx) => ({...}))   // typed result + no input
 *   trailblaze.tool<MyInput, MyResult>(async (i, ctx) => ({...}))      // fully typed
 *
 * Declared as an `interface` (not `type = Record<string, never>`) so the analyzer's
 * `ts-json-schema-generator` walks it as a named object type and emits the expected
 * `{"type":"object", "additionalProperties": false}` schema. Authoring-time, it's
 * structurally equivalent to `Record<string, never>` for the no-properties case; the
 * runtime handler receives an empty object on every call.
 */
// eslint-disable-next-line @typescript-eslint/no-empty-interface
export interface EmptyInput { /* intentionally empty — see kdoc */ }

/**
 * Carrier returned by the typed authoring surface. Today it's a 3-arg adapter shaped
 * `(args, ctx, client) => Promise<TResult>` — the same call shape the existing scripted-tool
 * wrapper synthesizer (`DaemonScriptedToolBundler.synthesizeWrapper`) invokes against every
 * registered tool. The adapter unpacks `client.tools` into the typed [ToolContext] before
 * forwarding to the author's `(input, ctx)` handler, so a `.ts` author can write the typed
 * shape WITHOUT the runtime dispatcher having to learn about the alternative arity.
 *
 * The `<I, O>` type parameters remain the load-bearing part of the contract for codegen
 * — `ScriptedToolDefinitionAnalyzer` walks each `trailblaze.tool<I, O>(handler)` call site
 * via the TypeScript AST to derive [TrailblazeToolMap] entries. The runtime shape chosen
 * here is decoupled from extraction; this 3-arg adapter just ensures the synthesized
 * wrapper's `__userHandler(args, ctx, __client)` call site reaches the author's typed
 * handler with the right arguments. If a future change wants a richer descriptor returned
 * here (e.g. carrying metadata that the analyzer can't recover from the AST alone), update
 * both sides in lockstep.
 */
export type TypedToolDefinition<
  TInput = Record<string, never>,
  TResult = string,
> = (
  args: TInput,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
) => Promise<TResult>;

/**
 * Underlying runtime wrapper for the typed `trailblaze.tool<I, O>(handler)` overload of
 * [tool]. Returns a 3-arg adapter that binds the author's typed handler to the
 * synthesized wrapper's `(args, ctx, client)` call shape. Not re-exported from
 * `index.ts` — the canonical authoring surface is `trailblaze.tool<I, O>(handler)`,
 * which dispatches into this function.
 *
 * **Why a 3-arg adapter, not a bare handler.** The existing scripted-tool wrapper invokes
 * every registered handler with `__userHandler(args, ctx, __client)` — the legacy
 * imperative shape. The typed surface wants the author to write `(input, ctx)`, where
 * `ctx` is the narrower [ToolContext] (`{ tools: TrailblazeToolMethods }`). The adapter
 * bridges the two by constructing the [ToolContext] from the runtime `client.tools`
 * proxy on each invocation. The synthesized wrapper stays unchanged; typed handlers
 * "just work" through the existing dispatch path with no host-side branching.
 *
 * **What the defensive guard catches.** Anything that's not a function — null,
 * undefined, plain objects, primitives. A clear `TypeError` is thrown at definition
 * time so the failure points at the offending call site rather than several stack
 * frames later. Authors who reach this branch are JS-only callers or `as unknown`-cast
 * TS callers; the TS overload signature `(input, ctx) => Promise<...>` rejects
 * non-function shapes at compile time.
 *
 * **What the guard does NOT catch.** Class constructors (`typeof === "function"` but
 * callable only with `new`) pass through and crash at invocation with `Class
 * constructor cannot be invoked without 'new'`. Detecting constructors reliably
 * requires probing `.prototype` descriptors, which is brittle across realms (iframes,
 * vm contexts) and engines. The tradeoff is intentional: the TS overload's
 * `(input, ctx) => Promise<...>` signature already rejects class types at the type
 * level, so a constructor reaches here only via an explicit `as never` cast — at which
 * point the caller has opted out of the static contract anyway.
 */
function defineTypedTool<TInput, TResult>(
  handler: (input: TInput, ctx: ToolContext) => Promise<TResult>,
  inputSchema?: Record<string, unknown>,
): TypedToolDefinition<TInput, TResult> {
  if (typeof handler !== "function") {
    // The `<TInput, TResult>` overloads guarantee a function at type-check time, but a
    // JS-only caller (or an `as unknown`-cast TS caller) can still hand us garbage.
    // Fail loudly at definition time instead of returning `undefined` and surfacing as
    // a "not a function" error far from the offending call site.
    throw new TypeError(
      "trailblaze.tool<I, O>(handler): argument must be a function. " +
        "Got: " + (handler == null ? String(handler) : typeof handler) + ".",
    );
  }
  // Compile the input validator once at definition time so per-dispatch overhead is
  // just `validator(args)` (one `new Function`-emitted JS call). The compiled
  // function is captured in the returned adapter's closure — there's nothing else
  // to look it up by, and `defineTypedTool` is called exactly once per tool, so
  // there's no caching layer between compile and use. (Review feedback on the
  // first ajv pass: an earlier draft kept a Map cache keyed by a synthesized
  // counter ID, but the cache could never hit — every typed-tool definition
  // generates a fresh key, so the lookup was pure overhead. The closure capture
  // is the cache.)
  //
  // Wrap the compile in try/catch so a malformed `inputSchema:` on one typed
  // tool doesn't take down every typed tool authored after it in module-eval
  // order (the throw would otherwise escape the `trailblaze.tool<I, O>(spec,
  // handler)` call and skip every subsequent typed-tool declaration in the
  // same module). Skip validation for that tool, log + dispatch continues.
  let validator: ValidateFunction | null = null;
  if (inputSchema != null) {
    try {
      validator = ajv.compile(inputSchema);
    } catch (e: unknown) {
      const reason = e instanceof Error ? e.message : String(e);
      // eslint-disable-next-line no-console
      console.warn(
        `[trailblaze.tool] typed tool: ajv schema compile failed ` +
          `(${reason}). The tool will dispatch without input validation. Fix the ` +
          `inputSchema in the spec and re-run.`,
      );
    }
  }
  // Bridge adapter. The synthesized wrapper calls every registered handler as
  // `(args, ctx, client)`; the typed handler wants `(input, ToolContext)`. Build the
  // ToolContext from `client.tools` + `legacyCtx.memory` per-call so the typed handler
  // sees the same `ctx.tools.X(args)` namespace + `ctx.memory.get/set(...)` primitive
  // the imperative handler gets through `TrailblazeContext`.
  //
  // `legacyCtx` is the legacy `TrailblazeContext | undefined` the wrapper passes in
  // slot 2 of `__userHandler(args, ctx, __client)`. We extract `legacyCtx.memory` (a
  // [TrailblazeMemory] built by `fromMeta`) when present; otherwise fall back to an
  // empty memory so the handler doesn't crash on the bundle / no-envelope paths.
  // Bundle-path writes go to a per-invocation no-op buffer — they don't flush back to
  // the host, but that's the existing bundle-path behavior and the typed surface stays
  // identical to the subprocess path for authoring purposes.
  //
  // When [validator] is non-null, the adapter validates `args` BEFORE invoking the
  // user's handler. On rejection it throws a typed error the synthesized wrapper's
  // host-side dispatch catches and surfaces as an `isError` envelope — same flow a
  // handler-thrown error rides through, so a session-log reader sees one consistent
  // error shape.
  return async (args, legacyCtx, client) => {
    // Coerce args to a real object reference before validation so ajv's
    // `useDefaults` mutation has somewhere to write the JSDoc-derived
    // defaults. Pass the SAME reference to the handler so it sees the
    // defaults the LLM didn't supply. A throwaway `args ?? {}` here would
    // fill defaults into an object the handler never sees.
    //
    // The imperative path has a sibling block — see `validatedArgs` in
    // `registerPendingTools` (~line 850). The two sites must stay in
    // lockstep: any change to the coercion / validation / pass-through
    // contract here needs the same change there, or one path will see
    // defaults the other won't.
    const validatedArgs: Record<string, unknown> =
      args != null && typeof args === "object" && !Array.isArray(args)
        ? (args as Record<string, unknown>)
        : {};
    if (validator != null && !validator(validatedArgs)) {
      throw new TypedToolValidationError(validator.errors ?? []);
    }
    const memory: TrailblazeMemory = legacyCtx?.memory ?? createMemory(undefined);
    // `target` rides through from the legacy ctx untouched — `fromMeta` already
    // injects the `resolveAppId` / `resolveBaseUrl` method bindings onto it on
    // both the subprocess and on-device paths, so the typed handler sees the
    // same callable surface a `TrailblazeContext`-shaped handler would. When
    // the envelope had no target (no session target configured), the field
    // stays `undefined` and typed handlers must optional-chain.
    const toolContext: ToolContext = { tools: client.tools, memory, target: legacyCtx?.target };
    return handler(validatedArgs as TInput, toolContext);
  };
}

/**
 * Typed-tool validation failure. Thrown from inside the typed adapter so the
 * synthesized wrapper's host-side dispatch catches it the same way it catches a
 * handler-thrown error — `QuickJsToolHost`'s catch path maps `Error` instances onto
 * the `isError` envelope via the same path used for any runtime throw. The
 * `name = "ValidationError"` makes it identifiable from session logs without
 * resorting to `e instanceof`.
 */
class TypedToolValidationError extends Error {
  override name = "ValidationError";
  constructor(readonly ajvErrors: ReadonlyArray<ErrorObject>) {
    super(`Invalid arguments: ${formatAjvErrors(ajvErrors)}`);
  }
}

/**
 * Upper bound on the `Error.stack` text included in an error envelope. Deep async chains in
 * Node/bun can produce stack traces of tens or hundreds of KB; the envelope rides through
 * `*ToolLog.json` session logs and CI report artifacts where unbounded growth is a real
 * operational concern. 16 KB keeps the head-of-stack frames (where the actual failing line
 * lives) intact while truncating runaway tails. Authors with a longer reproducer can attach
 * the full stack from local `bun run` output if needed.
 */
const MAX_STACK_LENGTH = 16_384;

/**
 * Shared ajv instance used to compile every tool's input schema.
 *
 * **Why `Ajv2020`, not the default `Ajv`.** zod 4's `z.toJSONSchema(...)` emits a
 * `$schema: "https://json-schema.org/draft/2020-12/schema"` reference; the default
 * `Ajv` class only ships the draft-07 meta-schema and throws "no schema with key or
 * ref" on first compile against zod's output. `Ajv2020` carries the 2020-12 meta
 * out of the box. Typed-path schemas (analyzer-emitted) inherit
 * ts-json-schema-generator's draft-07-ish shape but don't carry an explicit
 * `$schema` keyword, so `Ajv2020` parses them too — 2020-12 is a superset of the
 * keywords ts-json-schema-generator uses.
 *
 * **`allErrors: true`** so a single dispatch surfaces every field-level mismatch
 * the LLM needs to fix, not just the first one — a one-shot self-correction round
 * trip is far cheaper than two.
 *
 * **`strict: false`** because the schemas come from two sources — zod's
 * `toJSONSchema()` output for the imperative path and analyzer-emitted JSON
 * Schema for the typed path — and both occasionally include keywords (e.g.
 * `$schema`, `additionalProperties: {}`) that ajv's strict-mode policing would
 * reject without changing the validation behavior we care about.
 *
 * **`useDefaults: true`** so JSDoc `@default` tags on interface fields auto-fill
 * the incoming `args` during validation. `ts-json-schema-generator` flows every
 * `@default`, `@minLength`, `@maximum`, `@pattern`, `@format` tag into the
 * emitted JSON Schema; without `useDefaults`, the `"default"` keyword is purely
 * advisory and the handler has to write defensive `nonEmptyString(input.query,
 * "Trailblazer")`-style fallbacks. With it on, an author who writes
 *
 *     interface SearchArgs {
 *       /** @default "Trailblazer" *\/ query?: string;
 *       /** @default true        *\/ openFirstResult?: boolean;
 *     }
 *
 * recovers the Kotlin-data-class-with-defaults shape — the handler sees
 * `input.query === "Trailblazer"` when the LLM sent `{}`, no defensive code
 * needed. **Caveat**: `useDefaults` only fills defaults on properties of objects
 * that already exist. A schema with `{ address: { properties: { country: {
 * default: "US" } } } }` will NOT materialize a missing `address` object out of
 * nowhere — only `args.address.country` if `args.address` is already present.
 * This matches ajv's documented behavior; we don't paper over it.
 *
 * **`coerceTypes: false`** explicit (the ajv default, but we set it for clarity
 * alongside `useDefaults`). The whole point of this validation pass is to catch
 * silent semantic bugs like `{ openFirstResult: "false" }` (string "false" is
 * truthy in JS, so `flag !== false` evaluates true and flips meaning); auto-
 * coercing strings to booleans would re-introduce the exact failure mode we're
 * trying to detect.
 *
 * **No cache between compile and use.** Each dispatch site compiles once and
 * captures the resulting `ValidateFunction` in the returned adapter's closure
 * (`defineTypedTool`) or the `server.registerTool` callback's closure
 * (`registerPendingTools`). The closure IS the cache — every tool is compiled
 * exactly once and the validator's lifetime matches the tool's. An earlier draft
 * carried a `Map` keyed by tool name + a synthesized counter; a review pointed
 * out that the typed-path counter generated a fresh key per definition so the
 * cache could never hit, and the imperative-path lookup only hit on
 * re-registration — the test bug `_clearPendingTools` was added to mask.
 * Dropping the cache removed that foot-gun by construction.
 */
const ajv = new Ajv2020({
  allErrors: true,
  strict: false,
  useDefaults: true,
  coerceTypes: false,
});

/**
 * Convert an ajv error list into a single field-level error message the LLM can
 * self-correct against. ajv's per-error shape carries an `instancePath` (e.g.
 * `/openFirstResult`) plus a human-readable `message` (e.g. `"must be boolean"`);
 * we join the two into "<path>: <message>" and concatenate with semicolons so the
 * full set is one readable line in the session log.
 *
 * Root-level errors (missing-required on the top object) have `instancePath: ""`;
 * surfaced as `(root)` so a reader scanning the envelope text doesn't see a bare
 * leading colon.
 */
function formatAjvErrors(errors: ReadonlyArray<ErrorObject>): string {
  return errors
    .map((e) => {
      const path = e.instancePath.length > 0 ? e.instancePath : "(root)";
      return `${path}: ${e.message ?? "validation failed"}`;
    })
    .join("; ");
}

/**
 * Build the `isError: true` envelope returned when ajv rejects the incoming `args`.
 * Shape mirrors the handler-throw catch path in [registerPendingTools] so a
 * session-log reader sees one consistent error format regardless of whether the
 * failure came from validation or from inside the handler. The `name: "ValidationError"`
 * prefix is a deliberate parallel to the V8 `Error: <message>` shape — the prefix
 * lets a reader filtering by class quickly bucket the failure mode.
 */
function buildValidationErrorEnvelope(
  toolName: string,
  errors: ReadonlyArray<ErrorObject>,
): TrailblazeToolResult {
  return {
    isError: true,
    content: [
      {
        type: "text",
        text:
          `ValidationError: tool '${toolName}' received invalid arguments — ${formatAjvErrors(errors)}`,
      },
    ],
  };
}

/**
 * Extract a JSON Schema usable by ajv from the imperative spec's `inputSchema`.
 *
 * **Input shape.** After [withPassthroughInputSchema] runs, the field is either a
 * zod schema instance (with `safeParse`) or undefined — raw shapes have been wrapped
 * to `z.object(shape).passthrough()`. We never see a bare raw-shape here.
 *
 * **Returns null in three cases.**
 *  - `spec.inputSchema` is absent — no schema declared, no validation.
 *  - The schema doesn't expose `safeParse` — not a zod instance, defensive fallback
 *    for a JS-only caller bypassing the type system. We'd rather skip validation
 *    than crash at registration time on an unknown shape.
 *  - `z.toJSONSchema` itself throws — zod schema that can't be lowered (e.g. a
 *    `ZodCustom` refinement, a function type, a recursive shape ts-json-schema-
 *    generator's zod port doesn't support).
 *
 * **Why fail-open, not fail-closed.** If we can't derive a schema we can't
 * validate, but blocking registration would take the tool offline for what is
 * almost always an author-visible authoring bug. Better policy: log + skip
 * validation for that tool, let the rest of the registry come up, and let the
 * author see the warning the next time the daemon starts. The log line is the
 * key — silent skipping is what this pass exists to prevent in the first place.
 */
function specToJsonSchema(
  toolName: string,
  spec: TrailblazeToolSpec,
): Record<string, unknown> | null {
  const schema = spec.inputSchema as { safeParse?: unknown } | undefined;
  if (schema == null) return null;
  if (typeof schema.safeParse !== "function") return null;
  try {
    return z.toJSONSchema(schema as z.ZodTypeAny) as Record<string, unknown>;
  } catch (e: unknown) {
    const reason = e instanceof Error ? e.message : String(e);
    // eslint-disable-next-line no-console
    console.warn(
      `[trailblaze.tool] tool '${toolName}': skipping ajv validation — zod ` +
        `schema could not be lowered to JSON Schema (${reason}). The tool will ` +
        `dispatch without input validation. To enable validation, replace the ` +
        `inputSchema with a shape zod's toJSONSchema can serialize.`,
    );
    return null;
  }
}

/**
 * Declare a Trailblaze tool.
 *
 * Three call styles share this name:
 *
 *  - **Imperative (MCP-registered).** `tool(name, spec, handler): void` — buffers a
 *    registration until [run] connects the MCP server. Returns `void`. Used by tools
 *    that go through the host-subprocess path (MCP-over-stdio transport). The `spec`
 *    here is the legacy MCP-registration shape [TrailblazeToolSpec] (description,
 *    inputSchema, outputSchema, raw `_meta`).
 *  - **Typed declarative — bare handler.** `tool<I, O>(handler)` — returns the
 *    handler as a [TypedToolDefinition], carrying `<I, O>` type information that
 *    the analyzer extracts into JSON-Schema and [TrailblazeToolMap] entries. Both
 *    type parameters have defaults so authors pick the lightest shape that fits:
 *
 *      trailblaze.tool(async (_, ctx) => "done")                              // <{}, string>
 *      trailblaze.tool<MyInput>(async (i, ctx) => `hi ${i.name}`)             // <MyInput, string>
 *      trailblaze.tool<MyInput, MyOutput>(async (i, ctx) => ({ ... }))        // fully typed
 *
 *  - **Typed declarative — with spec.** `tool<I, O>(spec, handler)` — same as the
 *    bare-handler form, but the first positional arg is a [TrailblazeTypedToolSpec]
 *    carrying structured config (`supportedPlatforms`, `requiresContext`,
 *    `requiresHost`, `supportedDrivers`). The spec is captured by the build-time
 *    analyzer for synthesizing `_meta` on the MCP tool advertisement; at runtime it's
 *    discarded (the spec is a compile-time signal, not a runtime value). Use this
 *    overload when a tool needs gating or driver/platform restriction; reach for the
 *    bare-handler form when no metadata is needed.
 *
 *      trailblaze.tool<I, O>(
 *        { supportedPlatforms: ["web"], requiresContext: true },
 *        async (input, ctx) => { ... },
 *      )
 *
 * Tool descriptions live in TSDoc on the `export const X = trailblaze.tool(...)`
 * binding — there is intentionally no `description` field on [TrailblazeTypedToolSpec].
 * The analyzer reads the binding's TSDoc and the input/output interface field TSDoc to
 * synthesize the runtime tool description and per-property `description` keys.
 *
 * Dispatch is by first-arg type:
 *  - function → typed bare-handler
 *  - plain object + function second-arg → typed with-spec
 *  - string → imperative
 *  - anything else → falls through to imperative push (registration-side error)
 */
export function tool<TInput = Record<string, never>, TResult = string>(
  handler: (input: TInput, ctx: ToolContext) => Promise<TResult>,
): TypedToolDefinition<TInput, TResult>;
export function tool<TInput = Record<string, never>, TResult = string>(
  spec: TrailblazeTypedToolSpec,
  handler: (input: TInput, ctx: ToolContext) => Promise<TResult>,
): TypedToolDefinition<TInput, TResult>;
export function tool(name: string, spec: TrailblazeToolSpec, handler: TrailblazeToolHandler): void;
export function tool(
  arg0:
    | string
    | TrailblazeTypedToolSpec
    | ((input: unknown, ctx: ToolContext) => Promise<unknown>),
  arg1?:
    | TrailblazeToolSpec
    | ((input: unknown, ctx: ToolContext) => Promise<unknown>),
  arg2?: TrailblazeToolHandler,
): TypedToolDefinition<unknown, unknown> | void {
  // Typed bare-handler: tool<I, O>(handler).
  if (typeof arg0 === "function") {
    return defineTypedTool(
      arg0 as (input: unknown, ctx: ToolContext) => Promise<unknown>,
    );
  }
  // Typed with-spec: tool<I, O>(spec, handler).
  //
  // The spec object is captured by the build-time analyzer
  // (`ScriptedToolDefinitionAnalyzer`) which walks each `trailblaze.tool(...)` call
  // site via the TypeScript AST. At runtime we discard the spec value and adapt the
  // handler the same way as the bare-handler overload — the analyzer is what
  // synthesizes the MCP `_meta` for tool advertisement, not this function. Capturing
  // the spec as a runtime property would create an extra contract for callers to
  // depend on; deferring until a concrete use case appears keeps the surface minimal.
  //
  // The condition deliberately rejects `null` (which is `typeof === "object"`), arrays
  // (which are also `typeof === "object"`), and any second-arg that isn't a function.
  // It ALSO requires `arg2 === undefined` — without that guard, an ill-shaped
  // 3-arg imperative call like `tool({nameLike: ...}, async () => {}, anything)`
  // would silently route to the typed branch and never get registered as an MCP
  // tool. Restricting to exactly two args matches the overload signature and
  // surfaces ill-shaped 3-arg calls through the imperative branch's diagnostics
  // instead. Existing tests pin that `tool({})`, `tool(null, ...)`, and
  // `tool([], ...)` continue to fall through to the imperative branch — the
  // typed-with-spec branch only fires when the call shape unambiguously matches
  // `(plainObject, function)` with no third argument.
  if (
    typeof arg0 === "object" &&
    arg0 !== null &&
    !Array.isArray(arg0) &&
    typeof arg1 === "function" &&
    arg2 === undefined
  ) {
    // Plumb the spec's optional `inputSchema:` JSON Schema through to the runtime
    // adapter so the typed dispatch boundary can ajv-validate args before invoking
    // the handler. Absent / non-object values are passed as `undefined`, which
    // [defineTypedTool] interprets as "no validation."
    const specObj = arg0 as TrailblazeTypedToolSpec;
    const inlineSchema = (specObj.inputSchema && typeof specObj.inputSchema === "object" && !Array.isArray(specObj.inputSchema))
      ? (specObj.inputSchema as Record<string, unknown>)
      : undefined;
    return defineTypedTool(
      arg1 as (input: unknown, ctx: ToolContext) => Promise<unknown>,
      inlineSchema,
    );
  }
  // Imperative path. The overloads above guarantee well-shaped callers reach this
  // branch only with the (name, spec, handler) signature; ill-shaped callers (e.g.
  // null/array first-arg, missing third-arg) still reach here and surface as MCP
  // registration errors with the offending values in the diagnostic.
  pendingTools.push({
    name: arg0 as string,
    spec: arg1 as TrailblazeToolSpec,
    handler: arg2!,
  });
}

/**
 * Internal: drain the pending-tools queue into a live [McpServer]. Exposed so [run] stays
 * testable — tests can construct a server, call this, assert `server.tools` without needing a
 * real stdio transport.
 */
export function registerPendingTools(server: McpServer): void {
  // Pop from the front rather than iterate-then-clear so a tool registered *during* this loop
  // (unusual but possible if a module's top-level code has side effects during import) still
  // gets registered, not silently dropped on the floor.
  while (pendingTools.length > 0) {
    const pending = pendingTools.shift()!;
    const preparedSpec = withPassthroughInputSchema(pending.spec);
    // Compile the input schema into an ajv validator once at registration. zod is
    // lowered to JSON Schema via `z.toJSONSchema(...)` inside [specToJsonSchema];
    // absent / non-zod inputs return `null` and validation is skipped entirely. The
    // MCP SDK already enforces the zod shape on the wire (it calls `safeParse` per
    // dispatch), so ajv here is defense-in-depth — but it ALSO catches the case
    // where the SDK's zod-vs-JSON-Schema coercion let a malformed value through to
    // the handler, which is the exact failure mode this code path exists to
    // prevent.
    //
    // The compiled validator is captured in the `server.registerTool` callback's
    // closure below — `ajv.compile` runs exactly once per registered tool, and the
    // closure capture serves as the cache. (See the kdoc on the [ajv] instance
    // for the dropped-cache history.)
    //
    // Wrap the compile in try/catch so a single malformed-schema tool doesn't
    // abort the whole registration loop. Without this, a bad `inputSchema:` on
    // tool N kills tools N+1..end with the same "no schema with key or ref"
    // throw and no tool-name context — every dependent tool silently goes
    // unregistered. On compile failure, log + skip validation for that tool;
    // the tool still registers, just without ajv.
    const jsonSchema = specToJsonSchema(pending.name, preparedSpec);
    let validator: ValidateFunction | null = null;
    if (jsonSchema != null) {
      try {
        validator = ajv.compile(jsonSchema);
      } catch (e: unknown) {
        const reason = e instanceof Error ? e.message : String(e);
        // eslint-disable-next-line no-console
        console.warn(
          `[trailblaze.tool] tool '${pending.name}': ajv schema compile failed (${reason}). ` +
            `The tool will dispatch without input validation. Fix the schema and ` +
            `restart the daemon to re-enable validation.`,
        );
      }
    }
    // Bridge cast on the handler signature only.
    //
    // `server.registerTool` types the callback against the MCP SDK's full `CallToolResult`
    // content union — discriminated variants for text/image/audio/resource_link/embedded
    // resource. Authors of Trailblaze tools work against the simpler [TrailblazeToolResult]
    // (kdoc: "re-declared here so the SDK doesn't impose its import graph on every tool
    // file"), which intentionally widens `content` to accept either a known `text` literal
    // OR a generic `Record<string, unknown>` for one-off variants the author wants to pass
    // through unmodified.
    //
    // The cast scopes the variance gap to this one boundary — the wire-side runtime accepts
    // any object the MCP SDK serializes, so the loosened static type stays correct in
    // practice. Without the cast, every Trailblaze handler would have to either import the
    // SDK's content-variant types directly or narrow its own return value, defeating the
    // "tools don't import the MCP SDK" property this file's authoring surface promises.
    server.registerTool(
      pending.name,
      preparedSpec,
      (async (args: Record<string, unknown>, extra: unknown) => {
        // Build a server-backed logger so `ctx.logger.info(...)` emits MCP
        // `notifications/message` (host-routable structured logs) AND mirrors to stderr for
        // immediate CI visibility. See `./logger.ts` for the dual-path rationale. The logger
        // construction itself can't throw (it just captures references), so it lives outside
        // the try/catch — but the envelope/ctx parsing and the handler invocation are all
        // inside the catch so any throw from `extractMeta` / `fromMeta` / the handler itself
        // still surfaces through the same `isError` envelope shape with name + message + stack.
        const logger = createLogger(server, pending.name);
        // Catch handler throws (sync or async-reject) and surface them through an `isError`
        // envelope that includes `error.name`, `error.message`, AND `error.stack`. Without this
        // catch, the throw escapes the MCP SDK boundary, which wraps it into a result envelope
        // whose text content is just `error.message` — the JS stack is lost, and an author
        // debugging from session logs alone has no breadcrumb to the failing line. Surfacing
        // the stack through `content: [{ type: "text", text: ... }]` rides the same wire path
        // that `toTrailblazeToolResult` (Kotlin side) already maps to
        // `ExceptionThrown.errorMessage`, so the change is purely additive — no SDK or host
        // API change. Matches the in-process QuickJS host's parallel fix in
        // `QuickJsToolHost.callTool` (PR #2941).
        //
        // ## Stack-trace PII assumption
        //
        // `Error.stack` includes absolute file paths (e.g. `/Users/$USER/...` locally,
        // `/home/runner/...` in CI). Session logs are assumed to stay within the dev/CI trust
        // boundary; this code does not redact paths. If session logs ever start shipping to
        // external dashboards or shared analytics, add a redaction step here.
        try {
          // ajv runtime guard. Fail-fast BEFORE the meta/ctx extraction so a malformed
          // `args` payload produces a precise field-level diagnostic — the prior behavior
          // was to let the cast slip through to the handler, which would either crash
          // with an opaque `Cannot read properties of undefined` deep inside the
          // handler body, or worse, silently coerce (a string "false" is truthy in JS,
          // so `input.openFirstResult !== false` flips meaning). Returning the
          // [buildValidationErrorEnvelope] shape rides the same wire path as a
          // handler-throw envelope — a session-log reader sees one consistent error
          // format regardless of failure mode.
          //
          // Coerce args to a real object reference before validation so ajv's
          // `useDefaults` mutation has somewhere to write the JSDoc-derived
          // defaults. Pass the SAME reference to the handler so it sees the
          // defaults the LLM didn't supply.
          //
          // The typed path has a sibling block — see `validatedArgs` in
          // `defineTypedTool` (~line 430). The two sites must stay in
          // lockstep: any change to the coercion / validation / pass-through
          // contract here needs the same change there, or one path will see
          // defaults the other won't.
          const validatedArgs: Record<string, unknown> =
            args != null && typeof args === "object" && !Array.isArray(args)
              ? args
              : {};
          if (validator != null && !validator(validatedArgs)) {
            // Mirror the field-level errors through the structured logger so an
            // operator watching session logs sees one log line per dispatch
            // failure — easier to alert on a spike than parsing envelope text
            // from every failed call. The envelope itself still carries the
            // full diagnostic for the LLM to self-correct against.
            const errs = validator.errors ?? [];
            logger.warn(
              `input validation failed: ${formatAjvErrors(errs)}`,
            );
            return buildValidationErrorEnvelope(pending.name, errs);
          }
          // The raw MCP SDK threads the request's `_meta` through as part of `extra` (the
          // CallToolRequest extra object, which mirrors `RequestHandlerExtra` in the SDK).
          // Exact access path: `extra.request.params._meta`. If any link is missing (older
          // SDK, test harness skipping the envelope), `fromMeta` returns undefined and the
          // handler branches. Inside the try/catch so a malformed envelope produces an
          // `isError` result instead of an uncaught throw at the MCP SDK boundary.
          const meta = extractMeta(extra);
          const ctx = fromMeta(meta, logger);
          // Fresh per-invocation client so each handler call captures its own envelope —
          // concurrent `tools/call` requests within the same MCP session would otherwise
          // race on a shared one.
          const client = createClient(ctx);
          const result = await Promise.resolve(pending.handler(validatedArgs, ctx, client));
          // Successful return — drain any `ctx.memory.set/delete` writes into a
          // memoryDelta on the result envelope's `_meta`. The Kotlin host applies this
          // delta into the shared `AgentMemory` after `tools/call` resolves. A handler
          // that throws skips this branch (the catch below produces an isError envelope
          // without a delta), so the host's memory is left untouched — that's the
          // transactional contract.
          return attachMemoryDelta(result, ctx);
        } catch (e: unknown) {
          // Build the error envelope defensively. Every property access on `e` could itself
          // throw if the caller supplied a hostile object (throwing getter on `name`,
          // `message`, `stack`, a `toString` that throws, a proxy `has` trap). Each access
          // is wrapped in its own try/catch so a hostile throw can't re-introduce the
          // lost-envelope failure mode this catch exists to prevent — mirrors the defenses
          // QuickJsToolHost.callTool added during its review (#2941). Non-Error throws
          // (`throw "string"`, `throw 42`, `throw 0`, `throw null`, `throw undefined`, …)
          // all reach here too — `instanceof Error` is false → the fallback path produces a
          // valid envelope for each.
          const isErrorObj = e instanceof Error;
          let errName = "Error";
          if (isErrorObj) {
            try {
              const n = (e as Error).name;
              if (typeof n === "string" && n.length > 0) errName = n;
            } catch {
              // Throwing `name` getter — keep the default prefix.
            }
          }
          let errMessage: string;
          try {
            errMessage = isErrorObj ? (e as Error).message : String(e);
          } catch {
            errMessage = "<unstringifiable thrown value>";
          }
          if (errMessage === undefined || errMessage === null) {
            errMessage = String(errMessage);
          }
          // Build the envelope text from the constructed prefix + the JS stack. The two
          // engines we run in produce different `Error.stack` shapes:
          //
          //  - **V8 (Node/bun, host-subprocess path)**: `stack` already begins with
          //    `${name}: ${message}\n  at ...`. Using it verbatim would duplicate the header
          //    if we always prepend.
          //  - **QuickJS (on-device bundle path)**: `stack` is frames-only — just
          //    `  at fn (file:line:col)\n  at ...` with no header. Using `stack` verbatim
          //    would drop the error's name and message from the envelope, breaking the
          //    "session-log reader sees what went wrong" contract documented in the
          //    README's "Error handling" section.
          //
          // Strategy: always start from the constructed `${name}: ${message}` header, then
          // append the stack — minus its own header if it begins with one (V8) — so the
          // result is exactly one header line followed by frames on either engine. Cap the
          // included stack at MAX_STACK_LENGTH so a deep async chain (which can easily
          // exceed 50 KB) doesn't bloat session logs.
          let envelopeText = `${errName}: ${errMessage}`;
          if (isErrorObj) {
            try {
              const stack = (e as Error).stack;
              if (typeof stack === "string" && stack.length > 0) {
                // Strip a leading header line if the engine already produced one (V8) so
                // we don't double-print it. Two shapes the engine may emit:
                //
                //  - `${name}: ${message}\n  at ...`  — the normal case.
                //  - `${name}\n  at ...`              — V8 omits the `: ` when `message`
                //    is the empty string (`new Error("")`).
                //
                // Match against both; partial matches (e.g. `Error:` without our exact
                // message) fall through to "stack appended as-is", which is the safer
                // default.
                const headerWithMessage = `${errName}: ${errMessage}`;
                const headerBareName = errName;
                let framesOnly = stack;
                if (stack.startsWith(headerWithMessage)) {
                  framesOnly = stack.slice(headerWithMessage.length).replace(/^\r?\n/, "");
                } else if (errMessage.length === 0 && stack.startsWith(headerBareName + "\n")) {
                  // Empty-message form. Slice past the bare name AND its trailing newline
                  // so the frames append directly under our constructed `${name}: ` line.
                  framesOnly = stack.slice(headerBareName.length).replace(/^\r?\n/, "");
                }
                const combined = framesOnly.length > 0
                  ? `${envelopeText}\n${framesOnly}`
                  : envelopeText;
                envelopeText = combined.length > MAX_STACK_LENGTH
                  ? `${combined.slice(0, MAX_STACK_LENGTH)}\n...[stack truncated]`
                  : combined;
              }
            } catch {
              // Throwing `stack` getter — fall through with the prefix-only text.
            }
          }
          return {
            isError: true,
            content: [{ type: "text", text: envelopeText }],
          };
        }
        // Positional cast (`Parameters<...>[2]`) rather than the SDK's named `ToolCallback`
        // alias because `registerTool` is generic over `InputArgs` — naming the callback
        // type forces a specific `InputArgs` binding (e.g. `ZodRawShapeCompat`) that the
        // wider [TrailblazeToolSpec.inputSchema] union doesn't satisfy. Position-based
        // capture sidesteps the inference and lets each call use whichever `InputArgs` the
        // SDK derives from the spec at the call site.
      }) as Parameters<typeof server.registerTool>[2],
    );
  }
}

/**
 * Stamp the memory diff captured by `ctx.memory` onto the tool result's `_meta`. The
 * Kotlin host reads `_meta.trailblaze.memoryDelta` / `_meta.trailblaze.memoryDeletions`
 * after a successful `tools/call` and merges them into the shared `AgentMemory`.
 *
 * No-op when the handler made no memory changes, or when `ctx` was undefined (envelope
 * absent — e.g. an ad-hoc MCP client outside a Trailblaze session). Preserves any
 * existing `_meta` keys the handler set explicitly on its return value.
 */
function attachMemoryDelta(result: unknown, ctx: TrailblazeContext | undefined): unknown {
  if (ctx === undefined) return result;
  const drainable = ctx.memory as DrainableMemory;
  if (typeof drainable[DRAIN_DELTA] !== "function") return result;
  const delta = drainable[DRAIN_DELTA]();
  const setKeys = Object.keys(delta.sets);
  if (setKeys.length === 0 && delta.deletions.length === 0) return result;
  const trailblaze: Record<string, unknown> = {};
  if (setKeys.length > 0) trailblaze[META_KEY_MEMORY_DELTA] = delta.sets;
  if (delta.deletions.length > 0) trailblaze[META_KEY_MEMORY_DELETIONS] = delta.deletions;

  // Primitive / null / undefined return: the typed `trailblaze.tool<I, O>(handler)` surface
  // lets handlers return a bare string ("ok") or number — `__normalizeResult` in the bundle
  // wrapper handles those, and `registerTool`'s SDK auto-wraps under MCP semantics. We can't
  // mutate a primitive to attach `_meta`, so wrap it in a content envelope first and stamp
  // `_meta` onto the envelope. Without this, a handler that writes memory AND returns a
  // primitive silently loses the writes — the bug `attachMemoryDelta` exists to prevent.
  if (typeof result !== "object" || result === null) {
    const wrapped: Record<string, unknown> = {
      content: result === undefined || result === null
        ? []
        : [{ type: "text", text: typeof result === "string" ? result : JSON.stringify(result) }],
      _meta: { [META_KEY_TRAILBLAZE]: trailblaze },
    };
    return wrapped;
  }

  const resultRecord = result as Record<string, unknown>;
  const existingMeta =
    typeof resultRecord["_meta"] === "object" && resultRecord["_meta"] !== null
      ? (resultRecord["_meta"] as Record<string, unknown>)
      : {};
  const existingTrailblaze =
    typeof existingMeta[META_KEY_TRAILBLAZE] === "object" && existingMeta[META_KEY_TRAILBLAZE] !== null
      ? (existingMeta[META_KEY_TRAILBLAZE] as Record<string, unknown>)
      : {};
  const merged: Record<string, unknown> = { ...existingTrailblaze, ...trailblaze };
  return {
    ...resultRecord,
    _meta: { ...existingMeta, [META_KEY_TRAILBLAZE]: merged },
  };
}

/** Drill into the MCP SDK's per-call `extra` object for `request.params._meta`. */
function extractMeta(extra: unknown): unknown {
  if (typeof extra !== "object" || extra === null) return undefined;
  const bag = extra as Record<string, unknown>;
  // Newer SDK shape: `extra.request.params._meta`.
  const request = bag["request"];
  if (typeof request === "object" && request !== null) {
    const params = (request as Record<string, unknown>)["params"];
    if (typeof params === "object" && params !== null) {
      return (params as Record<string, unknown>)["_meta"];
    }
  }
  // Fallback: some SDK revisions flatten `_meta` onto the extra object directly. Be lenient
  // here — if a version ships where only this works, the SDK still succeeds.
  return bag["_meta"];
}

/**
 * Wrap the author's `inputSchema` raw shape with `.passthrough()` so unknown keys survive
 * validation. Default zod `z.object(shape)` silently strips unknown keys — harmless when an
 * author's schema enumerates every expected field, but surprising for the vibe-up `inputSchema: {}`
 * pattern where the author intentionally left the shape empty and expects raw args through.
 * For non-raw-shape inputs (already-built zod schemas, null/undefined), leave untouched —
 * those authors have opted into whatever behavior their schema implies.
 *
 * Detection mirrors `@modelcontextprotocol/sdk`'s `isZodRawShape`: an object whose enumerable
 * own values are all either undefined or zod schemas (have a `safeParse` method).
 */
function withPassthroughInputSchema(spec: TrailblazeToolSpec): TrailblazeToolSpec {
  const schema = spec.inputSchema;
  if (!isRawShape(schema)) return spec;
  return { ...spec, inputSchema: z.object(schema).passthrough() };
}

function isRawShape(schema: unknown): schema is z.ZodRawShape {
  if (typeof schema !== "object" || schema === null || Array.isArray(schema)) return false;
  // A zod schema instance has a `safeParse` method on its prototype; a raw shape is a plain
  // object whose values are zod schemas (or undefined for optional entries). Empty object
  // qualifies as raw shape too — that's the vibe-up "no-arg tool" default.
  if ((schema as { safeParse?: unknown }).safeParse != null) return false;
  for (const key of Object.keys(schema)) {
    const value = (schema as Record<string, unknown>)[key];
    // Only `undefined` slots are OK (caller intentionally omitted an entry). `null` means
    // the author wrote an explicit `null` in the shape — that's not a zod schema and would
    // throw at `z.object(shape)` build time, so reject it here with the clearer "not a raw
    // shape" reply (caller leaves the input alone and MCP SDK's own validator surfaces the
    // precise error, if any).
    if (value === undefined) continue;
    if (typeof value !== "object" || value === null || (value as { safeParse?: unknown }).safeParse == null) {
      return false;
    }
  }
  return true;
}

/** Clears the pending queue. Exposed for tests only. */
export function _clearPendingTools(): void {
  pendingTools.length = 0;
}
