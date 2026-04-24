---
title: "Scripting SDK — Envelope Migration & Callback Transport (D1 + D2)"
type: decision
date: 2026-04-22
---

# Scripting SDK — Envelope Migration & Callback Transport

Resolves the two open design decisions called out in the scripting-SDK roadmap
— D1 (envelope shape) and D2 (callback wire format) — for the initial landing.
Both decisions locked before coding so downstream work has a stable contract
to build against.

## D1. Envelope shape — `_trailblazeContext` arg vs `_meta.trailblaze`

**Decision: inject BOTH during the migration window.** Keep
`_trailblazeContext` as a reserved argument key (current behaviour) AND
add `_meta.trailblaze` with the richer shape. New SDK consumers read
from `_meta`; the existing raw-SDK `tools.ts` that reads
`_trailblazeContext` keeps working unchanged.

### Why not rip-and-replace

The host-subprocess landing already ships
`TrailblazeContextEnvelope` injecting `_trailblazeContext` into the
`tools/call` `arguments` object, and a follow-up shipped a real sample-app
example (`examples/android-sample-app/trailblaze-config/mcp/tools.ts`)
reading that envelope. The mission invariant is explicit:

> existing `tools.ts` reading `_trailblazeContext` today MUST still
> work. Either keep BOTH during migration, or stage across two PRs.

Dual-writing is the lower-risk path and falls out for free — one call to
`JsonObject + ("_meta" to ...)` alongside the existing arg merge.

### Why add `_meta.trailblaze` at all

The callback channel needs three pieces the current envelope doesn't
carry: `baseUrl` (so the subprocess knows *where* to hit Trailblaze back),
`sessionId` (for server-side log correlation), and `invocationId` (so a
callback from inside a tool handler resolves back to the right session
+ the right live agent). Stuffing those into `_trailblazeContext` under
`arguments` conflates tool-input data with transport/session metadata;
MCP's `_meta` field is the right home per spec convention.

### SDK round-trip validity

The prior investigation (scope devlog
`2026-04-20-scripted-tools-a3-host-subprocess.md § MCP handshake flow`)
was for `_meta` on `initialize`, not `tools/call`. Re-checked against
the pinned Kotlin MCP SDK 0.9.0 sources:

- `CallToolRequestParams`
  (`io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams`)
  declares `@SerialName("_meta") override val meta: RequestMeta? = null`
  — so the client side can send `_meta` on the request wire.
- The TS SDK's server handler layer exposes `request.params._meta` on
  the tool-call callback — authors read it directly, no setRequestHandler
  override needed.

Both ends round-trip `_meta` on `tools/call` today. No SDK patching
required.

### Shape (locked)

```typescript
type TrailblazeMeta = {
  // Transport
  baseUrl: string;           // e.g. "http://localhost:52525"
  sessionId: string;         // opaque; use for log correlation only
  invocationId: string;      // per-tool-call; used by the callback channel

  // Runtime snapshot (same fields as the legacy _trailblazeContext)
  device: {
    platform: "ios" | "android" | "web";
    widthPixels: number;
    heightPixels: number;
    driverType: string;
  };
  memory: Record<string, unknown>;
};
```

Lives under `_meta["trailblaze"]` on every `tools/call` request. Vendor
prefix `trailblaze/*` is reserved per the conventions devlog
(`2026-04-20-scripted-tools-mcp-conventions.md § 1`), and the single
`trailblaze` bucket keeps the key surface small.

The legacy `_trailblazeContext` arg is unchanged — still carries
`{memory, device}` only. Authors using the new SDK never see it; authors
reading the raw MCP SDK keep working.

### Migration horizon

The legacy arg key stays until a follow-up explicitly deprecates it.
No timeline here — that's a per-ecosystem call for a later PR. The
conventions devlog will be amended when deprecation is scheduled.

## D2. Callback wire format — JSON via `kotlinx.serialization`

**Decision: JSON with `@Serializable` Kotlin data classes and matching
TypeScript interface types. Not proto, not Wire, not MCP-as-transport.**

The original D2 recommendation was proto (Wire for Kotlin, `buf` for
external TS/Python bindings). For the initial landing that is over-engineered:

