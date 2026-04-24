---
title: "Scripted Tools PR A3 Phase 1 — Subprocess MCP Client, Lifecycle, and Registration"
type: decision
date: 2026-04-20
---

# Trailblaze Decision 038 PR A3 Phase 1: Subprocess MCP Client, Lifecycle, and Registration

> **Re-absorbed 2026-04-20 by the Option-2 amendment**
> (`2026-04-20-scripted-tools-toolset-consolidation.md` § Option-2 Amendment).
> The toolset-consolidation amendment briefly renumbered this devlog's scope
> to PR A4; the Option-2 amendment then merged that PR A4 back into the
> host-side PR A3. The design captured in this document is now **the primary
> implementation target for the merged PR A3**. Readers should pick up from
> the consolidation devlog's Option-2 Amendment section for the current
> phase picture, then return here for subprocess-MCP lifecycle and
> registration details. Any remaining references below to **PR A4** use the
> old numbering; for the on-device bundle path, read those references as
> **PR A5**.

Follow-up to the direction set in `2026-04-20-scripted-tools-mcp-subprocess.md`. This
devlog captures the implementation-level decisions that get locked in with the
minimal end-to-end path from "user declares a Node MCP subprocess toolset" to
"LLM picks a tool and it runs."

PR A3's direction doc documented *what* we're building; this doc documents *how* Phase 1
actually ships it.

## Scope (Phase 1)

The load-bearing user-visible capability: **a Node process that speaks MCP over stdio
can contribute tools to a Trailblaze session.** Everything else is follow-up work.

A subprocess toolset has two potential communication directions. Phase 1 ships only
the first:

| Direction | What it does | Phase |
|---|---|---|
| **Outer** (Trailblaze → Node) | LLM picks a tool; Trailblaze dispatches `CallToolRequest` to the subprocess. | **1A (this PR)** |
| **Callback** (Node → Trailblaze) | Subprocess's tool handler calls Trailblaze primitives (tap, assertVisible, memory reads, etc.). | **1B (next PR)** — proto-JSON over HTTP to the daemon, per Decision 029. |

Phase 1A alone is already useful: tools that make HTTP calls, spawn CLI commands,
or otherwise don't need to observe Trailblaze's device state (think "fetch user
from our staging API and pre-populate test fixtures") ship immediately. The
callback direction adds "tools that drive the device or observe its state" — most
useful for test-fixture helpers, but genuine additional work.

### In scope

- **Minimal stdio JSON-RPC 2.0 client in Kotlin.** Newline-delimited JSON frames;
  request/response correlation by `id`; single pending-request map per subprocess. No
  SSE, no HTTP, no streamable transports.
- **Subprocess lifecycle manager.** Spawn via `ProcessBuilder`, manage stdin/stdout
  streams, clean shutdown on session end, crash detection. **One process per declared
  toolset per session**; subprocess stays alive across tool invocations and is torn
  down when the session ends.
- **MCP handshake + tools/list on startup.** On session start, for each declared
  subprocess: send `initialize`, await capability response, send `tools/list`, iterate
  the result, register each tool as a `SubprocessTrailblazeTool` into the session's
  tool registry. Slow subprocess = slow session startup, deliberately.
- **Tool adapter.** `SubprocessTrailblazeTool(toolDescriptor, subprocessHandle)`
  implements `ExecutableTrailblazeTool`. Its `execute(context)` sends a
  `CallToolRequest` with the user's arguments plus `_trailblazeContext` (per the
  conventions devlog), awaits the response, and maps `CallToolResult` to
  `TrailblazeToolResult`:
  - `isError: false` → `Success(message = content[0].text)`
  - `isError: true` → `Error.ExceptionThrown(errorMessage = content[0].text)`
  - Transport or protocol error → `Error.ExceptionThrown`.
- **Configuration surface.** New `trailblazeSubprocessToolsets:` section in
  `trailblaze.config.yaml` listing entries with `{ name, command, args, cwd?, env? }`.
  Declared subprocesses spawn automatically at session start.
- **Integration test with a Node fixture.** A tiny `fixtures/mcp-test-server.mjs`
  that declares one test tool, echoes its params in the response. Tests assert the
  full loop spawn → handshake → list → dispatch → response → teardown.

### Explicitly out of scope (Phase 2+ follow-ups)

- **Crash recovery / auto-relaunch.** If a subprocess exits unexpectedly mid-session,
  Phase 1 aborts the session with a clear error. Relaunch is a polish follow-up.
