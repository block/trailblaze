---
title: "@trailblaze/scripting — Authoring Vision & Roadmap (for TS authors)"
type: decision
date: 2026-04-22
---

# `@trailblaze/scripting` — Authoring Vision & Roadmap

Companion devlog aimed at **TypeScript authors** who'll be writing custom
Trailblaze tools. Where the companion devlogs focus on decisions
(envelope shape, proto-vs-JSON, runtime mechanics), this one captures
the **author experience** — what you write today, what you'll write
tomorrow, and where we're headed. Review target: give TS-literate
reviewers enough to push back on the API shape before we commit harder.

## The one-paragraph pitch

Trailblaze is an AI-driven UI-testing framework where the LLM calls
"tools" to drive the app (tap, type, assert, remember-this). Authors
have always been able to add their own tools. Today that means writing
Kotlin. We're adding a TypeScript authoring surface so anyone who can
spin up an MCP server can ship custom tools — no Kotlin, no Gradle, no
JVM round-trip. `@trailblaze/scripting` is a thin wrapper around
`@modelcontextprotocol/sdk` that hides the protocol ceremony, exposes
Trailblaze's device/session context as a typed object, and (landing in
the next PR) lets your tool call back into Trailblaze's own primitives
to compose higher-level behaviour.

## What authors write today (raw MCP SDK)

Committed reference: `examples/android-sample-app/trailblaze-config/mcp/tools.ts`

```typescript
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

const server = new McpServer(
  { name: "sample-app-mcp-tools", version: "0.1.0" },
  { capabilities: { tools: {} } },
);

server.registerTool(
  "generateTestUser",
  { description: "...", inputSchema: {} },
  async () => ({
    content: [{ type: "text", text: JSON.stringify({ name: "Sam", email: "sam@example.com" }) }],
    isError: false,
  }),
);

// ...repeat for every tool...

const transport = new StdioServerTransport();
await server.connect(transport);
```

Four MCP imports, three pieces of server-construction ceremony, and
you're on the hook for stdio transport wiring per file. More importantly:
**the handler has no access to Trailblaze state.** Device platform,
screen dimensions, agent memory, the session id — none of it is
reachable from inside the tool. If you need them, you can dig them out
of `process.env` (where we set a handful of `TRAILBLAZE_*` vars at
spawn), but it's off-contract and inconvenient.

## What authors will write with `@trailblaze/scripting`

Committed reference: `examples/android-sample-app/trailblaze-config/mcp-sdk/tools.ts`

```typescript
import { trailblaze } from "@trailblaze/scripting";

trailblaze.tool(
  "generateTestUser",
  { description: "..." },
  async (_args, ctx) => {
    // ctx is TrailblazeContext | undefined
    //   ctx.device.platform  "ios" | "android" | "web"
    //   ctx.device.driverType, .widthPixels, .heightPixels
    //   ctx.memory           agent memory as Record<string, unknown>
    //   ctx.sessionId        opaque session id for log correlation
    //   ctx.invocationId     per-call id — forward on callbacks
    //   ctx.baseUrl          daemon URL for the callback channel
    return {
      content: [{ type: "text", text: JSON.stringify({ name: "Sam", email: "sam@example.com" }) }],
    };
  },
);

await trailblaze.run();
```

One import. One call per tool. `ctx` is typed. `await trailblaze.run()`
replaces the server + transport boilerplate.

The two reference files sit side-by-side in the sample app and expose
the **same two tools under suffixed names** (`generateTestUser` /
`generateTestUserSdk`) so CI exercises both authoring surfaces in
parallel and we notice the moment either one drifts.

## The `TrailblazeContext` envelope

Injected by the host on every `tools/call` under
`_meta.trailblaze`. Shape is locked in the
[envelope-migration devlog](2026-04-22-scripting-sdk-envelope-migration.md):

```typescript
type TrailblazeContext = {
  baseUrl: string;                // daemon HTTP base URL
  sessionId: string;              // opaque; log correlation only
  invocationId: string;           // per-tool-call; forward on callbacks
  device: {
    platform: "ios" | "android" | "web";
    widthPixels: number;
    heightPixels: number;
    driverType: string;           // e.g. "android-ondevice-accessibility"
  };
  memory: Record<string, unknown>; // string-valued today; typed `unknown` for forward-compat
};
```

