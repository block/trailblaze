---
title: "Scripted Tools — Toolset Consolidation & Revised Sequencing"
type: decision
date: 2026-04-20
---

# Trailblaze Decision 038 Amendment: Toolset Consolidation

> **Further amended 2026-04-20 by the Option-2 amendment** (see
> [§ Option-2 Amendment](#option-2-amendment-2026-04-20) at the bottom of this
> document). That amendment collapses the former PR A3 (in-process QuickJS
> toolsets) and PR A4 (subprocess MCP) into a single host-side PR A3: a
> **bun/node subprocess speaking stdio MCP**, using the real MCP SDK. QuickJS
> moves exclusively to PR A5 (on-device bundle). If you're landing here to
> pick up Decision 038 work, **read the Option-2 Amendment first** — the
> phase table and action plan in the middle of this document are historical,
> and any earlier sections that describe the pre-Option-2 authoring shape
> (including the old `tools[]` + `execute()` toolset export contract) are
> also superseded. Authors now use the real MCP SDK
> (`server.registerTool(...)` or `server.setRequestHandler(...)`) directly.

Supersedes the inline-`script:` authoring surface established in PR A and PR A2
(both merged). Re-sequences the remaining Decision 038 work around a single
universal authoring pattern: **one `.ts` toolset file = one or more registered
tools**.

## The decision

**All TS-authored Trailblaze tools come from toolset files.** Never inline JS in
YAML. Ever.

- Inline `script: { source: "..." }` (PR A's current shape) is **deprecated**.
  The data-class field stays for binary compatibility with the just-merged PR A;
  docs, examples, and new work do not use it.
- `script: { file: "./x.ts" }` (proposed in this conversation, not implemented)
  is **rejected**. Single-file-single-tool is inconsistent with the toolset
  pattern and creates a second mental model.
- The **universal** authoring pattern is: author writes a `.ts` toolset file
  exporting `{ tools: Tool[], execute(toolName, params): CallToolResult }`. The
  toolset registers at config level; individual tools become globally addressable
  in trail YAML and visible to the LLM (per each tool's
  `_meta["trailblaze/isForLlm"]` key).

This matches the MCP subprocess (`2026-04-20-scripted-tools-mcp-subprocess.md`)
and on-device bundle (`2026-04-20-scripted-tools-on-device-bundle.md`) devlogs
already in main — those already specified toolset-per-file. This amendment
extends the same pattern to the in-process case (host-side QuickJS, no bundler),
which was the missing piece.

## Why consolidate

- **One mental model.** Authors learn "write a toolset file," not "write an
  inline script OR a toolset depending on context." The deployment mode
  (in-process / subprocess / on-device bundle) is a config-level concern, not an
  authoring concern.
- **Symmetry across deployment modes.** The same `.ts` source can potentially
  run in-process, as a subprocess, or bundled on-device. Capability-based
  filtering (`trailblaze/requiresHost` per the conventions devlog) decides what
  registers where.
- **Kills inline JS in YAML.** Load-bearing ergonomics fix — inline JS past ~3
  lines is unmaintainable (escaping, no IDE support, no types, no lint, no tests).

## Revised sequencing

Previously (now obsolete):
- PR A (merged) — inline `script:` with `source:`
- PR A2 (merged) — `trailblaze.execute()` callback
- PR A3 (in-flight) — subprocess MCP toolsets
- PR A4 — on-device bundle

**Revised (this amendment):**

| Phase | Scope | Status |
|---|---|---|
| PR A (**done**) | QuickJS bridge primitive — inline `source:` is now **legacy**, not recommended. | Merged |
| PR A2 (**done**) | `trailblaze.execute()` callback. The primitive stays — it's the execution contract toolsets will use internally. | Merged |
| **PR A3** [NEW / renumbered] | **In-process TS toolsets.** Host-side QuickJS, no bundler, no subprocess. `trailblazeInProcessToolsets:` config section. Replaces inline `script:` as the recommended authoring pattern. | **Next up** — start here |
| PR A4 [was A3] | **Subprocess MCP toolsets.** Same `.ts` file format, Node runtime, stdio MCP transport. Bumped from PR A3. | Deferred until new PR A3 lands |
| PR A5 [was A4] | **On-device bundle toolsets.** Same `.ts` file format, Gradle-plugin-bundled for QuickJS on-device. Bumped from PR A4. | Deferred until PR A4 lands |
| PR A4 Phase 2 (was 1B) | **Callback direction.** Subprocess → Trailblaze primitives via HTTP proto-JSON per Decision 029. Applies to PR A4 (subprocess); PR A5 (on-device) uses the in-process binding instead. | Deferred |

The renumbering is annoying but the inserted in-process-toolsets step is
load-bearing — without it, subprocess and on-device would each have their own
toolset-loading story, and we'd never have a host-side-only toolset path.

## State at time of this amendment

**The subprocess-MCP branch** (the branch where the old PR A3 was in flight):
- Contains only documentation commits — the scope-capture devlog for the *old*
  PR A3 (subprocess MCP, now renumbered to PR A4) and a few documentation
  polish commits.
- No implementation code exists on this branch.

**The in-flight draft PR** for the old PR A3:
- Titled "PR A3 Phase 1: subprocess MCP toolsets."
- **Will be closed** with a pointer to this amendment. The subprocess work it
  was scoping is still valid — just renumbered to PR A4 and blocked on the new
  PR A3 landing first.

**The subprocess-impl devlog** (`2026-04-20-scripted-tools-a3-subprocess-impl.md`,
on this branch, not yet in main):
- Will be marked as "superseded / deferred" at the top, pointing readers here.
- Content is still the design target for the eventual subprocess PR (new PR A4),
  so it doesn't get deleted — just flagged.

## Action plan for the next agent

**Don't start by coding.** Start by aligning on the new PR A3 scope.

1. **Create a fresh branch off main** for the new PR A3 (in-process TS
   toolsets). Suggested slug: `scripted-tools-inprocess-toolsets` (prefix
   with your team's usual branch-naming convention).

2. **Write the new PR A3 scope devlog** —
   `2026-04-20-scripted-tools-a3-inprocess-toolsets.md`. Load-bearing contents:
   - Config shape: `trailblazeInProcessToolsets: [{ file: "./tools/x.ts" }]`
     (top-level in `trailblaze.config.yaml`).
   - Toolset file format: `.ts` file exporting `tools: Tool[]` + `execute`
     function. Matches the MCP conventions devlog's types (`TrailblazeTool` with
     `_meta["trailblaze/*"]` for metadata).
   - TS→JS build: author's responsibility (run `tsc`/`esbuild`), Trailblaze
     resolves `.ts` → sibling `.js`, clear error if missing.
   - Registration: at session start, for each declared toolset, evaluate the
     `.js` in QuickJS, read the `tools` export, register each entry as an
     `ExecutableTrailblazeTool` whose `execute()` routes through the toolset
     file's `execute(toolName, params)` function.
   - `_trailblazeContext` envelope injected into each call per the conventions
     devlog — same shape as the subprocess path will use.
   - Capability filtering: `_meta["trailblaze/requiresHost"]: true` tools
     are registered for in-process mode (this is host-side);
     `_meta["trailblaze/requiresHost"]: false` tools are in-scope for
     later on-device bundling.
   - `script: { source: "..." }` is legacy: docs stop mentioning it;
     data-class field stays for binary compat. No runtime warning — the
     form stays load-bearing for PR A's published artifact contract.

3. **Commit the scope devlog, open a draft PR**, start implementation:
   - Toolset-file loader (evaluate `.js`, extract `tools[]`, register each).
   - Dispatcher for toolset tools (routes each call to the toolset's `execute`).
   - Config parsing for `trailblazeInProcessToolsets:`.
   - Tests: fixture toolset file, verify registration, verify dispatch, verify
     `_trailblazeContext` injection, verify capability filtering.

4. **After new PR A3 merges**, pick up the old subprocess work as
   renumbered PR A4. That work's design (in the `-a3-subprocess-impl.md`
   devlog) is still valid — just deferred.

## Open questions for the next agent to resolve

These were flagged during implementation planning but not decided:

- **Module home for toolset loading.** Options: extend `:trailblaze-scripting`
  (natural, already holds QuickJS integration) vs. new
  `:trailblaze-scripting-toolsets` vs. inline into `:trailblaze-models`. Pick
  based on dependency fit.
- **Relationship of `trailblaze.execute()` (PR A2) to toolset-file dispatch.**
  The PR A2 binding is still exposed to toolset files (same engine). Toolset
  `execute` functions can call `trailblaze.execute("anotherToolName", ...)` to
  compose across tools. Decide whether this cross-composition is "same toolset
  only" or "any registered tool in the session."
- **Capability-filter default.** What does `trailblaze/requiresHost`
  default to when not declared? Per the conventions devlog: default is
  `false`. Confirm during implementation that the default is right for
  the in-process path.
- **Dev-loop ergonomics.** If the user edits `x.ts` and forgets to rebuild, do
  we just error, or can Trailblaze detect the stale `.js` and surface a helpful
  message? Phase-1 answer: clear error, user re-runs their build.

## What NOT to do

- **Never** accept inline JS/TS in YAML. `script: { source: "..." }` stays
  load-bearing-deprecated. Don't re-open it; don't generalize it; don't expose
  an `inline:` field on toolset configs.
- **Never** generate `.ts` inside Trailblaze's Kotlin code. Authors write TS;
  Trailblaze reads `.js` build artifacts. One direction only.
- **Never** make up numbered decisions without cross-checking the existing
  devlog index. The existing devlog directory has the authoritative numbering;
  `docs/devlog/index.md` is the generated view.

## References

- `docs/devlog/2026-04-20-scripted-tools-execution-model.md` — Decision 038
  umbrella. Will have a brief "revised sequencing" amendment added inline.
- `docs/devlog/2026-04-20-scripted-tools-mcp-subprocess.md` — PR A4 (was A3)
  direction. Unchanged content; re-sequenced after new PR A3.
- `docs/devlog/2026-04-20-scripted-tools-on-device-bundle.md` — PR A5 (was A4)
  direction. Unchanged content; re-sequenced.
- `docs/devlog/2026-04-20-scripted-tools-mcp-conventions.md` — shared
  conventions across all toolset deployment modes. `_trailblazeContext`,
  `_meta["trailblaze/*"]`, `isError` / `_meta.trailblaze` result shape. Apply
  to the new PR A3 in-process path unchanged.
- `docs/devlog/2026-04-20-scripted-tools-a2-sync-execute.md` — PR A2 (merged).
  `trailblaze.execute()` primitive unchanged; authoring-surface deprecation
  noted here rather than amending the merged devlog.
- `docs/devlog/2026-04-20-scripted-tools-a3-subprocess-impl.md` — now an
  artifact on the obsolete branch. Will be marked superseded at the top.
- `docs/devlog/2026-02-03-custom-tool-architecture.md` — Decision 029, the
  canonical RPC/proto architecture the subprocess callback direction
  implements when PR A4 Phase 2 eventually lands.

---

## Option-2 Amendment (2026-04-20)

Supersedes the phase table and action plan above. Driving observation: the
author's `.ts` toolset file should double as a **standard MCP server** (runnable
under Claude Desktop, Cursor, or any MCP client) with zero authoring fork.
QuickJS can't run the official `@modelcontextprotocol/sdk` verbatim — the SDK
uses Node APIs (`process.stdin`, streams, `Buffer`). So the host-side runtime
choice pivots: **bun/node subprocess speaking stdio MCP**, using the real SDK.

This collapses the former PR A3 (in-process QuickJS toolsets) and PR A4
(subprocess MCP) into a single host-side PR A3. QuickJS retains a home only
in PR A5 (on-device bundle), where subprocess isn't possible and a bundler
produces a QuickJS-runnable artifact via an in-process MCP transport (see
`2026-04-20-scripted-tools-on-device-bundle.md`).

### Key decisions locked in

1. **Host-side runtime is a bun/node subprocess.** Trailblaze acts as an MCP
   client; the subprocess is the server. No QuickJS on the host side for
   toolset execution.
2. **Author's `.ts` file is a standard MCP server.** Uses the real
   `@modelcontextprotocol/sdk`, wires `StdioServerTransport`, runs under any
   MCP client in addition to Trailblaze. No Trailblaze-specific `tools[]` /
   `execute()` export shape.
3. **`_trailblazeContext` is the sole Trailblaze-specific extension point at
   runtime.** Injected by Trailblaze as a reserved key inside `arguments` per
   the MCP conventions devlog. Pure MCP clients don't inject it, so the field
   is `undefined` there.
4. **Tier 1 / Tier 2 is declared by method signature, not a required flag.**
   Tools that destructure `_trailblazeContext` off their args are Trailblaze-aware
   (Tier 2). Tools that don't are portable (Tier 1). The `@trailblaze/scripting`
   TypeScript types package gives authors editor-level help by typing
   `_trailblazeContext?: TrailblazeContext` as an optional field on the args
   type. No runtime flag is required.
5. **Optional `_meta: { "trailblaze/requiresContext": true }`** is
   available as pure metadata — useful for hiding Trailblaze-only tools
   from pure-MCP-client tool surfaces and for giving the LLM a hint.
   Runtime correctness does not depend on it.
6. **`trailblaze/requiresHost` semantics locked in.** Default `false`.
   Host (PR A3 subprocess): all tools registered regardless of the flag
   (Node can run anything). On-device (PR A5 QuickJS): tools with
   `_meta["trailblaze/requiresHost"]: true` are **filtered out at
   on-device registration time** per the on-device-bundle devlog. The
   flag is what authors declare when a tool uses APIs unavailable in
   the on-device QuickJS runtime
   (`fs`, `child_process`, raw host `fetch`, arbitrary npm deps that don't
   bundle for QuickJS, etc.). Note `fetch` is a Web API, not Node-specific;
   it's host-only in this context because QuickJS doesn't expose a network
   binding out of the box.
7. **On-device portability path is bundle-twice, same source.** Authors
   compile their `.ts` + MCP SDK + `zod` to a QuickJS-runnable artifact using
   their own bundler (`tsc` + `esbuild` for PR A5's first landing; a Gradle
   plugin — likely Zipline-based — is tracked as a PR A5 follow-up rather
   than shipping in PR A5's MVP per the on-device-bundle devlog). Trailblaze
   supplies an **in-process MCP transport** that hands messages to Kotlin
   instead of stdio. Server wiring that can't run on-device (stdio transport,
   host-only imports) tree-shakes out.
8. **On-device API enforcement is pragmatic, not statically guaranteed.**
   Convention + docs (allowlist: pure ECMAScript + `trailblaze.execute()` +
   `_trailblazeContext`); annotation-based filtering by
   `trailblaze/requiresHost`; runtime errors when a handler touches an
   unavailable API. No static analysis of handler bodies in MVP (may land
   later as an opt-in lint, not a gate).
9. **`script: { source }` stays load-bearing-deprecated.** Data-class
   field remains for binary compat with PR A; docs and examples
   stop mentioning it. No runtime warning emitted.
10. **Dev-loop ergonomics trivial now.** `bun run` / `node --loader tsx` runs
    `.ts` directly — no separate build artifact on host, no stale-`.js`
    problem. On-device (PR A5) produces a deterministic artifact at app build
    time — no stale there either.

### Revised phase table (supersedes the earlier table)

| Phase | Scope | Status |
|---|---|---|
| PR A (**done**) | QuickJS bridge primitive — inline `source:` is legacy. | Merged |
| PR A2 (**done**) | `trailblaze.execute()` callback. Becomes the Trailblaze extension point within Tier 2 tools. | Merged |
| **PR A3** (merged A3+A4) | **Subprocess MCP toolsets.** bun/node runtime, stdio MCP transport, real `@modelcontextprotocol/sdk`. Author's `.ts` is a standard MCP server. Absorbs the former PR A4 entirely. | **Next up** |
| PR A5 [unchanged] | **On-device bundle toolsets.** Same `.ts` source, bundled for QuickJS (BYO-bundle in the first landing; Gradle-plugin automation tracked as follow-up) + in-process MCP transport + `trailblaze/requiresHost` filter at registration time. | Deferred until PR A3 lands |

PR A4 is no longer a separate phase. The former-PR-A4 subprocess-MCP design
(captured in `2026-04-20-scripted-tools-a3-subprocess-impl.md`) is the primary
design target for the merged PR A3.

### Revised action plan for the next agent

1. **Branch off latest main** for PR A3 (suggested slug
   `scripted-tools-host-subprocess`; pick whatever follows your team's
   branch-naming convention).
2. **Write the scope devlog** (suggested filename
   `2026-04-20-scripted-tools-a3-host-subprocess.md`). Load-bearing contents:
   - Config shape: `subprocess_toolsets: [{ file: "./tools/x.ts" }]`
     at top level of `TrailblazeConfig` (matches the name already captured in
     `2026-04-20-scripted-tools-a3-subprocess-impl.md`; the scope devlog
     confirms and locks this name). Path resolution: relative to the
     `trailblaze.config.yaml` directory.
   - Runtime detection: prefer `bun` on PATH; fall back to `node`
     (with `tsx` / `ts-node` for TS loading); hard error with a helpful
     message if neither is available.
   - Subprocess lifecycle: per-session spawn (long-lived for the trail's
     duration); graceful shutdown on session end; capture stderr for
     diagnostics.
   - Kotlin-side MCP client: Trailblaze connects as an MCP client and speaks
     `tools/list` + `tools/call` over stdio. Pick an existing Kotlin MCP SDK
     if available, otherwise a minimal subset of the protocol.
   - `_trailblazeContext` injection: the reserved-key envelope on each
     `tools/call` built from `TrailblazeToolExecutionContext`, per the
     conventions devlog.
   - `trailblaze.execute()` reciprocal channel: the subprocess-impl devlog
     specifies HTTP proto-JSON per Decision 029. Adopt as-is or defer behind
     a flag — decide during scope.
   - Capability handling: `trailblaze/requiresHost` ignored at host
     (all tools register); key preserved for PR A5's filter.
   - Tests: fixture toolset (`.ts` committed) exercising register + dispatch
     + `_trailblazeContext` injection + tier-1 and tier-2 handlers +
     graceful-shutdown.
3. **Commit the scope devlog, push, open a draft PR.** Iterate implementation
   commits on the same branch.

### Open questions resolved

- ~~Module home for toolset loading.~~ → Host-side toolset client sits
  alongside the Kotlin MCP client stack; `:trailblaze-scripting` remains the
  home for QuickJS (now PR A5 only).
- ~~Cross-composition semantics of `trailblaze.execute()`.~~ → Any registered
  tool in the session. Reciprocal callback channel (subprocess → host) exposes
  the same semantics as PR A2's in-process binding.
- ~~`trailblaze/requiresHost` default.~~ → `false`. Filters at on-device
  bundle (PR A5) only; host registers all tools.
- ~~Dev-loop ergonomics for stale `.js`.~~ → Moot. `bun run` / node+tsx runs
  `.ts` directly on host. On-device is deterministic at app build time.

### New open questions for PR A3 scoping

- **Kotlin MCP client choice.** Use an existing Kotlin MCP SDK if one is
  available; otherwise a minimal subset of `tools/list` + `tools/call` over
  stdio.
- **Reciprocal `trailblaze.execute()` channel.** The subprocess-impl devlog
  spec'd HTTP proto-JSON per Decision 029. Adopt as-is in PR A3 MVP or defer
  to a phase 2? Decide during scope.
- **Runtime discovery UX.** `bun` preferred, `node`+`tsx` fallback; confirm
  the error path is clear for users with neither installed.

### What NOT to do (updated)

All the earlier "What NOT to do" bullets still apply (no inline JS/TS in YAML;
no Trailblaze-generated `.ts`; no made-up decision numbers). Adds:

- **Don't reintroduce QuickJS as a host runtime for toolsets.** Host side is
  subprocess-only now. QuickJS is PR A5's exclusive runtime.
- **Don't invent a Trailblaze-specific `tools[]`/`execute()` export shape.**
  Authors wire their tools via the real MCP SDK — `server.registerTool(...)`
  is the only supported authoring surface for scripted tools.
- **Don't require `_meta["trailblaze/requiresContext"]`.** The key is
  optional metadata, not a runtime discriminator.
