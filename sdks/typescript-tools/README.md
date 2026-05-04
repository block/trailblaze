# `@trailblaze/tools`

Tiny authoring SDK for Trailblaze tools that run in the on-device QuickJS runtime.

```ts
import { trailblaze } from "@trailblaze/tools";

trailblaze.tool(
  "greet",
  { description: "Say hi", inputSchema: { name: { type: "string" } } },
  async (args, ctx) => ({
    content: [{ type: "text", text: `hello ${args.name}` }],
  }),
);
```

That's the entire surface. No MCP server, no transport, no `await trailblaze.run()`. The Kotlin runtime evaluates the bundle, reads `globalThis.__trailblazeTools` to discover what got registered, and dispatches calls directly. Bundle output is ~3 KB self-contained.

## When to use this vs. `@trailblaze/scripting`

- **`@trailblaze/tools`** (this package): tools that should run on-device. Pure JS + the SDK's surface. esbuild-bundled, evaluated by QuickJS in a Kotlin process.
- **`@trailblaze/scripting`**: tools that need full Node capabilities — `node:fs`, `child_process`, `fetch`, native deps. Run as a bun subprocess via the host MCP path.

The two SDKs intentionally share the author surface (`trailblaze.tool(...)`). What differs is the runtime that consumes the bundle, which is decided by what your handler reaches for. Pure JS and the SDK alone → on-device-compatible. `import * as fs from "node:fs"` → host-only.

## Background

Architectural rationale: see [`docs/devlog/2026-04-30-scripted-tools-not-mcp.md`](../../docs/devlog/2026-04-30-scripted-tools-not-mcp.md).
