---
title: "Consolidate scripted-tool surfaces: one SDK, per-tool runtime drives transport"
type: decision
date: 2026-06-17
---

# Consolidate scripted-tool surfaces: one SDK, per-tool runtime drives transport

## Summary

Trailblaze had two TypeScript authoring surfaces for scripted tools — `@trailblaze/scripting`
and `@trailblaze/tools` — which looked like competing SDKs and made the runtime story confusing.
The decision: **collapse to one surface, `@trailblaze/scripting`**, and let each tool's declared
`runtime:` decide how it runs — **in-process in QuickJS (the default)** or, only when the tool's
own TypeScript needs Node APIs, a **host-side `bun` subprocess**. The overwhelming majority stay
in-process, because scripted tools are *orchestrators*: the things that touch the system (taps,
broadcasts, app launches, Maestro commands) are framework/driver tools that execute inside
Trailblaze (Kotlin); the TypeScript just composes them. MCP stops being an author-facing concept
— it survives only as the wire protocol for the rare subprocess boundary.

> **Status — DONE (2026-06).** This collapse has shipped. `@trailblaze/scripting` was split into
> two bundle *profiles* from one authoring surface — the slim in-process entry
> (`sdks/typescript/src/in-process.ts`) and the full subprocess entry — and the in-process bundlers
> (`DaemonScriptedToolBundler`, the build-logic `BundleAuthorToolsTask`, and the `:trailblaze-common`
> framework bundler) alias `@trailblaze/scripting` to the slim entry. The three former
> `@trailblaze/tools` consumers (`openUrl` + the two sample-app tool files) were migrated to the
> typed `export const X = trailblaze.tool<I>(...)` form, and **the `@trailblaze/tools` package
> (`sdks/typescript-tools/`) has been deleted.** The references to it below describe the
> pre-collapse state and are kept as a historical record.

## Verified current state (the baseline that motivated this)

From code, not kdoc. Both surfaces target QuickJS, but the docs over-claimed what each does:

| | `@trailblaze/scripting` | `@trailblaze/tools` |
|---|---|---|
| Real tools using it | many (example trailmaps + downstream targets) | 3 (`openUrl` + 2 sample-app examples) |
| Typed bindings | Generated `client.tools.<name>` + private `callTool` | Hand-seeded proxy only; generator unbuilt |
| MCP framing | Yes (justified at the bun-subprocess boundary) | No (reads `globalThis.__trailblazeTools` directly) |
| On-device runtime | Not launched (its on-device bits were fiction) | Yes — the runtime `AndroidTrailblazeRule` launches |

The "fictional" docs — a `__trailblazeCallback` / `QuickJsBridge` in-process binding referenced
everywhere but **defined nowhere**, a `:trailblaze-scripting-bundle` README naming runtime classes
that don't exist, etc. — were corrected in this PR (#3809).