- The endpoint is a single request/response pair (`JsScriptingCallbackRequest` →
  `JsScriptingCallbackResponse`) with one action variant (`CallTool`). JSON is fine
  for that surface; proto's value — schema evolution, binary size,
  cross-language bindings — kicks in at N-actions, N-languages, not one.
- Proto here would add: a new Gradle module (`trailblaze-scripting-proto`),
  the Wire plugin's build wiring, a `buf.yaml` + `buf.gen.yaml`, a
  committed `generated/` directory in the TS SDK, and the ongoing
  discipline of regenerating on every edit. That's real cost for zero
  payoff today.
- MCP-as-callback-transport (using the same MCP channel back to
  Trailblaze) was considered and rejected — MCP's request/response model
  plus server-originated tool calls would require the Kotlin side to
  host an MCP *client* inside the subprocess and an MCP *server* on the
  Trailblaze host for just one method. HTTP + JSON is dramatically
  simpler.

### Shape (locked for v1)

```kotlin
@Serializable
data class JsScriptingCallbackRequest(
  @SerialName("version") val version: Int = 1,
  // Required in v1 — the endpoint cross-checks this against the live invocation's session
  // (invariant: never silently dispatch against a different session).
  @SerialName("session_id") val sessionId: String,
  @SerialName("invocation_id") val invocationId: String,
  @SerialName("action") val action: JsScriptingCallbackAction,
)

@Serializable
sealed interface JsScriptingCallbackAction {
  @Serializable
  @SerialName("call_tool")
  data class CallTool(
    @SerialName("tool_name") val toolName: String,
    @SerialName("arguments_json") val argumentsJson: String,
  ) : JsScriptingCallbackAction
}

@Serializable
data class JsScriptingCallbackResponse(
  @SerialName("result") val result: JsScriptingCallbackResult,
)

@Serializable
sealed interface JsScriptingCallbackResult {
  @Serializable
  @SerialName("call_tool_result")
  data class CallToolResult(
    val success: Boolean,
    val textContent: String = "",
    val errorMessage: String = "",
  ) : JsScriptingCallbackResult

  @Serializable
  @SerialName("error")
  data class Error(val message: String) : JsScriptingCallbackResult
}
```

Serialized via `kotlinx.serialization.json.Json` with a class
discriminator. TS side mirrors the shape as hand-written interfaces in
the SDK — no codegen. `arguments_json` is a JSON string (not a nested
JSON object) so tool schemas remain Kotlin-authoritative and the
callback doesn't need to know about each tool's argument shape.

### Versioning

`version: 1` is on every request. Breaking changes bump to `v2`; the
endpoint dispatches by version. No in-place edits to v1 post-ship.
Same invariant originally specified for proto-v1; just applied at the
JSON layer.

**v1 → v2 migration strategy.** The TS SDK's `JsScriptingCallbackRequest.version`
is a TS literal-type constant (`version: 1`), so a subprocess bundled
with this SDK sends `version: 1` forever. When we need to introduce a
v2 (new action variant that can't be expressed additively, a change to
`_meta.trailblaze` envelope semantics, etc.) the path is:

1. **Additive v2 where possible.** New `JsScriptingCallbackAction` variants ride
   under the existing v1 dispatcher — the endpoint's `when (action)`
   already handles unknown variants via the Kotlin sealed-class
   exhaustiveness fallback. Prefer this when the breaking change is
   just "new thing, old SDKs won't call it."
2. **Breaking v2.** When the v2 wire shape genuinely breaks v1
   compatibility (renamed fields, changed semantics), the daemon
   accepts BOTH `version: 1` AND `version: 2` requests for a deprecation
   window, dispatching by version. Ship the TS SDK bump (`version: 2`
   literal, updated types) and require users to update their
   `@trailblaze/scripting` dependency to pick up the new shape. The
   daemon's v1 acceptance stays until the deprecation window closes;
   at that point the version check rejects v1 and older subprocesses
   produce a clear "upgrade @trailblaze/scripting" error.
3. **CI drift guard.** If the Kotlin `JsScriptingCallbackRequest.CURRENT_VERSION`
   diverges from the TS literal without a coordinated SDK bump, a
   future CI check should fail — tracked as follow-up work; not
   load-bearing for the callback-channel landing.

