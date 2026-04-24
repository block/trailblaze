---
title: "On-device MCP tool callbacks — direct QuickJS binding"
type: decision
date: 2026-04-23
---

# Trailblaze Decision 047: on-device tool-callback channel (Option B)

Consumer: app-specific broadcast-tool migration.

## Problem

A TypeScript tool bundled on-device can invoke its own handler (that
wiring has landed). It **cannot** compose other Trailblaze tools from inside
that handler — `ctx.client.callTool("mobile_listInstalledApps", {…})`
has nowhere to dispatch to. The host path sends the request to the
daemon's `/scripting/callback` endpoint (see the 2026-04-22 envelope-
migration devlog). On-device there is no daemon and no HTTP server.

## Options considered

**A — embed a minimal HTTP server in the instrumentation process.**
Stand up Ktor (or a hand-rolled `HttpServer`) on-device, mount
`/scripting/callback`, populate `_meta.trailblaze.baseUrl` with
`http://localhost:<port>`. TS SDK unchanged.

- Pro: zero TS branching; single wire shape; reuses the endpoint code.
- Con: HTTP server inside instrumentation. Port binding is iffy on
  locked-down device farms. Lifecycle ceremony (bind on rule start,
  unbind on teardown). Serializing a JSON body through the OS loopback
  interface to dispatch a call that already lives in the same JVM is
  silly.

**B — direct QuickJS binding, no HTTP.**
`QuickJsBridge` grows a `__trailblazeCallback` async function binding
next to the existing `__trailblazeDeliverToKotlin`. The TS SDK's
`client.callTool` detects the in-process runtime via
`ctx.runtime === "ondevice"` and calls the binding; Kotlin dispatches
through the same `JsScriptingInvocationRegistry` the HTTP endpoint uses.

- Pro: no HTTP surface; zero syscalls; best fit for same-process
  architecture. Reuses the registry + depth gate.
- Con: TS SDK grows a branch. Must keep wire semantics identical to the
  HTTP path (error shapes, depth gate, timeouts) or authors see
  environment-dependent behavior.

**C — `adb reverse` back to a host daemon.**
Works only when a laptop is running `./trailblaze`. Doesn't cover the
cloud device-farm shard — which is the most common on-device test
environment and the one we need to make CI-green. Non-starter as a
primary.

## Decision — Option B

Pick B. The cost of the SDK branch (≈30 lines, fully covered by the
integration test) is cheaper than running an HTTP server inside an
Android instrumentation process to serialize a call back into the same
JVM. Option A was tempting because it keeps the TS SDK simple, but the
*complexity* it adds lives in a worse place (OS sockets, port
lifecycle, device-farm networking edge cases) than the complexity B
adds (one binding and one `if` branch on a well-understood envelope
field).

## Implementation shape

- **Envelope**: `_meta.trailblaze` gains an optional `runtime` field.
  `"ondevice"` when Kotlin-side is the `:trailblaze-scripting-bundle`
  runtime; absent on subprocess / daemon paths (TS SDK treats absent
  as the HTTP path for backward compat with every existing host
  envelope).
- **TS SDK**: `client.callTool` inspects `ctx.runtime`. If
  `"ondevice"`, serialize the same `JsScriptingCallbackRequest` that the HTTP
  path builds, `await globalThis.__trailblazeCallback(json)`, parse the
  `JsScriptingCallbackResponse`, surface errors the same way. No other path
  differences — `JsScriptingCallbackResult.Error` / `CallToolResult(success:
  false)` / etc. all map to the same thrown `Error` shape.
- **Kotlin — shared dispatcher**: extract the session-match / depth
  gate / dispatch core out of `ScriptingCallbackEndpoint` into a
  `JsScriptingCallbackDispatcher` object in `:trailblaze-common`. The HTTP
  endpoint keeps its HTTP-specific concerns (loopback gate, body-size
  cap, content-type); the on-device binding calls `JsScriptingCallbackDispatcher`
  directly. Single source of truth for the dispatch semantics.
- **Kotlin — binding**: `QuickJsBridge` registers
  `__trailblazeCallback(requestJson: string): Promise<string>`. The
  binding parses `JsScriptingCallbackRequest`, calls `JsScriptingCallbackDispatcher`,
  serializes the `JsScriptingCallbackResponse` and resolves. Errors that escape
  (malformed JSON, etc.) resolve with a `JsScriptingCallbackResult.Error`
  envelope — the TS client surfaces them the same way the HTTP path
  does for non-2xx responses.
- **Bundle tool wiring**: `BundleTrailblazeTool` gains an optional
  `JsScriptingCallbackContext(toolRepo)` (no `baseUrl` — the binding replaces it).
  At `execute()` time, register with `JsScriptingInvocationRegistry`,
  build `_meta.trailblaze` with `runtime: "ondevice"` +
  `invocationId`, close the handle in `finally`. Same register-close
  shape `SubprocessTrailblazeTool` uses.

## What stays the same

- The `JsScriptingCallbackContract` wire types (`JsScriptingCallbackRequest`,
  `JsScriptingCallbackAction.CallTool`, `JsScriptingCallbackResult`, `JsScriptingCallbackResponse`) are
  unchanged. Adding a new transport, not a new contract.
- `JsScriptingInvocationRegistry` is unchanged. It's already
  process-wide — the on-device binding registers into the same
  singleton the HTTP endpoint reads from.
- Depth gate + timeout knobs (`JsScriptingCallbackDispatchDepth`,
  `MAX_CALLBACK_DEPTH`, `trailblaze.callback.timeoutMs`) carry over
  verbatim via the shared dispatcher.

## Non-goals

- Bundler automation is still separate. This work lands with
  a hand-crafted test fixture JS that mirrors what the bundler will
  eventually emit, same pattern as the existing
  `OnDeviceBundleRoundTripTest`.
- No new MCP primitives. The TS surface (`ctx.client.callTool`) is
  unchanged; we're only teaching it a second transport.
- No retry / backoff on the binding call. Binding failures are
  programmer bugs (malformed JSON, registry miss) and should fail
  loudly, not silently retry.

## Acceptance

A bundled TS tool that calls `ctx.client.callTool("otherTool", {…})`
from inside its handler produces the same composed result on-device as
on the host subprocess path. The `uitest-sample-app` CI step
exercises this every PR; a regression fails the required check.
