---
title: "Scripted Tools PR A3 — MCP SDK Subprocess Toolsets"
type: decision
date: 2026-04-20
---

# Trailblaze Decision 038 PR A3: MCP SDK Subprocess Toolsets

> **Amended 2026-04-20 by the Option-2 amendment** in
> `2026-04-20-scripted-tools-toolset-consolidation.md`. This doc's direction is
> now **the single host-side scripted-tools path**. The toolset-consolidation
> amendment briefly inserted an in-process-QuickJS PR between PR A2 and this
> one; Option-2 then reversed that insertion and collapsed the former PR A4
> (this doc) into PR A3. Prefer `bun` as the runtime (with `node` + `tsx`
> fallback) per the Option-2 amendment — any compatible TS-capable runtime
> works. The conceptual shape below (author writes a standard MCP server,
> Trailblaze spawns it, speaks stdio MCP) is unchanged. Note: the example
> below uses the low-level `server.setRequestHandler(...)` style; the Option-2
> amendment's convention devlog example uses the higher-level
> `server.registerTool(...)` convenience API from the same MCP SDK. Both are
> equivalent — `registerTool` is a thin wrapper over `setRequestHandler` —
> and either style is acceptable authoring.

Authors contribute Trailblaze tools by writing a standard MCP server in TypeScript
using the official `@modelcontextprotocol/sdk`. Trailblaze spawns the server as a
Node subprocess on host runs, lists its tools, and registers them into the same
`TrailblazeToolDescriptor` registry as everything else. Host-only by construction.

This is the highest-leverage authoring surface for teams that need to call
external APIs, the local filesystem, or CLI tools — none of which can run inside
QuickJS.

## Scope

Authors write:

```typescript
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { ListToolsRequestSchema, CallToolRequestSchema } from "@modelcontextprotocol/sdk/types.js";

const server = new Server(
  { name: "my-tools", version: "1.0.0" },
  { capabilities: { tools: {} } },
);

server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: [
    { name: "fetchUserInfo", description: "…", inputSchema: { … } },
  ],
}));

server.setRequestHandler(CallToolRequestSchema, async (req) => {
  const { userId } = req.params.arguments;
  const res = await fetch(`https://api.example.com/users/${userId}`);
  const data = await res.json();
  return { content: [{ type: "text", text: JSON.stringify(data) }], isError: false };
});

