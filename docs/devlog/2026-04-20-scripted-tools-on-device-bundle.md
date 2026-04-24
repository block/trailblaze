---
title: "Scripted Tools PR A5 — MCP Toolsets Bundled for On-Device"
type: decision
date: 2026-04-20
---

# Trailblaze Decision 038 PR A5: MCP Toolsets Bundled for On-Device

> **Renumbered to PR A5 on 2026-04-20** per the toolset-consolidation
> amendment (`2026-04-20-scripted-tools-toolset-consolidation.md`), unchanged
> by the Option-2 amendment. This is the **only remaining home for QuickJS in
> scripted tool execution** — host-side scripted tools run as bun/node
> subprocesses per PR A3. The design below (in-process MCP transport,
> `trailblaze/requiresHost` filter) is unchanged and continues to be the
> target for this phase.

PR A5 takes the **same TypeScript source** an author writes for PR A3 (subprocess
MCP toolsets) and runs it **on-device inside QuickJS**. One TS codebase, two
deployment modes, zero authoring fork.

The key enabler: the MCP SDK itself is mostly pure JavaScript. With a bundler
(esbuild or Zipline's plugin) targeting a non-Node environment, the SDK + `zod`
compile to a QuickJS-runnable artifact. The only things that don't run on-device
are the author's own calls into Node-only APIs (`fetch`, `fs`, `child_process`,
etc.) — and those are declared explicitly so Trailblaze skips them during
on-device registration.

## Scope

Authors write the same `.ts` source documented in PR A3. For tools whose
implementation uses only pure JS + `trailblaze.execute()`, no additional work —
the same source runs on-device unmodified once bundled.

For tools that need Node-only APIs, the author declares that via the
`_meta["trailblaze/requiresHost"]: true` key on the tool (see the
conventions devlog). Trailblaze's on-device registration pass skips
those tools; the rest register normally.

### In scope

- **Build pipeline.** Produce a single JS bundle from the author's TS source +
  SDK + `zod`, targeting ES2020 / no Node built-ins. Initial path: authors bring
  their own `tsc` + `esbuild` and point Trailblaze at the pre-bundled `.js`.
  Longer-term path: Zipline's Gradle plugin automates this during APK assembly.
- **Artifact packaging.** The bundled JS ships as an Android asset or classpath
  resource, loaded at session start.
- **In-process MCP transport.** A custom `Transport` implementation for the
  SDK's `Server` that hands request messages directly to Kotlin instead of
  framing them onto stdio. No sockets, no JSON-RPC over the wire — just
  function calls across the QuickJS↔Kotlin bridge, carrying the same MCP
  message shapes.
- **Discovery.** On session start: evaluate the bundle in QuickJS, connect the
  in-process transport to the author's `Server` instance, fire `tools/list`,
  register the result.
- **Dispatch.** LLM selects tool → Trailblaze routes `CallToolRequest` through
  the in-process transport → SDK calls the author's handler → response flows
  back.
- **Capability-based registration filter.** Tools tagged with
  `_meta: { "trailblaze/requiresHost": true }` are skipped on on-device
  runs. Trails that depend on host-only tools fail fast with a clear
  error rather than silently degrading.

### Explicitly out of scope

