# trailblaze-quickjs-tools

Evaluates `@trailblaze/scripting`-authored JS bundles in QuickJS and registers the
advertised tools into Trailblaze's `TrailblazeToolRepo`. No MCP framing — the
runtime reads `globalThis.__trailblazeTools` directly and dispatches via
`QuickJsToolHost.callTool(name, args, ctx)`.

This is the MCP-free QuickJS runtime that `AndroidTrailblazeRule` launches on-device today.

> **Single author surface (2026-06).** Trailblaze has one scripted-tool authoring SDK,
> `@trailblaze/scripting`, with two bundle *profiles* selected per tool by its `runtime:`:
> the SLIM **in-process** profile (`sdks/typescript/src/in-process.ts` — typed-only, no MCP,
> no ajv, no zod) that this runtime evaluates, and the FULL **subprocess** profile (the MCP
> server) for `runtime: subprocess` tools. The retired `@trailblaze/tools` package — a separate
> MCP-free SDK that pre-dated the slim profile — has been deleted; its job (KB-scale in-process
> bundles) is now done by aliasing `@trailblaze/scripting` to the slim entry. The bundlers
> synthesize a small registration wrapper that populates `globalThis.__trailblazeTools` from each
> file's typed-tool exports (typed declarations don't self-register). `QuickJsToolHost` is the
> engine wrapper that backs this in-process runtime.

## Reading order

A new contributor onboarding to this module should read in this order:

1. **`QuickJsToolHost.kt`** — QuickJS engine wrapper. `connect`, `listTools`,
   `callTool`, `shutdown`. One engine = one bundle = one thread. Includes the
   `HostBinding` interface for `trailblaze.call(name, args)` round-trips.
2. **`QuickJsToolBundleLauncher.kt`** — launches bundles into hosts, filters
   tools by `_meta.trailblaze/…`, and registers each into a
   `TrailblazeToolRepo`. Returns a `LaunchedQuickJsToolRuntime` that owns
   shutdown.
3. **`QuickJsTrailblazeTool.kt`** — the executable form a registered tool
   takes. `HostLocalExecutableTrailblazeTool`, so the agent dispatches it
   directly via `execute(context)` without driver-specific routing.
4. **`QuickJsToolEnvelopes.kt`** — typed `@Serializable` data classes for the
   scripted-tool `TrailblazeToolResult` shape and the per-invocation `ctx`.
5. **`BundleSource.kt`** + **`AndroidAssetBundleSource.kt`** (androidMain) —
   load-from-where abstraction. File, inline string, or APK asset.

## Dispatch flow

```
LLM picks a tool from the repo
  → TrailblazeToolRepo.toolCallToTrailblazeTool(name, argsJson)
  → QuickJsToolRegistration.decodeToolCall → QuickJsTrailblazeTool
  → execute(context) → host.callTool(name, args, ctx)
  → bundle handler runs in QuickJS, returns { content: [...], isError? }
  → toTrailblazeToolResult → TrailblazeToolResult.Success | Error
```

## Differences from `:trailblaze-scripting-bundle`

- No MCP `tools/list` / `tools/call` — the runtime reads
  `globalThis.__trailblazeTools` directly.
- Bundles are built against the SLIM in-process `@trailblaze/scripting` profile
  (no MCP server, no ajv, no zod) rather than the full subprocess profile.
- The `HostBinding` interface is `(name, argsJson) → resultJson` — no MCP
  envelope, no protocol framing.

## Bundle types

Three flavors live under `examples/android-sample-app/trails/config/`:

- **`quickjs-tools/pure.js`** — pure JS, no SDK, no build step. Populates
  `globalThis.__trailblazeTools` directly.
- **`quickjs-tools/typed.ts`** — TS authored against `@trailblaze/scripting`,
  bundled by the `trailblaze.author-tool-bundle` plugin into a single
  self-contained `.bundle.js`.
- **`host-tools/tools.ts`** — uses `node:*` modules. Host-only (`runtime: subprocess`);
  cannot run in QuickJS, integrates via the subprocess path in
  `:trailblaze-scripting-subprocess`.
