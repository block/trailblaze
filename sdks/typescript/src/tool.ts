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
import { z } from "zod";

import { createClient, type TrailblazeClient } from "./client.js";
import { fromMeta, type TrailblazeContext } from "./context.js";
import { createLogger } from "./logger.js";

/**
 * Spec for a Trailblaze-authored tool. Intentionally a shallow wrapper over the raw MCP SDK's
 * `registerTool` spec shape — anything you'd pass to `server.registerTool(name, spec, handler)`
 * still works, just without having to construct a `McpServer` yourself.
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
 * - [client] — always provided, even when [ctx] is undefined. Exposes `callTool(name, args)`
 *   for composing other Trailblaze tools via the daemon's `/scripting/callback` endpoint.
 *   When [ctx] is undefined the client throws on any callTool attempt with a clear preflight
 *   error — handlers that never call back simply ignore the argument.
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
 * Declare a Trailblaze tool. Buffers the registration until [run] connects the MCP server,
 * so authors can call this at module top-level in any order.
 */
export function tool(name: string, spec: TrailblazeToolSpec, handler: TrailblazeToolHandler): void {
  pendingTools.push({ name, spec, handler });
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
          return await Promise.resolve(pending.handler(args, ctx, client));
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