**Composition status, then and now.** At this baseline, in-process cross-tool composition was
*unbuilt in production*: the on-device launcher installed a `CALL_NOT_WIRED` stub. **It has since
been wired (PR #3813):** the on-device launcher now installs the real, already-tested
`SessionScopedHostBinding`, so an in-process tool can call a framework/driver tool (e.g.
`openUrl → maestro`) or a tool in another bundle. Note the correction to the original plan — the
binding was **wired, not built from scratch**: `SessionScopedHostBinding` already existed and is
reached through the `__trailblazeCall` host binding; there was never a `__trailblazeCallback`
installer to write.

## The decision

**One author surface: `@trailblaze/scripting`** (it has the typed-binding generator, the private
`callTool`, and dozens of consumers). Per-tool `runtime:` is the single source of truth, resolved at
load time by `ScriptedToolRuntime.resolve`:

- `runtime: inProcess` is the **unconditional default** → embedded QuickJS, composes by calling
  back into the Kotlin tool repo → **no MCP**. The right fit for orchestration tools.
- `runtime: subprocess` is **explicit opt-in only** (no extension heuristic — a `.js` file is not
  auto-routed; PR #3819 removed it) → host-side `bun` process, MCP transport → only for tools whose
  own TS needs Node APIs (`node:fs`, `child_process`). A device can't spawn a subprocess, so
  `subprocess` effectively means "host-only tool."

**Composition model.** A `trailblaze.call(...)` / `client.tools.<name>(...)` is a *real,
observable dispatch* through the framework (resolve in the repo, run with the live context).
Reusing logic *within the same file* is a plain local function — a utility, not a dispatched tool.
Cross-tool dispatch to a framework/driver tool, or to a tool in **another** bundle, works;
composing a tool in the **same** bundle is refused with a directed error (it would deadlock the
shared QuickJS engine — the author calls a local function or splits the tool into its own bundle).

## One runtime, one engine (host = device)

In-process tools execute through a **single shared engine**, `QuickJsToolHost`, in the
`:trailblaze-quickjs-tools/jvmAndAndroid` source set — compiled identically for the host JVM and
the Android device. There is no separate non-QuickJS host path, so a host run exercises the exact
code an on-device run does. The one remaining duplication is **two launcher wirings**
(`LazyYamlScriptedToolRegistration` host-side, `QuickJsToolBundleLauncher` on-device) that each
create the host + install the binding + set the execution context. **These must be unified onto
one launch path** so there is genuinely one implementation to maintain — on-device-only bugs are
the hardest to find, so the wiring must not diverge.

## Why this direction (and not the reverse)

The earlier stated intent (in the `@trailblaze/tools` module README) was the opposite — retire
`@trailblaze/scripting` as the "legacy MCP-shaped path." We are reversing that:

- `@trailblaze/tools` exists for exactly one reason: MCP-free in-process execution. Once
  `@trailblaze/scripting` can do that too (slim profile, below), it has no remaining justification.
- The hard piece (in-process composition) is one shared problem regardless of surface — solve it
  once, on the mature surface that already has typed bindings and 38 consumers, rather than
  rebuilding the generator + composition on the 3-tool newcomer.
- Migrating 3 tools beats migrating dozens.

MCP is not overkill *everywhere* — at a real bun-subprocess boundary it is the right wire protocol.
It is overkill only when dragged into the in-process path, where there is no process boundary to
bridge. This decision scopes MCP precisely to where it earns its keep.

## Slim on-device bundle (the bundle-size win)

Each tool bundles independently (one `.bundle.js` per tool, its own engine). Because a device can't
spawn a subprocess, the slim-vs-full choice is **per-target, not per-tool**: the **on-device
bundler always emits a slim SDK profile**; the host bundler uses the full one (where size is
irrelevant and subprocess is possible). Slim = register + in-process compose, with **no MCP server,
no subprocess transport, and no Zod/Ajv** (validation moves to the Kotlin layer, which on-device is
right there on the device). It keeps full cross-tool composition, so orchestration tools lose
nothing.

Why it matters — measured (esbuild `--metafile` on `src/index.ts` with the production flags,
unminified, ~1.24 MB bundled):

| package | KB | % |
|---|---|---|
| `zod` | 701 | 56% |
| `ajv` (+ `ajv-formats`, `fast-uri`, `json-schema-traverse`, `fast-deep-equal`) | ~297 | 24% |
| `@modelcontextprotocol/sdk` | 172 | 14% |
| `zod-to-json-schema` | 41 | 3% |
| **Trailblaze SDK source** | **33** | **3%** |

The actual SDK logic is ~33 KB; ~97% is schema-validation + MCP machinery. The slim profile lands
in the tens of KB (a >10× reduction). Today `@trailblaze/tools` in-process tools are already this
small — the slim profile is what keeps the consolidation onto `@trailblaze/scripting` from
*regressing* on-device bundle size.

**Mechanism: a build-time slim entrypoint** that simply doesn't import the heavy modules. A runtime
`if/else` does **not** help — esbuild keeps both branches' top-level `import`s, and QuickJS parses
the whole file regardless; a lazy `import()` inside one IIFE bundle doesn't defer the parse either,
and the subprocess/MCP code is dead-on-device anyway (a device never runs it). So the right move is
to *omit* it at build time, not defer it.

## Build sequence

**Done:**
1. Doc-truth fixes + this decision record (PR #3809).
2. Wire the on-device launcher to the real `SessionScopedHostBinding` + a same-bundle re-entry
   guard (PR #3813). In-process composition — tool→driver and cross-bundle tool→tool — now works.

**Remaining:**
3. **Slim on-device entrypoint** — a `@trailblaze/scripting` entrypoint with no Zod/Ajv/MCP
   (plain-JSON-Schema authoring, validation host-side); the on-device bundler emits against it.
4. **Unify the two launcher wirings** (`LazyYamlScriptedToolRegistration` + `QuickJsToolBundleLauncher`)
   onto one shared launch path used by both host and device.
5. **Migrate the 3 `@trailblaze/tools` tools to `@trailblaze/scripting`** and route scripting tools
   through the one `QuickJsToolHost` engine — including reconciling the binding name (the scripting
   SDK's in-process path calls `__trailblazeCallback`; the host installs `__trailblazeCall` — align
   them).
6. **Delete the `@trailblaze/tools` SDK** and its parallel framing.
7. `objectiveStatus` → TS (loop-coupled; solo).

## Open questions / follow-ups

- Re-entrant **same-bundle** in-process composition is refused today (would deadlock the shared
  engine); lifting it would need per-call engine isolation. Not needed for orchestration tools.
- **No live on-device run yet** (no device available) — #3813 is validated by unit tests + the
  shared-engine argument; an Android trail exercising `openUrl → maestro` should confirm it.
- `openUrl` (PR #3803) is on `@trailblaze/tools`; it functions on-device once #3813 merges, and
  migrates to `@trailblaze/scripting` in step 5.

## Related

- `2026-04-20-scripted-tools-on-device-bundle.md` — original on-device bundle design.
- `2026-04-22-scripting-sdk-authoring-vision.md`, `2026-04-22-scripting-sdk-client-calltool.md`
  — the `@trailblaze/scripting` surface this consolidates onto.
- `2026-05-28-bun-only-runtime.md` — the subprocess runtime MCP is scoped to.
