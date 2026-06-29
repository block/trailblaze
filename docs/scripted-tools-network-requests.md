---
title: Scripted Tools — Network Requests
---

# Scripted Tools — Network Requests

Scripted tools often need to talk to an HTTP endpoint: a device bridge listening on
`localhost:<port>`, a REST API, a fixture server you stood up for a test. There are two
ways to make an HTTP call from a tool, and which one you reach for is decided by **where
your tool has to run**, not by how much you like one API over the other.

- **`fetch`** — a real, in-process HTTP client. No extra process, no `node_modules`.
  This is the default and covers almost everything.
- **A subprocess tool** (`runtime: subprocess`) — a full Bun/Node process with the entire
  Node networking surface. More capable, but **host-only** (never runs on a device) and
  pays a small per-call process round-trip.

Start with `fetch`. Reach for a subprocess only when `fetch` genuinely can't do what you
need.

## `fetch` — the in-process default

On the host, the runtime gives a scripted tool a WHATWG-shaped `globalThis.fetch`, backed
by an [`OkHttp`](https://square.github.io/okhttp/) client. It runs **inside the same
QuickJS engine that dispatches your tool** — no subprocess fork, no `node_modules`
resolution, so a call is just a function call into the host. Because it's in-process,
`fetch` is also the *only* HTTP primitive that could ever run on a device — a subprocess
(below) structurally cannot — though the default on-device runner doesn't install the
binding yet (see [Where `fetch` runs](#where-fetch-runs)). For now, treat `fetch` as a
host-dispatched capability.

```ts
import { trailblaze } from "@trailblaze/scripting";

interface GreetArgs {
  /** Base URL of the service to greet, e.g. http://localhost:8080 */
  baseUrl: string;
}

export const myapp_greet = trailblaze.tool<GreetArgs>(async (input) => {
  const res = await fetch(`${input.baseUrl}/hello`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name: "trailblaze" }),
  });

  // A 4xx/5xx still RESOLVES (you got a response) — branch on the status yourself.
  if (!res.ok) {
    throw new Error(`POST ${input.baseUrl}/hello failed with HTTP ${res.status}`);
  }
  return await res.text();
});
```

The `Response` you get back supports the surface you'd expect — `res.status`, `res.ok`,
`res.headers.get(...)`, `await res.text()`, `await res.json()`. It is **text/JSON only**:
`res.arrayBuffer()` is deliberately not supported and rejects, so there's no binary-body
path today. As with the browser/Node `fetch`, a non-2xx status is **not** an exception:
the promise resolves and you inspect `res.ok` / `res.status`. Only a *transport* failure —
connection refused, DNS failure, a timeout — rejects the promise. Wrap the call if you want to attribute that rejection to a
specific endpoint:

```ts
let res: Response;
try {
  res = await fetch(url, { method: "POST", body });
} catch (e) {
  throw new Error(`POST to ${url} failed: ${e instanceof Error ? e.message : String(e)}`);
}
```

`fetch` is the clean replacement for the old pattern of shelling `curl` through
`ctx.tools.exec`. If you find a tool still doing that to reach an HTTP endpoint, it can
almost always move to `fetch`.

### Where `fetch` runs

The **host daemon installs `fetch` by default** — so it's present in `trailblaze check`,
in `trailblaze tool`/`trailblaze run`, and in the daemon-backed agent loop. That's where
the vast majority of scripted-tool HTTP happens.

The binding itself is **portable**: it's compiled for Android as well as the JVM (OkHttp
runs on Android's ART), which is why `fetch` — not a subprocess — is the right primitive
for a tool you intend to run on-device. Note that the default on-device runner does not
install the binding yet, so a tool that *must* execute on-device shouldn't assume `fetch`
is present today; host-dispatched tools always have it.

### Timeouts

The host `fetch` client applies sane default bounds (a connect timeout and an overall
call timeout) so a tool pointed at an endpoint that accepts the connection but never
responds fails fast instead of hanging the daemon. You don't have to set them for the
common case.

### Constraining which hosts `fetch` can reach

By default `fetch` is **unrestricted** — it can reach any host, exactly like the
`ctx.tools.exec` + `curl` escape hatch it replaces, so it isn't artificially weaker. An
embedder that wants to constrain it can opt into an allow-list (loopback-only, or a named
set of hosts); a denied host then fails with a clear error. This is a framework-embedding
concern rather than a per-tool one — most tool authors never touch it.

## When `fetch` isn't enough: subprocess tools

`fetch` is plain HTTP. If your tool needs something HTTP-the-primitive can't express —
mutual-TLS with a client certificate, a bespoke auth helper, streaming to disk, a native
networking module, or any other Node-only API — select the **host subprocess** runtime by
setting `runtime: subprocess` in the tool's YAML descriptor. The framework then runs that
tool's invocations in a Bun subprocess with the full Node-compatible surface (`node:fs`,
`node:crypto`, `child_process`, native modules, …). See
[Scripted Tools (TypeScript) → Runtime](scripted-tools-typed-authoring.md#runtime-quickjs-in-process-by-default)
for the mechanics.

> **`runtime: subprocess` is the selector, not `requiresHost: true`.** `runtime` is a
> descriptor field, not part of the typed `trailblaze.tool(...)` spec — a pure-`.ts` tool
> opts in via a sibling `<name>.yaml` carrying `runtime: subprocess`. The separate typed
> flag `requiresHost: true` is only an on-device *visibility* gate; it does **not** change
> the runtime, so a tool marked `requiresHost: true` but left on the default runtime still
> runs in QuickJS, where `node:*` is absent and a `node:fs` import fails.

Two costs come with that capability, and both follow from the fact that it's a *separate
process*, not in-process code:

1. **It won't run on a device.** A subprocess is a real OS process spawned on the host.
   There is no subprocess to spawn on a phone or simulator, so `runtime: subprocess` tools
   are host-only by nature — the on-device runner never registers them. If your tool has
   to run on-device, you can't use this runtime; stick to `fetch`.
2. **It isn't in-process, so it has protocol overhead.** Each invocation crosses a small
   IPC boundary to the subprocess and back. That overhead is only **single-digit
   milliseconds**, negligible against the network call itself — but it's real, and it's
   exactly the overhead the in-process `fetch` does not have. For a tight loop of many
   small HTTP calls, in-process `fetch` is meaningfully cheaper; for the occasional call
   that genuinely needs Node APIs, the milliseconds don't matter.

The rule of thumb: **`fetch` in-process is the default, and the only HTTP primitive that
*could* run on-device (a subprocess structurally can't — though the on-device runner
doesn't install the binding yet). A subprocess (`runtime: subprocess`) is the escape hatch
for the capabilities plain HTTP can't reach, at the cost of being host-only plus a few
milliseconds per call.**

## Where to go next

- **[Scripted Tools (TypeScript)](scripted-tools-typed-authoring.md)** — the canonical
  authoring guide, including the [Runtime](scripted-tools-typed-authoring.md#runtime-quickjs-in-process-by-default)
  section that covers the subprocess runtime in full.
- **[Scripted Tools — Snapshot Queries](scripted-tools-snapshot-queries.md)** — reading
  the on-screen hierarchy from a tool.
- **[Trailmaps](trailmaps.md)** — how tools are registered and discovered.