- **Automated bundling in the Trailblaze build.** PR A5 accepts pre-bundled
  `.js` artifacts. Automating the tsc + esbuild pipeline as part of
  `./gradlew assemble` is a follow-up (probably gated on Zipline adoption,
  tracked separately in Decision 038's Open Questions).
- **npm package support beyond what the SDK + zod need.** Authors who want to
  use arbitrary npm packages need to verify those packages bundle cleanly for
  a non-Node target (many don't). Not PR A5's job to guarantee.
- **Hot-reload of bundles.** The bundle is baked into the APK / host artifact
  at build time. Runtime reloading is not in scope.

## Design decisions

### The bundler is the author's responsibility (initially)

For PR A5's first landing, we don't ship a Gradle plugin. Authors run their
own `tsc` + `esbuild`:

```bash
tsc && esbuild dist/tools.js \
  --bundle \
  --platform=neutral \
  --format=iife \
  --target=es2020 \
  --outfile=tools.bundle.js
```

And point Trailblaze at `tools.bundle.js`. This unblocks on-device support
without gating on a build-tooling PR. The Zipline migration (flagged in
Decision 038 Open Questions) automates this later.

This also happens to be the **permanent** path for binary-release consumers
(external users who consume published Trailblaze artifacts rather than
rebuilding). They don't run Trailblaze's Gradle build — they drop in a
pre-bundled artifact. Zipline benefits build-from-source consumers; BYO-bundle
is the forever-path for the rest.

### Capability gating at registration, not at call time

The `trailblaze/requiresHost` key is checked when the tool gets
registered, not when it's invoked. Host-only tools never appear in the
on-device tool list. The LLM can't select them, no runtime check needed, no
silent-failure foot-gun. If someone tries to run a trail that references a
host-only tool on-device, the error is at trail-validation time: "this trail
references `fetchUserInfo`, which is host-only and not registered in this
on-device session."

### Same MCP SDK, different Transport

The SDK's `Server` class doesn't care what transport carries its messages —
that's the whole point of the `Transport` interface. On host, PR A3 uses
`StdioServerTransport`. On-device, PR A5 uses `InProcessTransport`. The
author's `Server` instance and request handlers are identical in both cases.

The `InProcessTransport` is a small shim: `send(message)` hands the JSON-RPC
message (as an already-parsed JS object, no serialization round-trip needed)
to Kotlin via a QuickJS-exposed callback. Kotlin dispatches appropriately and
calls back via `onmessage` with the response. No JSON parse/serialize per
call — we skip the wire format since both sides are in the same process.

### What runs, what doesn't

The matrix authors should keep in mind:

| Construct | On-device (QuickJS) |
|---|---|
| Strings, numbers, regex, `Date`, `JSON`, `Math` | ✓ ECMAScript core |
| `async` / `await`, Promises | ✓ Supported |
| MCP SDK's `Server`, handler registration, dispatch | ✓ Bundles cleanly |
| `zod` schema validation | ✓ Pure JS |
| `trailblaze.execute(...)` calls | ✓ Via in-process binding |
| `fetch` / `fs` / `child_process` / `http` | ✗ Not in QuickJS |
| Random npm package | Depends on whether it bundles cleanly |

Esbuild fails the build loudly if the author's code imports something that
doesn't resolve in the target environment. That's a feature: authors know
before deployment whether their tool can run on-device.

## Open questions

- **Zipline migration timing.** Decision 038 Open Questions flagged "when to
  upgrade from bare `quickjs-kt` to Zipline" as a follow-up. PR A5 shipping
  with BYO-bundle is one data point in favor of delaying — authors can ship
  without Zipline. But Zipline's typed `ZiplineService` interfaces would
  replace the hand-written in-process transport with something
  compiler-checked. Revisit after PR A5 lands.
- **Multi-toolset composition.** If multiple toolsets register on-device and
  their tools share names, what wins? Error out at registration time with a
  conflict; authors resolve by renaming.

## References

- `docs/devlog/2026-04-20-scripted-tools-execution-model.md` — Decision 038,
  the umbrella roadmap.
- `docs/devlog/2026-04-20-scripted-tools-mcp-subprocess.md` — PR A3, the
  subprocess deployment mode. PR A5 reuses PR A3's TS authoring source
  verbatim.
- `docs/devlog/2026-04-20-scripted-tools-a2-sync-execute.md` — PR A2, ships the
  `trailblaze.execute()` in-process callback that on-device toolsets use to
  invoke Trailblaze primitives.
- `docs/devlog/2026-04-20-scripted-tools-mcp-conventions.md` — the MCP
  extension conventions, including `_meta["trailblaze/requiresHost"]`
  used here for capability-based filtering.