- **`trailblaze/requiresHost` capability-based filtering.** That's a cross-cutting
  concern that also ties into PR A5 (on-device bundle). Track separately.
- **Callback direction (Node → Trailblaze primitives).** Phase 1A subprocesses cannot
  call back into Trailblaze to invoke primitives; their tool handlers work with Node's
  native capabilities only (HTTP, fs, CLI, npm packages). Phase 1B ships this via
  **proto-JSON over HTTP** to the Trailblaze daemon — specifically, the RPC server
  architecture Decision 029 (`2026-02-03-custom-tool-architecture.md`) already
  defined. Architecture sketch:
  - Trailblaze sets `TRAILBLAZE_PORT` and `TRAILBLAZE_SESSION_TOKEN` env vars on the
    spawned subprocess (`TRAILBLAZE_PORT` comes from `TrailblazePortManager`; the
    token is a per-session secret).
  - Subprocess's `@trailblaze/scripting/primitives` TS client reads those env vars,
    constructs the base URL (`http://localhost:${TRAILBLAZE_PORT}`), and attaches
    the token as a bearer on every RPC.
  - Kotlin side registers a new HTTP route (e.g. `POST
    /scripting/primitives/ExecuteTool`) handled by the same proto service shape
    Decision 029 describes. Token → session lookup gives the handler the right
    `TrailblazeToolExecutionContext`.
  - Phase 1B starts minimal: one RPC, `ExecuteTool(tool_name, params_json) →
    { is_error, message }`. Typed per-primitive messages (`IsVisibleRequest`,
    `CaptureScreenRequest`, etc.) land as they become needed, alongside the same
    service.
- **Non-stdio transports for the outer direction.** No HTTP/SSE/streamable for
  Trailblaze → subprocess dispatch. Stdio is MCP's canonical IPC for local
  subprocesses. The callback direction (Node → Trailblaze) deliberately uses HTTP
  for different reasons — see above.
- **`npm install` automation.** Phase 1 requires the user to install dependencies
  before running. Trailblaze doesn't invoke `npm` automatically — keeps us out of the
  Node-tooling management business.

## Design decisions

### Client protocol: newline-delimited JSON, not Content-Length framing

MCP's spec historically supports both line-delimited JSON over stdio (classic) and
Content-Length framing (LSP-style). Empirically, every Node MCP SDK subprocess we
care about emits line-delimited JSON. Phase 1 implements only that. Content-Length
framing can be added to the client under the same transport interface later if a
real need surfaces.

### Concurrency: one pending map per subprocess, synchronous send/await

Each subprocess gets a request-id counter and a `ConcurrentHashMap<Long,
CompletableDeferred<JsonRpcResponse>>` for pending requests. `send()` assigns an id,
registers the deferred, writes the JSON to stdin, and returns the deferred. A reader
coroutine consumes stdout line-by-line, parses each response, looks up the pending
deferred, and completes it.

Tool dispatches block on the deferred via `runBlocking` (same discipline as PR A2's
`trailblaze.execute()`). Multiple tools dispatched in parallel would currently
serialize — acceptable for Phase 1 since the LLM dispatches tools sequentially.
Parallelism is a potential follow-up if real workloads benefit.

### Lifecycle: per-session, not per-call

Per-call spawn would add ~200-500ms of Node startup latency per tool invocation.
Per-session keeps the subprocess alive for the whole trail. Shutdown is triggered
by session-end hooks (or `AutoCloseable` on the session registry). Stderr is
captured and surfaced in the Trailblaze log for debuggability; stdout is only
consumed by the transport reader.

### Startup timing: blocking, not lazy

Subprocess startup + `initialize` + `tools/list` happens during session
initialization, before any LLM invocation. Rationale: the LLM's tool list must be
final before the first prompt, otherwise mid-session tool-list changes create
confusing retry semantics. Cost: a slow subprocess (2-3s `npm install` followed by
warm-up) slows session startup. Acceptable; alternative (lazy startup) is more
complex for no user-visible benefit.

### Crash semantics: fail-fast

If a subprocess exits non-zero during the session, Phase 1 aborts the session with
`TrailblazeToolResult.Error.FatalError` and a message that includes the last 4KB of
stderr. No automatic relaunch. Users can retry the whole session; relaunch-mid-
session is genuine complexity (in-flight request correlation, state resumption) that
doesn't belong in Phase 1.

