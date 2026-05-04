---
title: "A TrailblazeTool is a function call (MCP tool, RPC request — same thing)"
type: devlog
date: 2026-04-22
---

# A TrailblazeTool is a function call

## Summary

A `TrailblazeTool` is a **named function with typed parameters that returns a result**. That's it. "MCP tool," "RPC request," and "function call" are three vocabularies for the same underlying thing — pick whichever lens you're thinking in. MCP is one transport; the LLM is one caller. Every other caller (TS script via `client.callTool`, Kotlin code invoking directly, a future Python or other SDK) is another client dispatching the same named function with the same arguments. This has always been true; naming it gives us canonical vocabulary that's been missing from design discussions.

## The reframe

You can describe a `TrailblazeTool` in any of these vocabularies without changing the thing:

| Lens | Name | Args | Result |
|---|---|---|---|
| Function call | `tapOnElementWithText` | `{ text: "Continue" }` | return value / thrown exception |
| RPC request | `tapOnElementWithText` | `{ text: "Continue" }` | response envelope |
| MCP tool | `tapOnElementWithText` | `inputSchema` + args object | `{ content, isError }` |

All three are "name + parameters → result." The differences are surface: how errors are signalled, how results are framed, who chooses which to call. The core is the same.

The "LLM tool-calling" framing is a **use case** of the mechanism, not the mechanism itself:

```
LLM (via MCP)        ─┐
TS script (callTool) ─┼──▶ dispatcher ──▶ TrailblazeTool.execute(args) ──▶ result
Kotlin code          ─┤
future SDKs          ─┘
```

- **The tool** is a named + schema'd request whose handler returns a result. Nothing about it presupposes who's calling.
- **The caller** chooses how to dispatch. An LLM picks tools from a catalog using descriptions; a script picks them by name against a known contract. Same wire, different selection semantics.
- **MCP is the transport** we already ship. Not the only one we could ship.

## Why it matters

- **Scripting SDK isn't a bolt-on.** It's the natural second client on a system that was always RPC-shaped. The `client.callTool(name, args)` surface is the load-bearing piece — without it, scripting is a dead-end second-class citizen instead of a peer RPC client.
- **"Be like MCP" becomes a statement about the mechanism, not the frontend.** MCP's value is that *tool = RPC call = something any caller can dispatch*. We align with MCP because the shape is right, not because the LLM use case is special.
- **"Kotlin class or scripted tool?" falls out cleanly.** The first question is always "what's the RPC primitive?" — the caller choice (LLM, script, direct) is downstream. This cuts through debates like "do we ship a thin Kotlin wrapper for app-specific installed-app resolution?" Write the primitive (`mobile_listInstalledApps`); compose from whichever caller needs it.
- **Public-surface completeness is now falsifiable.** Kotlin wrappers that bypass the RPC path create invisible shortcuts external contributors can't use. If a tool can't be built against the public RPC surface, the public surface is incomplete. Dogfooding the TS SDK surfaces the gap instead of papering over it.
- **Typed clients are optional, not required.** `callTool(name, args)` is the universal floor — the same property HTTP/JSON gives the web, where `curl` works for anyone and typed SDKs are opt-in sugar. A Python/Go/Ruby SDK can start with one `callTool(name, args)` function and grow typed wrappers (`client.mobile.listInstalledApps()`) only for the primitives its users actually hit friction on. No language has to ship a huge codegen'd client before it can participate; no one is gated by another language's typed-surface completeness.

## Wire shape tension

MCP tool results are message-style (`{content: [{text}], isError}`) — designed for an LLM to read. RPC-style primitives want typed returns (`listInstalledApps() → string[]`). The cast from typed-value-to-text-for-the-LLM is lossy at the boundary.

Resolution: **keep MCP as the transport; hide the cast at the SDK layer.** Kotlin tool returns JSON-in-text; typed TS client wrapper does `JSON.parse` once and hands the caller a typed object. Same pattern as gRPC / OpenAPI codegen on top of a string wire protocol. Authors see the typed surface; the ugly wire format is just transport. If this grows to dozens of RPC-style primitives, revisit — MCP's `structuredContent` direction or a first-class RPC result category become options. Not now.

