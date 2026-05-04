---
title: "Scripted Tools PR A3 — Host-Side Subprocess MCP Toolsets (Scope)"
type: decision
date: 2026-04-20
---

# Trailblaze Decision 038 PR A3: Host-Side Subprocess MCP Toolsets (Scope)

> This is the **scope devlog for the merged PR A3** (the former PR A3 and
> PR A4 collapsed per the [Option-2 Amendment](2026-04-20-scripted-tools-toolset-consolidation.md#option-2-amendment-2026-04-20)).
> It locks in the implementation contract an agent picks up and builds
> against. Companion docs:
>
> - [Scripted Tools PR A3 — MCP SDK Subprocess Toolsets](2026-04-20-scripted-tools-mcp-subprocess.md) — direction doc (the *what*)
> - [Scripted Tools PR A3 Phase 1 — Subprocess MCP Client, Lifecycle, and Registration](2026-04-20-scripted-tools-a3-subprocess-impl.md) — subprocess lifecycle and registration design (still valid; re-absorbed as the primary target for this PR per the Option-2 amendment)
> - [Scripted Tools — MCP Extension Conventions](2026-04-20-scripted-tools-mcp-conventions.md) — shared conventions (annotations, context envelope, naming, error shape). This devlog does not re-spec what's in the conventions devlog; it specifies how PR A3 consumes and enforces those conventions.

## Context

Authors contribute Trailblaze tools by writing a **standard MCP server**
in TypeScript using the official `@modelcontextprotocol/sdk`. Trailblaze
spawns the server as a bun/node subprocess per session, speaks `initialize`
→ `tools/list` → `tools/call` over stdio, and registers the returned tools
into the `TrailblazeToolDescriptor` registry alongside Kotlin and
YAML-defined tools.

The Option-2 amendment established the *shape*: author writes a real MCP
server, Trailblaze is an MCP client, bun is the preferred runtime, QuickJS
moves exclusively to PR A5 (on-device). This devlog fills in the
implementation details that let an agent build that without further
open questions.

## Scope

### In scope (this landing)

- **Config parsing.** New `mcp_servers:` field on `AppTargetYamlConfig`
  (the existing target YAML schema in `:trailblaze-models`). Each entry
  is an `McpServerConfig` data class. MVP ships the `script:` entry
  shape only — `command:` / `args:` / `env:` are parseable in the schema
  for forward compatibility but not implemented in the runtime (see the
  forward-looking devlog
  [MCP Integration Patterns](2026-04-21-scripted-tools-mcp-integration-patterns.md)).
- **Toolset membership via existing mechanisms.** No new toolset schema
  changes. Subprocess tools enter the global session registry under
  their advertised names; existing toolset YAMLs
  (`ToolSetYamlConfig` in `:trailblaze-models`,
  `xyz.block.trailblaze.config.ToolSetYamlConfig`)
  reference them by name via `tools: List<String>`. Authors can also
  push tools into specific toolsets from the `.ts` source via the
  `_meta["trailblaze/toolset"]` key (see the conventions devlog).
- **Runtime detection and subprocess spawn.** Prefer `bun`; fall back to
  `node` + `tsx` (or equivalent TS loader); clear error if neither is on
  `PATH`.
- **Stdio MCP client.** Speak `initialize`, `tools/list`, and `tools/call`
  as an MCP client.
- **Tool registration.** Read each tool's `_meta["trailblaze/*"]`; apply
  the filter rules from the conventions devlog; resolve final tool names
  per conventions § 4; register into the session registry.
- **`_trailblazeContext` envelope.** Build from
  `TrailblazeToolExecutionContext` and inject as a reserved key in every
  `tools/call` `arguments` object.
- **`TRAILBLAZE_*` env var contract.** Set the public-API env vars at
  subprocess spawn as documented in the direction doc.
- **Subprocess lifecycle.** Per-session spawn, long-lived; graceful
  shutdown on session teardown; stderr captured to session logs.
- **Tests.** Fixture toolset, tier-1 handler, tier-2 handler with context,
  collision error path, env inheritance sanity check, graceful shutdown.

### Explicitly out of scope (this landing)

- **Reciprocal `trailblaze.execute()` callback channel** (subprocess →
  Trailblaze primitives). Decision deferred — see § Reciprocal callback
  direction below.
- **On-device QuickJS bundle path** — the same `.ts` source compiled
  and bundled for QuickJS execution on-device. A separate landing; this
  PR preserves the annotations that path will read.
- **Hot-reload.** Authors restart the session to pick up `.ts` changes.
- **Automatic `npm install` / `bun install`.** Authors run their own
  install before first run; Trailblaze errors clearly if `node_modules`
  is missing.
- **Crash recovery / auto-relaunch.** If a subprocess crashes mid-session,
  abort with a clear error; polish follow-up.
- **Dependency bundling or offline artifacts.** MVP assumes network + a
  working TS authoring environment on the host.
- **Spec features supported by both SDKs that we're deliberately *not*
  adopting for MVP.** Reviewed against the
  [2025-11-25 MCP spec](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/schema/2025-11-25/schema.ts),
  cross-checked against Kotlin SDK
  [0.11.1](https://github.com/modelcontextprotocol/kotlin-sdk/releases/tag/0.11.1)
  and TypeScript SDK
  [@ `bdfd7f0`](https://github.com/modelcontextprotocol/typescript-sdk/commit/bdfd7f0):
  - **`ProgressNotification`** (client-side `onProgress` handler
    registered via `RequestOptions` on `callTool`; server-side
    `sendProgress`). Would surface long-running tool progress in-session
    — useful for fixture-setup / env-seed tools. Candidate for the
    lifecycle polish pass rather than MVP.
  - **`CancelledNotification`**. Would let Trailblaze cancel in-flight
    tool calls cooperatively on session abort, giving the handler a
    cleanup window before the subprocess is destroyed. Natural fit for
    when the lifecycle commit lands.
  - **`LoggingMessageNotification`** + `setLoggingLevel`. Structured
    log messages routed through MCP instead of stderr classification.
    Additive (`console.log` still goes to stderr, authors must opt in);
    not a replacement for the stderr capture path. Skip unless an
    author need surfaces.
  - **`ClientCapabilities.experimental`** (the old `JsonObject?` field,
    not the spec-new `extensions` — the latter is absent from Kotlin
    0.11.1, see
    [issue #406](https://github.com/modelcontextprotocol/kotlin-sdk/issues/406)).
    A round-trip-supported channel for vendor-namespaced handshake
    payloads today. Noted as the pragmatic home if a structured
    handshake payload ever becomes necessary; MVP's env-var channel
    covers the current need.
  - **Tasks**, **Elicitation**, **Sampling**, **Resources / Prompts /
    Completions**: not a fit for the automated-test vocabulary. Note
    that `Tool._meta`
    ([PR #339](https://github.com/modelcontextprotocol/kotlin-sdk/pull/339))
    IS used by this landing — that's where our `trailblaze/*` per-tool
    metadata rides. See conventions § 1.

  These are recorded so future contributors can see the shortlist was
  reviewed deliberately rather than overlooked.

## Config surface

MCP server declarations live at the **target root** — a new `mcp_servers:`
field on `AppTargetYamlConfig`
(`trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/config/AppTargetYamlConfig.kt`).
Each entry is an `McpServerConfig`. This is the right home because
sessions are scoped by (target, driver, agent-mode), and a target's
scripts contribute tools that the target's platforms / drivers can pull
into their toolsets.

```yaml
# trails/config/targets/myapp.yaml
id: myapp
display_name: Example App

mcp_servers:
  # MVP shape — .ts script. Dual-target: host subprocess now;
  # compiled + bundled for on-device QuickJS in a later landing.
  - script: ./tools/myapp/login.ts
  - script: ./tools/myapp/onboarding.ts

platforms:
  android:
    app_ids: [com.example.myapp.debug]
    tool_sets:
      - core_interaction
      - myapp_login          # populated by login.ts via annotations or by a toolset YAML
      - myapp_android
  ios:
    app_ids: [com.example.myapp]
    tool_sets: [core_interaction, myapp_login, myapp_ios]
```

Entry fields (MVP):

- `script:` — path to a `.ts` / `.js` file authored with
  `@trailblaze/scripting`. Required for MVP; `command:` is
  schema-reserved but not runtime-implemented.

**Path resolution (settled by the runtime landing):** target YAMLs live as
classpath resources (`trailblaze-config/targets/*.yaml`), not filesystem
files, so "relative to the target YAML's directory" has no filesystem
meaning. The runtime anchors relative `script:` paths against the **JVM's
current working directory** (`System.getProperty("user.dir")`) — i.e. where
the author ran `./trailblaze` from, which is the project root in the common
single-repo layout. Absolute `script:` values pass through unchanged.
Resolution lives in `McpSubprocessSpawner.resolveScriptPath` in
`:trailblaze-scripting-subprocess`. A future per-target `script_root:`
override can be added additively when a concrete need surfaces; MVP
sticks with the cwd anchor because it matches the existing invocation
shape without new config.

**No name overlays (prefix / rename).** Authors advertise tool names
directly in their `.ts` source; Trailblaze registers those names as-is.
See conventions § 4 — bare names are deterministic, and Trailblaze is
curatorial about tool sources.

### Spawn scope

Every subprocess spawn requires a **resolved session context (target,
driver, agent-mode)**. Trailblaze does **not** spawn `mcp_servers:`
entries outside that scope. Concretely:

- **Trail run:** CLI / agent resolves target + driver + agent-mode from
  config and flags, then spawns the target's `mcp_servers:` entries.
- **Tool discovery (e.g., a `toolbox` CLI lister, or the registry
  populating for LLM-tool introspection):** passes through the same
  resolution — caller specifies target + driver (+ agent-mode), Trailblaze
  spawns + reads `tools/list` + returns the filtered set. No tool calls
  are invoked; the subprocess is torn down after listing.

This keeps host resources idle when no session is active and avoids
ambiguous "which platform's filter should apply?" state in a discovery
flow.

## Toolset YAML integration (no schema change)

The existing toolset YAML schema is `ToolSetYamlConfig`:

```kotlin
data class ToolSetYamlConfig(
  val id: String,
  val description: String = "",
  val platforms: List<String>? = null,
  val drivers: List<String>? = null,
  @SerialName("always_enabled") val alwaysEnabled: Boolean = false,
  val tools: List<String> = emptyList(),
)
```

**This landing does not modify the schema.** The existing `tools: List<String>`
field takes tool names; once a subprocess tool is in the global session
registry under its advertised name, any toolset YAML can include that
name in its `tools:` list, identical to how it references Kotlin-backed
or YAML-defined tools. Existing toolset YAML loading
(`ToolSetYamlLoader.kt`, classpath scan of
`TrailblazeConfigPaths.TOOLSETS_DIR`) continues to work unchanged.

Authors have two ways to get a subprocess-contributed tool into a
toolset:

- **Push from the source** — tag the tool with
  `_meta: { "trailblaze/toolset": "myapp_login" }`. The tool joins that
  toolset at registration time.
- **Pull from a toolset YAML** — add the tool name to a toolset YAML's
  `tools: List<String>`.

Both coexist; authors pick whichever suits the code-organization they
want. Toolset YAML's `platforms:` / `drivers:` / `always_enabled:` fields
continue to gate toolset applicability regardless of the tool's source.

### `trailblaze/toolset` _meta key

Lives alongside the other `_meta["trailblaze/*"]` keys defined in the
conventions devlog (`trailblaze/requiresHost`,
`trailblaze/supportedPlatforms`, `trailblaze/supportedDrivers`,
`trailblaze/requiresContext`). Single-valued for MVP
(`"trailblaze/toolset": "foo"`); plural form deferred until a concrete
use case surfaces.

## Runtime detection

At subprocess spawn, in order:

1. **`bun`** if present on `PATH`. Runs `.ts` directly via `bun run
   <file.ts>`.
2. **`node`** if present on `PATH`, plus `tsx` (via `node --import tsx
   <file.ts>` or equivalent TS loader). If `node` is present but no TS
   loader is resolvable, error with a message suggesting
   `npm install -g tsx` or similar.
3. **Neither present** → clear error at session start: "No compatible
   TypeScript runtime found on PATH. Install bun (https://bun.sh) or
   Node with tsx."

Runtime detection is cached per daemon lifetime — one check, not
per-session.

## Subprocess lifecycle

- **Spawn:** one subprocess per Trailblaze session, at session start.
- **Stdio:**  the subprocess's stdin/stdout carry the MCP JSON-RPC
  traffic. stderr is surfaced via `StderrCapture` — every line flows
  through a rolling in-memory tail (default 64 lines) that the crash
  abort attaches to its `FatalError` message, and optionally into a
  per-session log file. Wiring the file path
  (`logs/<session_id>/subprocess_stderr.log`) is the session-startup
  integration layer's job (follow-up PR); this landing ships the
  `StderrCapture(logFile = …)` plumbing that that integration will call.
- **Environment inheritance:** parent process env inherited; `TRAILBLAZE_*`
  vars (§ Environment contract) layered on top.
- **Working directory:** the `.ts` file's parent directory. Authors can
  resolve sibling files via relative paths. This is a stable contract.
- **Graceful shutdown:** on session teardown, send the MCP `shutdown`
  notification (if the SDK supports it) followed by closing stdin; wait
  up to 5 seconds for the process to exit; then SIGTERM; then SIGKILL
  after another 2 seconds.
- **Crash handling:** if the subprocess exits unexpectedly mid-session,
  capture the last stderr lines and abort the session with a clear
  error. Auto-relaunch is explicitly out of scope for MVP.

## Kotlin MCP client

**Adopt the official `io.modelcontextprotocol:kotlin-sdk` SDK already on
the Trailblaze classpath** (`io.modelcontextprotocol:kotlin-sdk:0.9.0`,
pinned in `gradle/libs.versions.toml`). The same SDK already backs
`TrailblazeMcpServer`
(`trailblaze-server/src/main/java/xyz/block/trailblaze/logs/server/TrailblazeMcpServer.kt`)
on the server side, and the SDK's `client.Client` class is already
imported in `KoogMcpFactory.kt`, so the API is known-compatible with the
rest of the codebase. Using it keeps us symmetric with how Trailblaze
already speaks MCP.

**Transport:** `io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport`.
**Verified present in v0.9.0** by inspecting the cached
`kotlin-sdk-client-jvm-0.9.0.jar` (classes under
`io/modelcontextprotocol/kotlin/sdk/client/StdioClientTransport*`). The
transport ships with rich event handling — `EOFEvent`, `IOErrorEvent`,
`JsonRpc`, `StderrEvent`, and a configurable `StderrSeverity` classifier
(`IGNORE` / `DEBUG` / `INFO` / `WARNING` / `FATAL`) — which maps cleanly
onto the "capture stderr into session logs" design.

Usage pattern (from the SDK's own KDoc):

```kotlin
val process = ProcessBuilder(runtime, sourceFile).start()

val transport = StdioClientTransport(
    input = process.inputStream.asSource().buffered(),
    output = process.outputStream.asSink().buffered(),
    error = process.errorStream.asSource().buffered(),
) { stderrLine ->
    // Classify into session-log severity
    when {
        stderrLine.contains("error", ignoreCase = true) -> StderrSeverity.WARNING
        else -> StderrSeverity.DEBUG
    }
}
transport.start()
```

Streams are `kotlinx.io.Source` / `Sink` — standard interop via
`.asSource().buffered()` / `.asSink().buffered()` extensions on JVM
streams.

**Module placement:** new module `:trailblaze-scripting-subprocess`
(sibling to the existing `:trailblaze-scripting`, which remains the
QuickJS host for the eventual on-device bundle path). The new module depends on:

- `io.modelcontextprotocol:kotlin-sdk` (the MCP SDK; already in libs
  catalog).
- `:trailblaze-models` (for `TrailblazeConfig` parsing extensions and
  `TrailblazeToolDescriptor`).
- `:trailblaze-common` (for `TrailblazeToolExecutionContext` and session
  machinery).
- Kotlin stdlib + coroutines.

`:trailblaze-scripting` stays unchanged for this PR — it owns the
QuickJS bridge primitive and the legacy `ScriptTrailblazeTool`, and
neither is touched by the subprocess runtime.

**Why not Koog's `agents-mcp` wrapper:** it's scoped to tool-calling
agent consumption via `McpToolRegistryProvider`. The subprocess-client
use case here is lower-level (stdio transport, per-session subprocess
lifecycle) and doesn't benefit from Koog's registry abstraction.
Dropping to the raw SDK client is simpler.

## MCP handshake flow

Per-session sequence:

1. **Spawn** subprocess with `TRAILBLAZE_*` env vars + inherited env.
2. **`initialize`** request. Trailblaze sends `clientInfo: { name:
   "trailblaze", version: "..." }`. The direction doc originally
   envisioned an `_meta.trailblaze` payload as a second channel
   alongside the `TRAILBLAZE_*` env vars, letting authors read the
   device/session snapshot directly from the `initialize` params in
   their server's handler. Investigation of both public SDKs showed
   this isn't round-trip-supported today:
   - **[Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk)
     (client side, pinned at
     [`0.11.1`](https://github.com/modelcontextprotocol/kotlin-sdk/releases/tag/0.11.1)):**
     [`Client.connect()`](https://github.com/modelcontextprotocol/kotlin-sdk/blob/0.11.1/kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/Client.kt#L188-L196)
     hard-codes `InitializeRequestParams(protocolVersion, capabilities,
     clientInfo)` — no hook for `meta`, even though the data class
     [already declares
     `meta: RequestMeta? = null`](https://github.com/modelcontextprotocol/kotlin-sdk/blob/0.11.1/kotlin-sdk-core/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/types/initialize.kt).
     None of the 0.10.0 / 0.11.0 / 0.11.1 release notes address this,
     and nothing in the issue tracker specifically calls it out; the
     closest precedent is
     [PR #289](https://github.com/modelcontextprotocol/kotlin-sdk/pull/289)
     ("Add metadata support to callTool method") and
     [PR #339](https://github.com/modelcontextprotocol/kotlin-sdk/pull/339)
     (adds `_meta` to `Tool`). The broad spec-alignment umbrella
     [issue #406](https://github.com/modelcontextprotocol/kotlin-sdk/issues/406)
     tracks gaps but doesn't include `initialize._meta`.
   - **[TypeScript SDK](https://github.com/modelcontextprotocol/typescript-sdk)
     (server side, pinned at main commit
     [`bdfd7f0`](https://github.com/modelcontextprotocol/typescript-sdk/commit/bdfd7f0)):**
     the [schema layer
     accepts](https://github.com/modelcontextprotocol/typescript-sdk/blob/bdfd7f0/packages/core/src/types/schemas.ts)
     `_meta` on `InitializeRequestParams` (via `BaseRequestParamsSchema`
     +  `RequestMetaSchema.looseObject`), but the built-in
     [`Server._oninitialize`
     handler](https://github.com/modelcontextprotocol/typescript-sdk/blob/bdfd7f0/packages/server/src/server/server.ts#L426)
     reads only `protocolVersion`, `capabilities`, `clientInfo` and
     discards `_meta`. No public `McpServer` API exposes it to author
     handlers; `oninitialized` fires after initialize with no params.
     The only workaround is replacing the default handler via
     `setRequestHandler('initialize', ...)` on the underlying `Server`,
     which is fragile if the SDK's defaults evolve.

   Both ends would need additive changes for `_meta.trailblaze` on
   `initialize` to work ergonomically. **Runtime ships without that
   channel.** The `TRAILBLAZE_*` env-var channel (§ Environment
   contract) already carries the same snapshot and is cleanly readable
   from `process.env` inside any MCP handler. `ClientCapabilities.extensions`
   (the spec's vendor-namespaced capability field) is the more promising
   future home for a structured Trailblaze handshake payload — the TS
   SDK captures `capabilities` onto `this._clientCapabilities` during
   `_oninitialize` and authors read via
   `server.getClientCapabilities()?.extensions?.["trailblaze/v1"]`, so
   the read side is already ergonomic. Kotlin-side send support would
   need a parallel addition. Not scope of this landing; filed as
   follow-up.
3. **`tools/list`** request. Subprocess returns its tool catalog.
4. **Registration pass.** For each returned tool:
   - Read `_meta["trailblaze/*"]` keys.
   - Apply filters in order: skip if `trailblaze/supportedDrivers` or
     `trailblaze/supportedPlatforms` is present and doesn't include the
     session's active driver / platform; skip if
     `trailblaze/requiresHost: true` and the agent-mode is on-device. In
     host-agent mode, host tools register fine; in on-device-agent mode,
     host-only tools are skipped.
   - Register under the name the tool advertises (no transforms) per
     conventions § 4.
   - Check global registry uniqueness; error at registration on
     collision with a message naming both contributors.
   - Register into the session's `TrailblazeToolDescriptor` registry.
5. **`tools/call`** per LLM invocation. Trailblaze injects
   `_trailblazeContext` as a reserved key inside `arguments`, maps the
   resolved name back to the subprocess's original tool name (so the
   subprocess sees what it advertised), sends the call, awaits the
   response, maps back per the conventions devlog § 3 result shape.
6. **Session teardown.** Graceful shutdown per § Subprocess lifecycle.

## Environment contract

Per the direction doc (`2026-04-20-scripted-tools-mcp-subprocess.md §
Execution environment and trust model`), this landing sets the following env
vars at spawn. **Public API — breaking changes require a bump:**

| Variable | Value | Source |
|---|---|---|
| `TRAILBLAZE_DEVICE_PLATFORM` | `IOS` / `ANDROID` / `WEB` | Session's device platform |
| `TRAILBLAZE_DEVICE_DRIVER` | The session driver's full `TrailblazeDriverType.yamlKey` — e.g. `android-ondevice-accessibility`, `android-ondevice-instrumentation`, `ios-host`, `playwright-native`, `revyl-android`. Full list in `TrailblazeDriverType` (`:trailblaze-models`). | Session's driver |
| `TRAILBLAZE_DEVICE_WIDTH_PX` / `TRAILBLAZE_DEVICE_HEIGHT_PX` | Integer pixel counts | Snapshot at session start |
| `TRAILBLAZE_SESSION_ID` | Opaque session id | For log correlation |
| `TRAILBLAZE_TOOLSET_FILE` | Absolute path to the author's `.ts` | For diagnostics |

Parent process env (`PATH`, `HOME`, `ANDROID_SDK_ROOT`, ambient auth
tokens, etc.) is inherited by default. Scrubbing specific vars before
spawn is possible via future config knobs; not MVP.

## `_trailblazeContext` envelope

Per conventions devlog § 2, on every `tools/call`, Trailblaze injects a
reserved `_trailblazeContext` key into `arguments`:

```typescript
type TrailblazeContext = {
  memory: Record<string, unknown>;
  device: {
    platform: "IOS" | "ANDROID" | "WEB";
    widthPixels: number;
    heightPixels: number;
    driverType: string;
  };
};
```

Kotlin-side envelope builder: new internal utility in the new
`:trailblaze-scripting-subprocess` module that reads
`TrailblazeToolExecutionContext` (device info, session memory) and emits
the JSON shape above. Encapsulated so the on-device bundle path can
reuse the same builder (same envelope, different transport).

## Capability filtering

Applied at step 4 of the handshake (registration pass). Filters come
from the `_meta["trailblaze/*"]` namespace and are applied in this
order:

1. **`trailblaze/supportedDrivers`** — array of driver yamlKeys the tool
   supports (e.g., `[android-ondevice-accessibility, ios-host]`). If
   present and non-empty, and the session's active driver is not in the
   array, skip registration. Matches `TrailblazeDriverType.yamlKey`.
2. **`trailblaze/supportedPlatforms`** — coarser, platform-level variant:
   array of `IOS` / `ANDROID` / `WEB`. If present and non-empty, and the
   session's active platform (derived from the driver) is not in the
   array, skip registration. Use this when the tool works across all
   drivers of a platform; use `trailblaze/supportedDrivers` when it's
   driver-specific.
3. **`trailblaze/requiresHost`** — if `true`, tool works only in
   host-agent sessions. Skipped in on-device-agent sessions per
   `TrailblazeConfig.preferHostAgent=false`. Orthogonal to driver — the
   driver's `requiresHost` capability describes what the driver itself
   needs to function, not whether the agent runs on host vs on-device.
4. **`trailblaze/requiresContext`** — read and preserved as metadata;
   not applied as a filter. UX-only (hides Trailblaze-only tools in
   pure-MCP-client surfaces, LLM hints).

Dynamic filtering via the subprocess's own `tools/list` handler is
always available — Trailblaze honors whatever the subprocess returns.
Authors who want env-var / feature-flag / license gates put the logic
there rather than adding new `_meta` keys.

## Tool-name registration

Per conventions § 4, **advertised name = registered name**. Subprocess
tools register in the global registry under exactly the name returned in
the `tools/list` response. No transforms (no prefixing, no renaming, no
namespacing). This gives deterministic, recordable, replayable behavior
— a trail that records `- myapp_logInWithEmail:` maps to exactly one handler
today and next year.

Global-uniqueness check at registration across the combined registry
(Kotlin + YAML + JS/TS). Collision = registration error with a message
naming both contributing sources; the author resolves by renaming one of
the sources.

Trailblaze maintains an internal map from **tool name → (subprocess id,
tool name)** so `tools/call` can be dispatched back to the correct
subprocess. Since names are not transformed, the subprocess sees the
same name the registry does.

## Reciprocal `trailblaze.execute()` callback direction

**Deferred to post-MVP.** The subprocess-impl devlog
(`2026-04-20-scripted-tools-a3-subprocess-impl.md`) specified an HTTP
proto-JSON channel per Decision 029 (`2026-02-03-custom-tool-architecture.md`).
This landing ships *without* that channel.

Rationale: subprocess authors have full Node APIs (`fetch`, `fs`,
`child_process`, any npm package) to do their work. The reciprocal
callback's value is limited to calling back into **Trailblaze-specific
primitives** (`tapOn`, `screenshot`, `memorize`). That's useful but not
MVP-critical — the set of authors writing Trailblaze-aware tools that
also want to orchestrate other Trailblaze tools is a subset of a subset.

When that channel lands (a later follow-up), the implementation is
exactly what the subprocess-impl devlog already specifies — proto-JSON
over a short-lived HTTP server Trailblaze exposes to the subprocess,
discovered via the `TRAILBLAZE_CALLBACK_URL` env var. Nothing in this
landing's MVP forecloses that direction.

Until then, Tier 2 tools that need Trailblaze data use the
`_trailblazeContext` envelope (read-only snapshot of device + memory).
Tools that want to *mutate* Trailblaze state (write memory, invoke
primitives) wait for the callback channel.

## Test plan

New tests in the new `:trailblaze-scripting-subprocess` module:

- **Fixture toolset** (`.ts` file committed in test resources)
  exercising:
  - One Tier-1 handler (no `_trailblazeContext` destructure).
  - One Tier-2 handler (destructures `_trailblazeContext`; reads
    `device.platform` and `memory.x`).
  - One tool with `_meta: { "trailblaze/supportedDrivers":
    ["android-ondevice-accessibility"] }` — verify it's registered under
    that driver and skipped for sessions with other drivers.
  - One tool with `_meta: { "trailblaze/requiresHost": true }` — verify
    it's registered in host-agent sessions and skipped when
    `TrailblazeConfig.preferHostAgent=false` (on-device agent).
  - One tool with `_meta: { "trailblaze/toolset": "otherToolset" }` —
    verify it registers under `otherToolset`, not the file's default.
- **Registration tests:**
  - Subprocess `tools/list` returns three tools → registry has three
    entries under the names the subprocess advertised (no transforms).
  - Two fixture files with a name collision → registration fails with a
    clear error naming both contributing sources.
  - Fixture tool's advertised name reaches the registry unchanged —
    verify identity pre- and post-registration.
- **Dispatch tests:**
  - `tools/call` on a fixture tool routes to the right subprocess and
    returns the correct `CallToolResult`.
  - `_trailblazeContext` is present in the handler's `arguments`;
    device platform matches the session.
- **Lifecycle tests:**
  - Graceful shutdown on session teardown.
  - Subprocess crash mid-session → session aborts with stderr captured.
  - Missing `bun` and `node` on `PATH` → clear error at session start.
- **Env inheritance test:** set a sentinel env var before spawning a
  fixture; fixture's handler verifies `process.env.SENTINEL` matches;
  verify `TRAILBLAZE_DEVICE_PLATFORM` is present.

## Open questions

*None remaining at this time — all open questions resolved. Future
consideration (not MVP): if a concrete use case for mechanical namespace
shifting appears (e.g., adopting a specific 3rd-party MCP ecosystem
convention), conventions § 4 notes the door is open for an additive
overlay mechanism in a later PR.*

### Resolved

- ~~**MCP SDK v0.9.0 stdio-client-transport availability.**~~ Verified
  present. `StdioClientTransport` is in the client SDK jar with full
  stderr severity classification; see § Kotlin MCP client above.
- ~~**Config surface placement.**~~ Initially scoped to a top-level
  `trailblaze.yaml` field (`subprocess_toolsets:`). Corrected: the
  declaration lives at target root, on `AppTargetYamlConfig.mcpServers`.
  Per-target scoping matches Trailblaze's actual session model (target
  + driver + agent-mode) and honors the existing target/platform/driver
  hierarchy.
- ~~**`ToolYamlConfig` schema extension coordination** with the Decision
  037 team.~~ No longer needed — this landing doesn't modify `ToolYamlConfig`
  (per-tool, Decision 037) or `ToolSetYamlConfig` (toolset-level).
  Subprocess tools enter the global registry under their advertised
  names; existing toolset YAMLs reference them by name via the
  unchanged `tools: List<String>` field.
- ~~**`namePrefix:` / `rename:` precedence edge cases.**~~ Obviated —
  the feature itself is dropped from MVP (see § Config surface and
  conventions § 4). Advertised name = registered name; authors own
  naming. No precedence table needed because there's only one stage.

## References

- [Scripted Tools — Toolset Consolidation & Revised Sequencing](2026-04-20-scripted-tools-toolset-consolidation.md) — Option-2 Amendment (phase decisions).
- [Scripted Tools PR A3 — MCP SDK Subprocess Toolsets](2026-04-20-scripted-tools-mcp-subprocess.md) — direction doc (the *what*).
- [Scripted Tools PR A3 Phase 1 — Subprocess MCP Client, Lifecycle, and Registration](2026-04-20-scripted-tools-a3-subprocess-impl.md) — subprocess lifecycle + registration design (re-absorbed as PR A3's target per Option-2 amendment).
- [Scripted Tools — MCP Extension Conventions](2026-04-20-scripted-tools-mcp-conventions.md) — shared conventions (annotations, `_trailblazeContext` envelope, result shape, tool naming + global uniqueness).
- [Scripted Tools PR A2 — Synchronous Tool Execution from JS](2026-04-20-scripted-tools-a2-sync-execute.md) — the in-process `trailblaze.execute()` primitive (QuickJS). Unchanged; not consumed by PR A3 directly.
- [Decision 037: YAML-Defined Tools (the `tools:` mode)](2026-04-20-yaml-defined-tools.md) — per-tool `ToolYamlConfig` schema. PR A3 does not modify this schema; subprocess tools appear in the global registry under their advertised names so Decision 037's tool-listing mechanisms continue to work unchanged.
- [Decision 029: Custom Tool Architecture](2026-02-03-custom-tool-architecture.md) — the RPC architecture the deferred reciprocal callback channel will adopt.
- [Decision 014: Tool Naming Convention](2026-01-14-tool-naming-convention.md) — the authoritative tool-naming convention (categories, reserved prefixes, versioning). Subprocess MCP tools follow it directly; [conventions § 4](2026-04-20-scripted-tools-mcp-conventions.md#4-tool-naming-and-global-uniqueness) summarizes and extends to the subprocess source.