`undefined` when the tool was invoked outside a Trailblaze session
(ad-hoc MCP client, unit test) — your handler decides whether to
degrade gracefully or refuse.

There's also a **legacy arg envelope** (`_trailblazeContext` inside
`arguments`) that pre-dates the SDK and still works; the raw-SDK example
reads it. New tools should only read via `ctx`. Both envelopes are
injected in parallel during the migration window.

## What the next landing unlocks

This landing ships the authoring surface — tools declare themselves,
register, and execute. They have typed access to context but can't
*call back* into Trailblaze. A follow-up lights up that second half.

The callback architecture inherits directly from two prior design
threads:

- **[Decision 029 — Custom Tool Architecture](2026-02-03-custom-tool-architecture.md)**
  specified the RPC path: proto-typed commands over HTTP, with authors
  reaching a Trailblaze daemon endpoint discovered via `baseUrl` /
  `trailblazeInvocationId` on the envelope. Our callback endpoint
  (`/scripting/callback`) is that endpoint, JSON-first instead of proto
  (see D2 in the
  [envelope-migration devlog](2026-04-22-scripting-sdk-envelope-migration.md#d2-callback-wire-format-json-via-kotlinxserialization)).
- **[Synchronous Tool Execution from JS](2026-04-20-scripted-tools-a2-sync-execute.md)**
  specified the *semantics* — synchronous `trailblaze.execute(toolName, params)`
  returning a `TrailblazeToolResult`, reentrance, recording behaviour,
  error variants as JS objects. That work was originally aimed at QuickJS
  (in-process); we're taking the same shape and putting it on HTTP for
  the subprocess path. Same author-facing contract, different
  transport.

The next PR's surface:

```typescript
import { trailblaze } from "@trailblaze/scripting";

trailblaze.tool(
  "signUpNewUser",
  { description: "Creates a fresh account and signs in." },
  async (_args, ctx, client) => {
    // client.callTool(name, args) hits the daemon's /scripting/callback endpoint,
    // which deserializes via toolRepo.toolCallToTrailblazeTool(name, argsJson) and
    // executes against the live session's agent.
    const user = await client.callTool("generateTestUser", {});
    await client.callTool("tapOnElementWithText", { text: "Name field" });
    await client.callTool("inputText", { text: user.name });
    await client.callTool("tapOnElementWithText", { text: "Sign up" });
    return { content: [{ type: "text", text: `Signed up as ${user.email}` }] };
  },
);

await trailblaze.run();
```

The Kotlin side is already in place: `/scripting/callback` validates the
`invocationId`, resolves the live `TrailblazeToolRepo` + execution
context, dispatches, returns the result as JSON. The callback landing is
a pure TS change — no daemon changes, no proto.

### What the callback unlocks in practice

Once a subprocess can call back into Trailblaze, it can do anything a
Kotlin-authored tool can do — because it's dispatching the same tools
through the same repo. The patterns this enables are described
end-to-end in the
[execution-model devlog](2026-04-20-scripted-tools-execution-model.md),
which was originally written for the in-process QuickJS path but
applies identically here:

- **Query view hierarchy / visibility.** `await client.callTool("assertVisibleWithText", { text: "Login" })`
  and branch on `success: true/false`. Same information
  `trailblaze.isVisible(...)` would have surfaced in the QuickJS
  vision — no need for a separate query API because every Trailblaze
  tool's result is already the answer.
- **Read agent memory** (already live via `ctx.memory`). Write via
  callback into `rememberText` or any Trailblaze tool that writes
  memory.
- **Try-then-fallback composition.** Call a primitive, inspect
  `success`, branch. The original
  [Decision 025 vision](2026-02-20-scripted-tools-vision.md) was
  emit-only and couldn't do this; callbacks restore the observability
  that vision was missing.
- **Polling / stabilization loops.** The
  [execution-model devlog](2026-04-20-scripted-tools-execution-model.md#who-actually-needs-this)
  uses the API-29 "wait for main screen + stay stable for N checks"
  pattern as the motivating example — 80 lines of Kotlin today, ~15
  lines of TS with callbacks.
- **Custom assertions.** Compose whatever branching logic you want on
  top of the primitive tools, still recorded for deterministic replay.

The sync-execute devlog's
[design concerns](2026-04-20-scripted-tools-a2-sync-execute.md)
(reentrance caps, timeout discipline, recording semantics, thread
safety) apply here too — flagged in the
[envelope-migration devlog](2026-04-22-scripting-sdk-envelope-migration.md#callback-channel-design-concerns-flagged-for-the-callback-channel-landing)
as things the callback landing has to pin down.

### Design question we'd love feedback on

**Should the TS surface ever get typed command wrappers?**

A future option is to generate `client.tap({ text: "..." })`,
`client.inputText(...)`, etc. from the Kotlin tool catalog so authors
get IDE completion instead of "call `callTool('tapOnElementWithText',
...)` and hope the name hasn't changed." We're leaning **no**: name +
JSON args is the lowest-common-denominator surface, it evolves without
forcing SDK re-publishes every time a tool changes, and it works
identically for Python / on-device QuickJS consumers later.

The original
[Decision 038 execution-model devlog](2026-04-20-scripted-tools-execution-model.md#pr-b-typed-query-ergonomics-optional-polish)
flagged PR B (typed query ergonomics) as "optional polish" for exactly
this reason — everything expressible via
`client.callTool("assertVisibleWithText", { text }).success === true`
is already there; typed wrappers are about readability, not capability.
The
[earlier `commands.proto` prototype](2026-02-03-custom-tool-architecture.md)
tried typed wrappers and what we'd be reviving is essentially that
idea's codegen pipeline.

If your team has a strong opinion here — ergonomics win vs. catalog
drift risk — this is the load-bearing place to push back before that
option lands.

## Where we're heading

| Milestone | What |
|----|------|
| **This landing** | Authoring surface + callback endpoint. Tools declare, register, execute. No composition yet. |
| **Callback landing** | `client.callTool(name, args)` on the TS side. Tools compose other Trailblaze tools. |
| **Typed-commands decision** | Decision: typed commands or stay at `callTool(name, args)`. (Current lean: stay untyped.) |
| Later | Python SDK with the same envelope shape. Same HTTP callback endpoint. |
| Later still | On-device QuickJS bundle of the same `.ts` source — same authoring code, different transport. |

The authoring surface and the envelope contract are deliberately
**runtime-agnostic**: the same `tools.ts` should compile for both the
host-subprocess mode (today) and the on-device QuickJS mode (later)
without code changes. That constraint is why callbacks go over HTTP +
JSON instead of MCP-as-transport — an MCP-over-stdio callback would be
a different protocol than the HTTP-to-daemon callback QuickJS-on-device
will need to issue.

## Authoring workflow today

1. **Install** — `cd <your-target>/trailblaze-config/mcp-sdk && bun install` (or `npm install`). The SDK is consumed today via a local `file:` link in the sample example; npm-registry publish is a follow-up.
2. **Author** — `tools.ts` in that directory, using `@trailblaze/scripting`.
3. **Wire** — add an entry under `mcp_servers:` in the target YAML:
   ```yaml
   mcp_servers:
     - script: ./mcp-sdk/tools.ts
   ```
4. **Run** — Trailblaze spawns the file as a bun/node subprocess at
   session start, registers every tool it advertises, and includes them
   in the LLM's tool catalog for that session.

No Kotlin, no Gradle, no JVM in your author loop. If bun isn't on your
PATH, Trailblaze falls back to `node + tsx`.

## What we want from web-team review

Specific questions:

1. **`trailblaze.tool()` API shape.** Is `(name, spec, handler)` the
   right shape, or do you prefer `trailblaze.tool({ name, description,
   handler })` as an options object? We picked positional to match
   `server.registerTool`.
2. **`ctx` as second handler arg, or options-bag?** Second arg was
   chosen for destructure-friendliness (`async (_args, { device, memory })`).
3. **Handler return type.** Today we mirror the MCP SDK's shape
   (`{ content: [{ type: "text", text }], isError? }`). Worth wrapping?
   Concern: every wrapper is another thing to maintain on upgrade.
4. **Typed commands.** Worth the codegen cost or stay at
   `callTool(name, args)`?
5. **Error handling.** Thrown errors → `isError: true` MCP result, or
   let the subprocess crash? Today the SDK lets the MCP SDK's default
   behavior stand.
6. **Memory writes.** Today memory is read-only from `ctx.memory`.
   Authors who want to remember something call back into a Trailblaze
   tool that writes memory. Ergonomically right, or should `ctx.memory`
   expose `set`?
7. **Publishing.** Private `file:` link today. What's the right
   publishing path — GitHub Package Registry? npm public with a
   `@trailblaze/` scope? A private registry?
8. **TypeScript tooling.** Sample tsconfig uses `Node16` module
   resolution + strict mode. Any standards we should align with up-front?

## References

### MCP-based tools (what lands how)
- **Direction doc:** [Scripted Tools PR A3 — MCP SDK Subprocess Toolsets](2026-04-20-scripted-tools-mcp-subprocess.md) — the *what* (Option-2 amendment: subprocess MCP, not QuickJS-first)
- **Scope for PR A3 (what shipped before this SDK landing):** [Host-Side Subprocess MCP Toolsets (Scope)](2026-04-20-scripted-tools-a3-host-subprocess.md) — `mcp_servers:` YAML, spawn, handshake, tool registration, env-var contract
- **Subprocess lifecycle + registration details:** [Scripted Tools PR A3 Phase 1](2026-04-20-scripted-tools-a3-subprocess-impl.md)
- **MCP conventions (`_meta["trailblaze/*"]` keys, result shape, naming):** [Scripted Tools — MCP Extension Conventions](2026-04-20-scripted-tools-mcp-conventions.md)
- **Forward-looking integration patterns (Tier 1 first-party vs Tier 2 third-party servers, toolset-level `mcp_servers:`, metadata overlays):** [MCP Server Integration Patterns](2026-04-21-scripted-tools-mcp-integration-patterns.md)
- **Toolset consolidation (how host-subprocess + on-device bundle split):** [Scripted Tools — Toolset Consolidation & Revised Sequencing](2026-04-20-scripted-tools-toolset-consolidation.md)

### SDK callback / JSON-RPC / proto (the "call back into Trailblaze" thread)
- **Foundational RPC architecture (HTTP proto-JSON, `baseUrl` + `trailblazeInvocationId`, the pattern this SDK inherits):** [Decision 029: Custom Tool Architecture](2026-02-03-custom-tool-architecture.md)
- **Envelope + callback contract (this landing's decisions — D1 dual-write envelope, D2 JSON-not-proto):** [Scripting SDK — Envelope Migration & Callback Transport](2026-04-22-scripting-sdk-envelope-migration.md)
- **Synchronous execute semantics (originally for QuickJS; same contract we're delivering via HTTP):** [Scripted Tools PR A2 — Synchronous Tool Execution from JS](2026-04-20-scripted-tools-a2-sync-execute.md)

### Query view hierarchy / memory / execute-tools — "observe and react" patterns
- **Execution-model master plan (scripted tools, `trailblaze.execute()`, typed queries PR B, reentrance + timeout design):** [Scripted Tools Execution Model (QuickJS + Synchronous Host Bridge)](2026-04-20-scripted-tools-execution-model.md)
- **Original scripted-tools vision (Decision 025, where memory + emit started):** [Scripted Tools Vision](2026-02-20-scripted-tools-vision.md)
- **On-device QuickJS bundle (how the same `.ts` source will run on-device later):** [Scripted Tools PR A5 — MCP Toolsets Bundled for On-Device](2026-04-20-scripted-tools-on-device-bundle.md)

### Complementary authoring paths
- **YAML-defined tools (static composition; scripts complement, don't replace):** [Decision 037: YAML-Defined Tools](2026-04-20-yaml-defined-tools.md)

## Appendix: sample-app side-by-side

Both files implement `generateTestUser` and `currentEpochMillis`. The SDK
file suffixes them (`...Sdk`) so both paths register side-by-side in the
session without colliding. Read them together to see the diff the SDK
makes:

- Raw MCP SDK: `examples/android-sample-app/trailblaze-config/mcp/tools.ts`
- SDK: `examples/android-sample-app/trailblaze-config/mcp-sdk/tools.ts`

CI exercises both end-to-end via `SampleAppMcpToolsTest` and
`SampleAppMcpSdkToolsTest` so drift between the two authoring surfaces
surfaces immediately.
