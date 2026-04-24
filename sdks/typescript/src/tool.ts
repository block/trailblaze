// `trailblaze.tool(name, spec, handler)` — the SDK's authoring surface for declaring an MCP
// tool. Wraps `server.registerTool` so authors never touch the raw MCP SDK unless they want
// to, and extracts the `TrailblazeContext` envelope up-front so every handler sees the same
// typed shape regardless of how the host is invoking it.
//
// Tools authored with this helper buffer until `trailblaze.run()` starts the stdio server —
// that ordering lets authors declare all their tools at module top-level without worrying
// about whether the MCP server has been created yet.

import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { createClient, type TrailblazeClient } from "./client.js";
import { fromMeta, type TrailblazeContext } from "./context.js";

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
   * MCP input schema — plain JSON schema object or a zod shape. Passed through to
   * `server.registerTool` unchanged.
   */
  inputSchema?: Record<string, unknown>;
  /**
   * Output schema, if your tool returns structured data. Also passed through.
   */
  outputSchema?: Record<string, unknown>;
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
    server.registerTool(pending.name, pending.spec, async (args: Record<string, unknown>, extra: unknown) => {
      // The raw MCP SDK threads the request's `_meta` through as part of `extra` (the
      // CallToolRequest extra object, which mirrors `RequestHandlerExtra` in the SDK). Exact
      // access path: `extra.request.params._meta`. If any link is missing (older SDK, test
      // harness skipping the envelope), `fromMeta` returns undefined and the handler branches.
      const meta = extractMeta(extra);
      const ctx = fromMeta(meta);
      // Fresh per-invocation client so each handler call captures its own envelope — concurrent
      // `tools/call` requests within the same MCP session would otherwise race on a shared one.
      const client = createClient(ctx);
      return pending.handler(args, ctx, client);
    });
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

/** Clears the pending queue. Exposed for tests only. */
export function _clearPendingTools(): void {
  pendingTools.length = 0;
}
