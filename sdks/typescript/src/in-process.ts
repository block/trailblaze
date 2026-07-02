// SLIM / in-process profile entry for `@trailblaze/scripting` — the default runtime for tools
// that run in-process (on-device QuickJS bundle, daemon in-process host). It is the
// bundle-aliased target the build wires up for every tool that does NOT declare
// `runtime: subprocess`.
//
// The whole point of this entry is bundle weight. It pulls in ONLY the typed authoring core
// (`./tool-core.js`), which value-imports just `createMemory` (pure) — no
// `@modelcontextprotocol/sdk`, no ajv, no zod. The `tool()` here delegates to the core's
// `defineTypedTool` WITHOUT injecting an ajv compiler, so typed tools dispatch without runtime
// input validation (the analyzer-emitted schema + MCP advertisement remain the source of truth).
// The result is a KB-scale bundle instead of the ~1 MB MCP-server bundle the full profile
// (`./sub-process.js`) produces.
//
// Two things are intentionally unavailable here vs the full profile:
//  - The imperative `tool(name, spec, handler)` form (it needs `server.registerTool` + zod).
//  - `trailblaze.run()` (it needs the MCP stdio server).
// Both throw a clear, actionable error pointing the author at `runtime: subprocess` if they
// genuinely need the full MCP runtime.

import {
  defineTypedTool,
  type EmptyInput,
  type ToolContext,
  type TrailblazeTypedToolSpec,
  type TrailheadSpec,
  type TypedToolDefinition,
} from "./tool-core.js";

// Full lightweight authoring surface — re-exported so this slim entry is a drop-in for
// `@trailblaze/scripting` when the bundler aliases it. These are everything `index.ts` exposes that
// an in-process tool can legitimately reach for (selectors, conditionals, view hierarchy,
// context/client types, zod, the built-in-tool type augmentation) — NONE of which pull
// `@modelcontextprotocol/sdk`, ajv, or `run.ts` into the bundle. esbuild tree-shakes whatever a
// given tool doesn't import, so the per-tool bundle stays KB-scale while a tool that reaches for
// `selectors` / `captureViewHierarchy` / `z` still resolves. Without these, aliasing
// `@trailblaze/scripting` to this entry would break any in-process tool using the broader surface
// (the full-only `run` + imperative `tool` are intentionally NOT here — `run`/`tool` below are the
// slim overrides). Keep this list in sync with `index.ts`.
export { z } from "zod";
export {
  fromMeta,
  type TrailblazeContext,
  type TrailblazeDevice,
  type TrailblazeLogger,
  type TrailblazeLogLevel,
  type TrailblazeMemory,
  type TrailblazeTarget,
} from "./context.js";
export type {
  TrailblazeCallToolResult,
  TrailblazeClient,
  TrailblazeToolEntry,
  TrailblazeToolMap,
} from "./client.js";
export {
  captureViewHierarchy,
  ConditionalActionFailedError,
  runConditionalActions,
  type ConditionalAction,
  type ViewHierarchy,
} from "./conditional-action.js";
export {
  selectors,
  type Bounds,
  type DriverNodeMatchAndroidAccessibility,
  type DriverNodeMatchAndroidMaestro,
  type DriverNodeMatchCompose,
  type DriverNodeMatchIosAxe,
  type DriverNodeMatchIosMaestro,
  type DriverNodeMatchWeb,
  type MatchDescriptor,
  type TrailblazeNodeSelector,
} from "./generated/selectors.js";
// Side-effect import: pure `TrailblazeToolMap` declaration merging (compiles to no runtime values),
// so in-process tool authors get the same framework-tool autocomplete the full entry provides.
import "./built-in-tools.js";