## Transport choice: why MCP, and what about OpenAPI?

MCP and OpenAPI are both transports for the same underlying thing — "function with a name and parameters → result." The devlog's core framing makes this choice about fit and ecosystem, not about what tools fundamentally are.

| | MCP | OpenAPI |
|---|---|---|
| Primary consumer | LLMs | Developers / HTTP clients |
| Discovery model | `list_tools` at session start (runtime) | Static spec at dev time |
| Catalog shape | Name + description-for-LLM + input schema | Endpoints + request/response schemas |
| Result typing | Free-form `content: [{text}]` | Typed response schemas |
| Statefulness | Session-oriented | Stateless by default |
| Codegen ecosystem | Young (~18mo) | Mature (~10 years, 40+ languages) |
| LLM tool-calling | Native | Bolted on (function-calling adapters) |

**Why MCP is right as the primary for Trailblaze:**

- **The LLM is a first-class caller.** Trailblaze is LLM-driven UI testing. MCP was designed for "LLM discovers tools, picks one, calls it, reads result, picks another." OpenAPI retrofits that loop via function-calling adapters that effectively re-invent MCP's catalog-and-describe model, less well.
- **Runtime tool catalogs.** Tool sets vary per target app and per session config. MCP's `list_tools` at connect is native to this. OpenAPI would need per-target specs or heavy conditional-schema gymnastics.
- **Ecosystem convergence.** Claude Code, ChatGPT, Cursor, VS Code, Zed — they all speak MCP. Shipping MCP = immediately consumable by every major LLM-tooling client. OpenAPI = writing and maintaining an adapter for each.
- **Session context.** The `_meta.trailblaze` envelope carrying device info, memory, session ID fits MCP's session model. OpenAPI is stateless by default — you'd pass this on every request or invent a session layer on top.

**Where OpenAPI would win:**

- Typed results natively (the wire-shape tension above disappears).
- Polyglot baseline — anything with an HTTP library can call, without needing a JSON-RPC client.
- Mature tooling ecosystem (mock servers, docs generators, codegen for ~40 languages).

**How to hold both in mind:**

The core abstraction stays transport-agnostic: `TrailblazeTool = name + args → result`. MCP is the primary serialization, chosen because the LLM is the primary caller and that's where the tooling ecosystem is consolidating. If demand materializes for non-LLM HTTP consumers (monitoring dashboards, CI orchestration, external automation), adding an OpenAPI transport on the same dispatcher is mechanical — same tool definitions, different wire format. We haven't painted ourselves into a corner, and that's a direct consequence of this devlog's framing.

## What this unlocks next

- `mobile_listInstalledApps` — first RPC-style primitive, delegating to `AndroidDeviceCommandExecutor.listInstalledApps()` (the same abstraction `MobileDeviceUtils.getInstalledAppIds` uses under the hood — the tool lives in `trailblaze-common` so it works from both host JVM and on-device Android). Lands once the TS `client.callTool` roundtrip is available. Exposed as typed `client.mobile.listInstalledApps()` on the TS SDK.
- App-specific debug-broadcast tools (custom `sendBroadcast`-backed tools that currently live as one-off Kotlin wrappers per consuming app) — motivating migration target. Rewritten as TS tools that compose `mobile_listInstalledApps` + `android_sendBroadcast`. No Kotlin wrapper.

## References

- `sdks/typescript/src/client.ts` — `TrailblazeClient` interface
- `trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/scripting/callback/JsScriptingCallbackContract.kt` — wire contract
- `docs/devlog/2026-04-22-scripting-sdk-envelope-migration.md` — envelope design
- `docs/devlog/2026-04-22-scripting-sdk-client-calltool.md` — `client.callTool` design
- `trailblaze-host/src/main/java/xyz/block/trailblaze/host/ios/MobileDeviceUtils.kt` — `getInstalledAppIds`