The subprocess SDK bundles its own version — callers upgrade by
bumping their `@trailblaze/scripting` version, not by wire-level
negotiation. Server-side capability negotiation is deliberately
out of scope for the JSON channel; proto/Wire migration (§ "Migration
to proto later") is where the story gets more structured.

### Migration to proto later

If later follow-ups grow the action surface (tap, inputText, assertVisible,
memorize, screenshot, …) and we add Python/on-device-QuickJS
consumers, proto becomes the right call. Migration cost at that point:
define the proto, wire Wire + buf, flip the endpoint to accept
`application/x-protobuf` alongside `application/json`. The JSON types
here stay as a transitional bridge or get deleted outright — either way
the v1-of-the-endpoint is a small, contained blast radius.

## Scope this locks in

- Envelope dual-write (`_trailblazeContext` arg + `_meta.trailblaze`).
- `baseUrl` / `sessionId` / `invocationId` live under `_meta.trailblaze`.
- `/scripting/callback` endpoint accepts JSON, not proto.
- `@trailblaze/scripting` ships with hand-written TS types that match
  the Kotlin `@Serializable` shapes. No proto codegen.
- No new Gradle module for a proto contract; the data classes live in
  `:trailblaze-host` alongside the endpoint (or `:trailblaze-common` if
  cross-module access shows up — decide at implementation time, default
  host-local).

Explicitly deferred:

- Actual `client.callTool()` round-trip from inside a tool handler — follow-up.
- Additional action variants beyond `CallTool` — follow-up.
- Proto contract + `buf` codegen + Python bindings — reassess once the
  action surface grows.

## Callback-channel design concerns flagged for the callback-channel landing

Surfaced from Decision 038's execution-model devlog
(`2026-04-20-scripted-tools-execution-model.md`) when it lands the
PR A2 synchronous-execute surface. These apply here too the moment
callbacks actually light up:

- **Reentrance.** A subprocess callback can dispatch a Trailblaze tool
  that resolves to *another* subprocess tool on the same MCP session.
  MCP's JSON-RPC layer multiplexes concurrent requests via ids, but
  nothing caps the callback depth. The callback landing should bound it
  (Decision 038 uses ~16 for scripts; pick a number for subprocess too).
- **Timeout discipline.** A subprocess handler could wait on its own
  callback; if the Trailblaze-side dispatch hangs, the MCP `tools/call`
  hangs, the session hangs. The endpoint should apply a bounded
  dispatch timeout, and/or rely on the outer agent-level timeout.
- **Thread safety of `TrailblazeToolExecutionContext`.** The callback
  endpoint dispatches against the SAME context the outer handler is
  holding. Memory writes propagate; cached screen state is shared.
  The callback landing decides whether callbacks get a fresh `screenState`
  capture or read the cached one. Pick deliberately — either is defensible,
  but silent drift will confuse authors.
- **MCP SDK version.** The project pins Kotlin MCP SDK **0.9.0** (not
  0.11.1 as originally considered). 0.9.0's `RequestMeta` is a
  `@JvmInline value class RequestMeta(val json: JsonObject)` — the
  full object rides as-is, so vendor keys (`trailblaze`) sit alongside
  SDK-reserved ones (`progressToken`) without conflict. No upgrade
  pressure; flagged in case a future bump changes the shape.

## References

- Host-subprocess scope devlog:
  [2026-04-20-scripted-tools-a3-host-subprocess.md](2026-04-20-scripted-tools-a3-host-subprocess.md)
- Integration-patterns forward-looking devlog:
  [2026-04-21-scripted-tools-mcp-integration-patterns.md](2026-04-21-scripted-tools-mcp-integration-patterns.md)
- Conventions devlog (annotations, envelope, result shape, naming):
  [2026-04-20-scripted-tools-mcp-conventions.md](2026-04-20-scripted-tools-mcp-conventions.md)
- Decision 029 (original proto/HTTP callback architecture):
  [2026-02-03-custom-tool-architecture.md](2026-02-03-custom-tool-architecture.md)
