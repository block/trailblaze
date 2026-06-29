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

import { createClient, type TrailblazeClient } from "./client.js";
import { fromMeta, type TrailblazeContext } from "./context.js";
import { createLogger } from "./logger.js";
import {
  DRAIN_DELTA,
  META_KEY_MEMORY_DELETIONS,
  META_KEY_MEMORY_DELTA,
  META_KEY_TRAILBLAZE,
  type DrainableMemory,
} from "./memory.js";
import {
  defineTypedTool,
  formatAjvErrors,
  TypedToolValidationError,
  type EmptyInput,
  type ToolContext,
  type TrailblazeTypedToolSpec,
  type TypedToolDefinition,
} from "./tool-core.js";

// Re-export the typed-authoring core so existing importers of `./tool.js`
// (e.g. `index.ts`) keep resolving these from here unchanged after the slim/full split.
export { defineTypedTool, formatAjvErrors, TypedToolValidationError } from "./tool-core.js";
export type {
  ToolContext,
  EmptyInput,
  TypedToolDefinition,
  TrailblazeTypedToolSpec,
} from "./tool-core.js";

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
 * `requiresHost`, `supportedDrivers`) plus an optional `description` (the typed
 * spec's `description`, when omitted, falls back to the TSDoc above the
 * `export const`, which the analyzer reads — see [TrailblazeTypedToolSpec] for
 * the full YAML > spec > TSDoc precedence).
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
//
// The typed authoring core (`ToolContext`, `EmptyInput`, `TrailblazeTypedToolSpec`,
// `TypedToolDefinition`, `TypedToolValidationError`, `formatAjvErrors`, and `defineTypedTool`)
// lives in `./tool-core.ts` — the slim, dependency-light half shared with the in-process
// profile. It is imported + re-exported at the top of this file so importers of `./tool.js`
// keep resolving these from here unchanged. The full path (this file) injects an ajv-backed
// validator compiler into `defineTypedTool` (see `ajvCompile` below) so typed tools keep
// runtime input validation; the slim path injects nothing.

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
 * ajv-backed validator compiler injected into the typed-authoring core's [defineTypedTool].
 * The core stays ajv-free (the slim in-process profile injects nothing); the full path passes
 * this so typed tools keep their runtime input validation. Thin closure over the shared [ajv]
 * instance — the closure capture is the validator cache, same as everywhere else in this file.
 */
const ajvCompile = (schema: Record<string, unknown>): ValidateFunction => ajv.compile(schema);

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
 *    carrying structured config (`description`, `supportedPlatforms`, `requiresContext`,
 *    `requiresHost`, `supportedDrivers`). The spec is captured by the build-time
 *    analyzer for synthesizing the tool advertisement (description + `_meta`); at runtime
 *    it's discarded (the spec is a compile-time signal, not a runtime value). Use this
 *    overload when a tool needs gating, driver/platform restriction, or an explicit
 *    LLM-facing description; reach for the bare-handler form when no metadata is needed.
 *
 *      trailblaze.tool<I, O>(
 *        { description: "Open the sample app.", supportedPlatforms: ["web"] },
 *        async (input, ctx) => { ... },
 *      )
 *
 * A typed tool's LLM-facing description resolves as YAML sidecar `description:` > the spec's
 * `description` > the TSDoc above the `export const X = trailblaze.tool(...)` binding. When the
 * spec omits `description`, the analyzer falls back to that TSDoc (the historical behavior); it
 * always reads the input/output interface field TSDoc to synthesize the per-property
 * `description` keys regardless.
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
  // Typed bare-handler: tool<I, O>(handler). Inject the ajv compiler so the full path keeps
  // runtime input validation (there's no inline schema here, so it only matters if a future
  // analyzer-injected sidecar supplies one).
  if (typeof arg0 === "function") {
    return defineTypedTool(
      arg0 as (input: unknown, ctx: ToolContext) => Promise<unknown>,
      undefined,
      ajvCompile,
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
      ajvCompile,
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