### `_trailblazeContext` injection from day one

Per the conventions devlog, the reserved `_trailblazeContext` key carries memory and
device info into the tool's argument envelope. Phase 1 implements this immediately
even though most authors' tools won't use it — the alternative (retrofitting it
later) would silently change the payload shape for every in-tree subprocess toolset,
and there's no reason to defer. `inputSchema` never advertises `_trailblazeContext`
to the LLM.

### Two transports, one per direction

Phase 1A uses **MCP over stdio** for Trailblaze → Node; Phase 1B uses **proto-JSON
over HTTP** to the Trailblaze daemon for Node → Trailblaze. Not multiplexed on one
stream — two distinct transports, each the natural fit for its direction:

- **Outer is MCP** because Node authors already know `@modelcontextprotocol/sdk`.
  The tools they expose are *their* vocabulary, dynamic and author-owned. MCP's
  JSON-Schema-described tool-call shape fits open vocabularies well.
- **Callback is proto-typed RPC** because Trailblaze's primitive surface is a
  *fixed, Trailblaze-owned vocabulary*. Same rationale as Decision 029: a typed
  service contract insulates external clients from tool-registry churn, generates
  clients in any language, and gives authors IDE-level safety when calling
  primitives. The wire format is JSON over HTTP for debuggability, but the schema
  source of truth is the proto file.

The asymmetry is intentional. Every language that can make HTTP calls can call
Trailblaze primitives; stdio multiplexing would have narrowed that audience to
subprocesses Trailblaze itself spawns, which is strictly less useful.

### Config shape

```yaml
trailblazeSubprocessToolsets:
  - name: my-api-tools
    command: node
    args: ["./tools/my-mcp-server.js"]
    cwd: ./tools
    env:
      API_BASE_URL: https://api.example.com
```

`name` is a human-readable identifier for logs and error messages (NOT the prefix
on tool names — those come from the subprocess's `tools/list` response).
`cwd` defaults to the Trailblaze config file's directory. `env` merges onto the
inherited process env.

## Open questions (Phase 1 lands with these unresolved)

- **Reuse of existing MCP types.** Trailblaze already has MCP *server* code. The
  request/response shapes (`InitializeRequest`, `ListToolsRequest`, etc.) may be
  directly reusable on the client side, OR we may end up with a separate set in the
  client module to avoid a cross-module dependency. Decide during implementation when
  the actual module graph is visible.
- **Module home.** Options: extend `:trailblaze-scripting` (natural since these are
  scripted tools); new `:trailblaze-mcp-client` module (cleaner boundary); or inline
  into `:trailblaze-host` (already holds process-spawning helpers). First two are
  most likely; pick during implementation based on dependency fit.
- **Tool-name collisions.** If two subprocesses each advertise a tool named `fetch`,
  or if a subprocess advertises a name that collides with a Kotlin-registered tool,
  the current plan is "error at registration time, user resolves by renaming." Phase
  1 will confirm that error surfaces early and is actionable.

## References

- `docs/devlog/2026-04-20-scripted-tools-execution-model.md` — Decision 038
  umbrella.
- `docs/devlog/2026-04-20-scripted-tools-mcp-subprocess.md` — the direction doc for
  PR A3. This devlog captures Phase 1's implementation commitments against that
  direction.
- `docs/devlog/2026-04-20-scripted-tools-mcp-conventions.md` — the
  `_trailblazeContext` convention Phase 1A implements for the outer direction
  (per-call context inside `CallToolRequest.arguments`).
- `docs/devlog/2026-02-03-custom-tool-architecture.md` — **Decision 029**. This is
  the canonical doc for the proto-defined RPC server at `localhost:52525`, the
  stateless `_meta` envelope carrying `{sessionId, deviceId, baseUrl, platform,
  ...}`, and the `TrailblazeCommands` Wire-proto contract external clients use.
  Phase 1B implements the subprocess-client side of that architecture. An earlier
  internal draft PR explored the same architecture; its content is preserved in
  this Decision 029 devlog.
- `docs/devlog/2026-04-20-scripted-tools-a2-sync-execute.md` — PR A2, the
  `trailblaze.execute()` in-process primitive. Same functional shape as Phase 1B's
  HTTP RPC (`ExecuteTool(name, params) → {isError, message}`), just in-process
  rather than cross-process. Authors' TS clients should expose an identical facade
  across both transports — the deployment mode decides which underneath.