await server.connect(new StdioServerTransport());
```

They run it normally with `node` — it's a real MCP server. Trailblaze spawns it
on host runs, lists its tools, registers them as `TrailblazeToolDescriptor`
entries indistinguishable from Kotlin-authored tools.

### In scope

- **Subprocess spawning + lifecycle.** Trailblaze starts the Node process on
  session startup (or lazily on first tool invocation), manages its stdio
  streams, handles clean shutdown and crash recovery.
- **Client-side stdio transport.** Kotlin-side MCP client that speaks the MCP
  wire protocol over the subprocess's stdin/stdout. Implements the handshake,
  `tools/list`, `tools/call`, error propagation.
- **Tool registration.** On session start, fire `tools/list`, iterate the
  response, register each into Trailblaze's tool registry. Tools carry their
  MCP `annotations` including the `trailblaze*` metadata namespace (see the
  conventions devlog).
- **Dispatch.** LLM selects tool → Trailblaze dispatcher routes `CallToolRequest`
  to the right subprocess → response marshals back to `TrailblazeToolResult`.
- **Configuration surface.** Users declare subprocess toolsets in the Trailblaze
  config (path to the server entry point, any args/env needed to start it).

### Explicitly out of scope

- **Subprocess calling back into Trailblaze as an MCP client.** An author's Node
  server could, in principle, open its own MCP client connection back to a
  running Trailblaze instance to invoke primitives. That's a bidirectional
  pattern we're deferring — the `trailblaze.execute()` surface ships via PR A2
  for in-process scripts, and subprocess tools can hit any HTTP/CLI they need
  via Node's native APIs. Revisit if a real cross-boundary composition use case
  surfaces.
- **On-device execution of subprocess toolsets.** Android can't spawn Node
  processes. On-device support for the same TS source is PR A5 (bundle via
  esbuild into a QuickJS-runnable artifact), not this PR.
- **Non-stdio transports.** SSE / HTTP / streamable HTTP are unnecessary for
  the in-tree subprocess case. Skip.

## Design decisions

### Why subprocess first, not QuickJS-bundled-SDK first

Two reasons:

1. **Zero bundler tooling.** Node runs the author's code natively. We don't need
   esbuild, polyfills, or a Gradle plugin for this PR. The author installs deps
   with `npm`, runs their `tsc`, and ships. Trailblaze consumes the output.
2. **Full capability out of the gate.** `fetch`, `fs`, `child_process`, npm
   packages — everything Node has, the author has. That's the high-leverage
   surface for most real external-I/O use cases (API validation from memory,
   local CLI invocation, etc.). Asking authors to shim these via Trailblaze
   bindings would be wrong.

PR A5 (on-device bundle) uses the **same TS source** once the bundler pipeline
lands. PR A3's MCP-shaped output is directly reusable there. No rework.

### Stdio, not HTTP

The subprocess runs on the same host as Trailblaze. No reason for an HTTP
listener — it's a local IPC problem, and stdio is the MCP-canonical solution
for that. Simple, no port collisions, no auth to manage.

### One registry, indistinguishable from Kotlin tools

Subprocess tools register as `TrailblazeToolDescriptor` entries. The LLM sees
them identically to Kotlin-authored tools. The agent loop, recording format,
and replay system don't need to know or care that a given tool's body is in a
subprocess — they just call it and get a result back.

This is the architectural economy payoff: the MCP-shaped descriptor becomes the
universal currency, and Kotlin / subprocess / (future) on-device-bundle all
plumb identically downstream.

### Process-lifetime model: per-session, not per-call

Spawn the subprocess once per session, keep it alive across tool invocations,
shut it down on session teardown. Per-call spawning would be prohibitively
slow (tsc → node startup per tool call). Per-process-lifetime is fine —
subprocess tools are trusted code the user deployed, not hostile inputs.

## Execution environment and trust model

**No sandboxing on host.** The subprocess is a plain bun/node process with
full runtime access. Authors can read any environment variable, shell out via
`child_process` / `Bun.spawn`, open network connections with `fetch`, read
and write files via `fs`, and import any npm package — everything the Node
ecosystem offers is available. That's intentional: subprocess MCP exists
precisely to give authors full language power. If an author needs something
QuickJS can't do (raw `fetch` to a backend, local CLI invocation, filesystem
I/O), that use case lives here.

### Environment inheritance at spawn

Trailblaze spawns the subprocess with the parent process's environment
inherited (default `ProcessBuilder` behavior on JVM / default `Bun.spawn`
behavior on bun), then layers a stable set of `TRAILBLAZE_*` variables on
top. Inheritance means `PATH`, `ANDROID_SDK_ROOT`, `HOME`, shell-exported
tokens like `GITHUB_TOKEN`, and anything else the user had in their shell
when launching Trailblaze is visible to author code. Authors can invoke
`adb`, `xcrun`, `curl`, or anything else on `PATH` the same way any CLI
tool would.

Scrubbing secrets before spawn is possible via explicit config (a future
knob) but not the default — the principle-of-least-surprise is "it works
like every other build tool."

### Stable `TRAILBLAZE_*` env-var contract

Authors can rely on these variables being present in `process.env` at every
subprocess spawn. This is a **public API** surface — bumps are breaking
changes.

| Variable | Value | Notes |
|---|---|---|
| `TRAILBLAZE_DEVICE_PLATFORM` | `IOS` \| `ANDROID` \| `WEB` | Target device platform for this session |
| `TRAILBLAZE_DEVICE_DRIVER` | The session driver's full `TrailblazeDriverType.yamlKey` (e.g. `android-ondevice-accessibility`, `ios-host`, `playwright-native`). Scope devlog has the full list + link to the enum. | Driver backing the platform |
| `TRAILBLAZE_DEVICE_WIDTH_PX` / `TRAILBLAZE_DEVICE_HEIGHT_PX` | integer pixel counts | Snapshot at session start; may change mid-session (rotation) — per-call `_trailblazeContext` is authoritative for live state |
| `TRAILBLAZE_SESSION_ID` | opaque session id string | For logging / correlation |
| `TRAILBLAZE_TOOLSET_FILE` | absolute path to the author's `.ts` | So the subprocess knows its own source (useful for diagnostics, hot-reload follow-ups) |

Earlier drafts of this direction contemplated riding the same snapshot in
the MCP `initialize` request's `_meta` field as a second channel, but
investigation of both public SDKs during the runtime landing showed that
neither end exposes that path ergonomically — the
[Kotlin SDK `Client.connect()`](https://github.com/modelcontextprotocol/kotlin-sdk/blob/0.11.1/kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/Client.kt#L188-L196)
doesn't plumb `meta` through, and the
[TypeScript SDK `Server._oninitialize`](https://github.com/modelcontextprotocol/typescript-sdk/blob/bdfd7f0/packages/server/src/server/server.ts#L426)
handler reads only `protocolVersion` / `capabilities` / `clientInfo` and
drops `_meta` before authors see it. The scope devlog
(§ MCP handshake flow) captures the full analysis with linked citations.
**The env-var channel is the authoritative one.** A future structured
handshake payload would more naturally ride in
`ClientCapabilities.extensions` (spec's vendor-namespaced extension
point, already ergonomic on the TS read side); additive follow-up.

### Capability contrast with on-device (PR A5)

Authors writing tools that should work across both deployment modes should
keep this contrast in mind:

| Capability | Host subprocess (PR A3) | On-device QuickJS (PR A5) |
|---|---|---|
| `process.env` | Full access | Not available |
| `fetch` / network | Full access | Not available (no network binding in QuickJS) |
| `child_process` / shell out | Full access | Not available |
| `fs` / filesystem | Full access | Not available |
| npm packages | Any | Only what bundles cleanly for a non-Node target |
| `trailblaze.execute(...)` callback | Via reciprocal MCP channel | Via in-process binding (see PR A2) |
| `_trailblazeContext` in args | Yes | Yes |

Tools using capabilities in the left column but not the right declare
`_meta: { "trailblaze/requiresHost": true }` — the on-device bundle filter
(PR A5) then excludes them at registration time. See the conventions devlog
for the full annotation namespace.

### Trust model

Author code runs with **the same privileges as the Trailblaze daemon that
spawned it** (user-level, not root). Same trust model as Gradle plugins,
yarn scripts, Make targets, or any other build/test tooling: you run the
author's code because you're the author (or your team is). This is *not* a
multi-tenant sandbox. If someone distributes a public Trailblaze toolset,
users evaluating it should review the source the same way they'd review a
build plugin or npm postinstall script.

## Open questions

- **Subprocess declaration format.** Where and how do users list their
  subprocess toolsets? Options: entry in `trailblaze.config.yaml`, per-project
  convention (`.trailblaze/tools/*`), CLI flag. Resolve when implementation
  lands.
- **Dependency installation UX.** Authors need `node_modules` installed to
  run. Does Trailblaze `npm install` for them, or expect it pre-installed?
  Probably latter for now — keep it simple, require `npm install` before
  first run.
- **Crash semantics.** If a subprocess tool crashes mid-session, do we relaunch
  transparently, surface the error to the LLM, or abort the session? Default:
  abort with a clear error, user can retry. Relaunch is a polish follow-up.

## References

- `docs/devlog/2026-04-20-scripted-tools-execution-model.md` — Decision 038,
  the umbrella roadmap.
- `docs/devlog/2026-04-20-scripted-tools-a2-sync-execute.md` — PR A2, ships the
  `trailblaze.execute()` in-process primitive. PR A3 doesn't depend on it
  (subprocess tools use Node-native APIs for their work), but authors who want
  composition across primitives from inside a subprocess can still go through
  Trailblaze via a bidirectional pattern later.
- `docs/devlog/2026-04-20-scripted-tools-on-device-bundle.md` — PR A5, reuses
  the same MCP SDK authoring source and bundles it for QuickJS on-device
  execution.
- `docs/devlog/2026-04-20-scripted-tools-mcp-conventions.md` — the MCP
  extension conventions all scripted-tool paths share (metadata via
  `annotations`, context via `_trailblazeContext`, errors via `isError` +
  later `_meta.trailblaze`).
