---
title: Configuration
---

## On-Device Android Instrumentation Arguments
* `trailblaze.aiEnabled` (defaults to `true`) - This will have the Trailblaze SDK send all requests to the LLM.  When `false`, only recordings can be used.
* `trailblaze.reverseProxy` (defaults to `false`) - This will enable the reverse proxy for all Trailblaze traffic.
  * When `false`, logging traffic is sent to `https://10.0.2.2:<httpsPort>`, the default Android Emulator networking loopback address.
  * When `true`, the logs are sent through `https://localhost:<httpsPort>` and using `adb reverse tcp:<httpsPort> tcp:<httpsPort>` are forwarded to the host running the Trailblaze app.
    * This means all Trailblaze SDK Traffic is re-routed through `adb` and then the logs server reverse proxies the traffic to the final host.
    * This is important because it allows the Trailblaze Agent to run on-device, but not require a network connection.
    * It is also helpful/important because in the future it will allow you to not send your API Keys to the device itself, but add the `Authorization` information via the reverse proxy.
* `trailblaze.httpsPort` (defaults to `52526`, i.e. `trailblaze.port` + 1) - The HTTPS port for the Trailblaze server. Override this when running multiple Trailblaze instances.
* `trailblaze.logsEndpoint` - Defaults to the same values as the `reverseProxy` uses.  You can use this value if you want to use a remote logs server.  NOTE: Logging timeouts are set to 5 seconds as they are expected to be fast.

## Scripting Callback Channel

Tuning knobs for the `/scripting/callback` endpoint used by the TypeScript scripting SDK's `client.callTool(...)` round-trip. Defaults are production-ready; override only when a slow emulator or unusual composition graph needs more headroom.

* `-Dtrailblaze.callback.timeoutMs` (JVM system property, defaults to `30000`) — Per-callback dispatch timeout on the daemon side. Bounds how long a single `client.callTool(...)` dispatch can run before the daemon returns a structured timeout error. Raise when a target tool is legitimately slow (e.g. waiting for a screen to settle on a slow emulator).
* `TRAILBLAZE_CLIENT_FETCH_TIMEOUT_MS` (env var, defaults to `32000`) — Client-side fetch timeout in the subprocess. Kept ~2 s above the daemon's default so the daemon is normally the one that surfaces a structured timeout. **If you raise `trailblaze.callback.timeoutMs`, raise this in lockstep** — otherwise the client aborts the HTTP request before the daemon can return and the daemon-side override is defeated. Sampled once at SDK module load; must be set before `import { trailblaze } from "@trailblaze/scripting"`.
* `-Dtrailblaze.callback.maxDepth` (JVM system property, defaults to `16`) — Reentrance cap for recursive callback chains. A subprocess tool that calls back into the daemon to dispatch another subprocess tool counts as one level; the cap prevents runaway recursion from wedging a session until the outer agent timeout fires. Raise only if you have a legitimate deep-composition use case (e.g. recursive tree-walker).
* `-Dtrailblaze.callback.maxBodyBytes` (JVM system property, defaults to `1048576` / 1 MB) — Maximum accepted `JsScriptingCallbackRequest` body size. Requests whose declared `Content-Length` exceeds this are rejected with HTTP 413 before buffering. Real callback payloads are tiny (invocation id, session id, a single action with a JSON-string args field) so the cap is pure belt-and-suspenders against a buggy subprocess emitting a runaway args string. Raise only if a legitimate tool needs to pass a very large args payload through the callback channel.