/**
 * Typed-only `trailblaze.tool()` for the in-process profile. Matches the full profile's typed
 * overload signatures so authoring `trailblaze.tool<MyInput>(handler)` / `(spec, handler)`
 * type-checks identically — but the imperative `tool(name, spec, handler)` form throws (it's
 * subprocess-only).
 *
 *   trailblaze.tool(async (_, ctx) => "done")                       // <{}, string>
 *   trailblaze.tool<MyInput>(async (i, ctx) => `hi ${i.name}`)      // <MyInput, string>
 *   trailblaze.tool<MyInput, MyOutput>(async (i, ctx) => ({ ... })) // fully typed
 *   trailblaze.tool<I, O>({ supportedPlatforms: ["web"] }, handler) // typed with-spec
 *
 * No ajv compiler is injected, so typed tools dispatch without runtime input validation.
 */
export function tool<TInput = Record<string, never>, TResult = string>(
  handler: (input: TInput, ctx: ToolContext) => Promise<TResult>,
): TypedToolDefinition<TInput, TResult>;
export function tool<TInput = Record<string, never>, TResult = string>(
  spec: TrailblazeTypedToolSpec,
  handler: (input: TInput, ctx: ToolContext) => Promise<TResult>,
): TypedToolDefinition<TInput, TResult>;
export function tool(
  arg0: unknown,
  arg1?: unknown,
): TypedToolDefinition<unknown, unknown> {
  // Typed bare-handler: tool<I, O>(handler).
  if (typeof arg0 === "function") {
    return defineTypedTool(
      arg0 as (input: unknown, ctx: ToolContext) => Promise<unknown>,
    );
  }
  // Typed with-spec: tool<I, O>(spec, handler). Plumb the optional inline JSON Schema through,
  // but with no compiler injected it stays advisory only (no runtime validation on this path).
  if (
    typeof arg0 === "object" &&
    arg0 !== null &&
    !Array.isArray(arg0) &&
    typeof arg1 === "function"
  ) {
    const specObj = arg0 as TrailblazeTypedToolSpec;
    const inlineSchema =
      specObj.inputSchema &&
      typeof specObj.inputSchema === "object" &&
      !Array.isArray(specObj.inputSchema)
        ? (specObj.inputSchema as Record<string, unknown>)
        : undefined;
    return defineTypedTool(
      arg1 as (input: unknown, ctx: ToolContext) => Promise<unknown>,
      inlineSchema,
    );
  }
  // Imperative `tool(name, spec, handler)` — unavailable in the slim runtime.
  throw new Error(
    "imperative trailblaze.tool(name, spec, handler) is unavailable in the in-process runtime; " +
      "author tools as `export const x = trailblaze.tool<I>(...)`, or declare `runtime: subprocess` " +
      "for tools that need the full MCP runtime.",
  );
}

/**
 * `trailblaze.run()` stub. The in-process runtime never starts an MCP stdio server — in-process
 * tools are registered by the synthesized bundle wrapper, not by `run()`. Throws so an author who
 * copied a subprocess toolset's `await trailblaze.run()` tail gets a clear pointer rather than a
 * silent no-op. Imports nothing heavy.
 */
export async function run(): Promise<void> {
  throw new Error(
    "trailblaze.run() is unavailable in the in-process runtime; in-process tools are registered " +
      "by the synthesized bundle wrapper. Declare `runtime: subprocess` if you need the MCP server.",
  );
}

/**
 * Namespace bundle authors import as `trailblaze` on the in-process path. Same shape as the full
 * profile (`{ tool, run }`) so author code is profile-agnostic; the slim members just throw for
 * the subprocess-only forms.
 */
export const trailblaze = {
  tool,
  run,
};

// `tool` and `run` are already exported via their `export function` / `export async function`
// declarations above — the flat named exports authors can `import { tool } from ...`.

// Re-export the slim-relevant authoring types so in-process tool authors get the same type
// surface (`ToolContext`, `EmptyInput`, `TypedToolDefinition`, `TrailblazeTypedToolSpec`).
export type {
  ToolContext,
  EmptyInput,
  TypedToolDefinition,
  TrailblazeTypedToolSpec,
  TrailheadSpec,
};
