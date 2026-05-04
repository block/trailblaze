# trailblaze-quickjs-tools

Evaluates `@trailblaze/tools`-authored JS bundles in QuickJS and registers the
advertised tools into Trailblaze's `TrailblazeToolRepo`. No MCP framing — the
runtime reads `globalThis.__trailblazeTools` directly and dispatches via
`QuickJsToolHost.callTool(name, args, ctx)`.

This is the MCP-free counterpart to `:trailblaze-scripting-bundle` (the legacy
MCP-shaped path). Both are wired into `AndroidTrailblazeRule` today; the legacy
path will be retired in a follow-up once consumers have migrated.

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
   SDK's `TrailblazeToolResult` shape and the per-invocation `ctx`.
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
- One SDK (`@trailblaze/tools`) instead of `@trailblaze/scripting`.
- The `HostBinding` interface is `(name, argsJson) → resultJson` — no MCP
  envelope, no protocol framing.

## Bundle types

Three flavors live under `examples/android-sample-app/trails/config/`:

- **`quickjs-tools/pure.js`** — pure JS, no SDK, no build step. Populates
  `globalThis.__trailblazeTools` directly.
- **`quickjs-tools/typed.ts`** — TS authored against `@trailblaze/tools`,
  bundled by the `trailblaze.author-tool-bundle` plugin into a single
  self-contained `.bundle.js`.
- **`host-tools/tools.ts`** — uses `node:*` modules. Host-only; cannot run in
  QuickJS, integrates via the subprocess path in `:trailblaze-scripting-subprocess`.
