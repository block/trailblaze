# trailblaze-scripting-fetch

A real `globalThis.fetch` for in-process (QuickJS) scripted tools, backed by a host
[OkHttp](https://square.github.io/okhttp/) client. This is the clean replacement for tools that
otherwise shell `curl` through `ctx.tools.exec` to talk HTTP — e.g. a tool POSTing to a device
bridge listening on `localhost:<port>`.

## Why a separate module

The in-process scripted-tool runtime is QuickJS (`io.github.dokar3:quickjs-kt`), wrapped by
`QuickJsToolHost` in `:trailblaze-quickjs-tools`. quickjs-kt ships **no networking** — the host
binds only `__trailblazeCall` (the `ctx.tools` bridge) and a `console` shim. Networking is added
here, in its own module, so the lean engine module stays **OkHttp-free** for any runtime that
doesn't want networking. A runtime installs `fetch` by passing an `OkHttpFetchExtension` to
`QuickJsToolHost.connect(engineExtension = …)` (or to `QuickJsToolBundleLauncher.launchAll`):

- **Host daemon: yes** — the host scripted-tool launchers install it (unrestricted).
- **On-device: yes** — the on-device launchers (`AndroidTrailblazeRule`,
  `OnDeviceScriptedToolBundleLauncher`) install the same extension, so a tool gets the same
  `fetch` whether it runs on-device or host-dispatched. OkHttp runs on ART, which is why this
  module is `jvmAndAndroid` like the engine module.

## What it gives the author

The WHATWG `fetch` surface, kept basic:

```ts
const res = await fetch("https://localhost:8080/command", {
  method: "POST",
  headers: { "content-type": "application/json" },
  body: JSON.stringify({ flag: "some_flag", value: true }),
});
if (!res.ok) throw new Error(`bridge returned ${res.status}`);
const data = await res.json();
```

`Response` exposes `status` / `statusText` / `ok` / `headers.get(name)` / `text()` / `json()`.
`text()` / `json()` buffer the whole response body into memory. Streaming bodies and
`arrayBuffer()` are out of scope — a tool that needs them belongs on the `runtime: subprocess`
path (bun's full `fetch`). The `fetch` / `Request` / `Response` / `Headers`
TypeScript declarations live in the SDK (`sdks/typescript/runtime-globals.d.ts`) and are bundled
into `@trailblaze/scripting`'s `.d.ts`.

## Posture & safety

- **Author-only.** Like `ctx.tools.exec`, `fetch` is surfaced to scripted-tool authors, never to
  the LLM.
- **TLS: validated, except on device-local hosts.** Certificates are checked normally for every
  real host. Validation (and hostname verification) is skipped for `localhost` / `127.0.0.1` /
  `::1` only — plus `10.0.2.2` when running on Android, since the emulator's host alias is an
  ordinary routable address to the host JVM that installs this same extension. Those are the
  addresses where Trailblaze's own HTTPS surfaces live and present
  **self-signed** certs no trust store can validate (the on-device server; the host daemon over
  `adb reverse`). Turning validation off globally, as the rest of the Trailblaze HTTP stack does,
  would silently downgrade a tool's call to a real API. This is what lets a purely on-device run —
  a device farm, no `adb reverse`, no host to proxy through — reach a local endpoint with nothing
  installed on the device.
- **Speak `https`, not `http`, from a tool that runs on-device.** Android denies cleartext from
  targetSdk 28, so a plain-`http` call fails before any socket opens while the identical call
  works host-side. With self-signed certs accepted on loopback (above), `https` covers the
  local-bridge case without a cleartext opt-in in the runner APK.
- **Host access: unrestricted by default.** `fetch` reaches any host, exactly like the
  `ctx.tools.exec` + `curl` it replaces — it isn't artificially weaker than the escape hatch, and
  keeping a recorded run replay-deterministic is the author's responsibility (same as `exec`). A
  deployment that wants to constrain `fetch` opts into a `FetchHostAllowlist`:
  `FetchHostAllowlist.localhostOnly()` (loopback only) or `FetchHostAllowlist.allowHosts(...)` (a
  named set); a denied host then fails with a clear error **before** any socket opens.
- **Redirects.** Followed by default (standard WHATWG `fetch`). When a *restrictive* allow-list is
  in effect, redirects are NOT followed — otherwise a permitted host could 30x past the allow-list
  to a denied one (the list checks the request URL's host only). Under a restrictive list, a tool
  that must follow a redirect reads `res.status` / `res.headers.get("location")` and issues a
  second `fetch` (re-checked).
- **Logging.** Each request emits a breadcrumb (quiet-suppressed on the host; visible with `-v` or in
  the log file): `[OkHttpFetchExtension] <METHOD> <status> <url> (<ms>ms)` — e.g.
  `POST 200 https://host.com/path/goes/here?<redacted> (123ms)`. Failures and allow-list denials log
  the same shape with `FAILED` / `BLOCKED` in the status slot. **Userinfo (`user:pass@`), the query
  string and the fragment are stripped**: this binding also runs on-device, where `Console.log` goes
  to logcat, quiet mode is a no-op, and logcat is streamed into the session's `device.log` — which CI
  zips and uploads as a build artifact. So a credential in any of those would otherwise leave the
  device. Scheme/host/port/path are kept because they identify the endpoint — which is a **tradeoff,
  not a safe default**: a webhook-shaped URL carries its secret in the path and will be logged, so
  don't rely on this redaction for one. Request/response **headers and bodies are never logged**.

## Customizing the client (timeouts, etc.)

The default client uses 30s call/read/write and 10s connect timeouts and OkHttp's default
`ProxySelector`. Inject a custom `OkHttpClient` for a slow bridge or other tuning:

```kotlin
val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
OkHttpFetchExtension(client = client) // default allowlist = unrestricted
// …or also constrain hosts: OkHttpFetchExtension(client = client, allowlist = FetchHostAllowlist.localhostOnly())
```

## Proxy support — out of scope by design (intentional, not a deferred feature)

This binding does **not** take a proxy option, and that is intentional:

- WHATWG `fetch` has no proxy concept — proxy is a property of the backing client.
- The binding uses OkHttp's default `ProxySelector`, which honors the JVM proxy **system
  properties** (`-Dhttp.proxyHost/Port`, `-Dhttps.proxyHost/Port`, `http.nonProxyHosts`). Put
  `localhost` in `nonProxyHosts` for the device-bridge case. OkHttp does **not** read
  `HTTP_PROXY` / `HTTPS_PROXY` env vars by default.
- A tool that genuinely needs explicit / per-call proxying uses an **existing escape hatch**
  instead of us adding proxy here:
  1. `ctx.tools.exec` with `curl --proxy <url>` (curl honors `http(s)_proxy` / `NO_PROXY`), or
  2. a `runtime: subprocess` tool, where bun's `fetch` exposes a `proxy` option and honors the
     proxy env vars.

  Both are author-only and host-side — the correct scope for proxying. Please do not add a proxy
  option to the `fetch` binding; point "I need a proxy" requests at those hatches.
