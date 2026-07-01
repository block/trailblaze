---
title: "Type-checking trail recordings by transpiling them to TypeScript"
type: decision
date: 2026-07-01
---

# Type-checking trail recordings by transpiling them to TypeScript

## Summary

`trailblaze check` now type-checks the recorded tool calls in every `.trail.yaml` against the
target's generated typed tool surface — not by writing a YAML validator, but by **transpiling each
recorded call into a throwaway TypeScript statement and compiling it with the bundled `tsc`**. This
entry records why we chose the transpile-to-TS route over validating the YAML directly, what each
alternative would cost, and where this lands today (report-only, `TrailTscValidator` in
`:trailblaze-host`).

## What it does

`TrailYamlValidationTest` already proves every trail *parses*. It does not prove the recorded tool
calls are *type-correct for their target*: that the tool exists, every arg has the right type, and no
required arg is missing. This gap only surfaced at replay time on a device.

The validator closes it. For each trail, every `step.recording.tools[*]` entry —
`tapOnElementWithText: { text: "Buy" }` — becomes one line of
`client.tools.tapOnElementWithText({ "text": "Buy" })` in a throwaway `<trail>.trail.gen.ts`, written
into the owning trailmap's `tools/` dir so it's covered by the existing `tsconfig.json` and sees the
generated `trailblaze-client.d.ts`. We run the bundled `tsc --noEmit`, and remap every diagnostic
back to `<trail>.yaml · step N` (codegen emits one statement per line and records a
`genLine → {trail, step, tool}` table as it writes, so the remap is exact — no YAML position API, no
source maps). The args JSON comes from the executor's own `TrailblazeToolYamlWrapper.toJsonArgs()`,
so we validate the *exact* shape replay dispatches, not a re-derivation.

## Key decision: transpile to TS rather than validate the YAML directly

The recorded tool calls are already type-modeled somewhere — the question was *which* type system to
validate against. We had four realistic options:

1. **Transpile to TS + `tsc`** (chosen). Reuse the per-trailmap `trailblaze-client.d.ts` — the exact
   surface `.ts` scripted tools already compile against — as the type oracle.
2. **JSON Schema over the YAML.** Emit a JSON Schema per target and validate each recorded call's
   args against it (e.g. with a JVM JSON-Schema validator, or the same ajv the SDK already uses).
3. **kaml / kotlinx-serialization strict decode.** Decode each recorded `toolName: {args}` into the
   concrete `@Serializable` `TrailblazeTool` subclass and let deserialization reject unknown/missing/
   wrong-typed fields.
4. **Native Kotlin reflection.** Walk each tool class's constructor params (the same reflection
   `buildToolDescriptorIgnoringSurface` already does) and check the recorded args against them.

### Why (1) won

- **Zero parallel type model.** The typed surface already exists and is regenerated on every
  `check`. tsc *is* the type authority the whole scripting story is built on; validating trails
  against it means one source of truth. Options 2 and 4 require emitting and maintaining a *second*
  schema/type description of every tool purely for trail validation.
- **Full fidelity for free.** tsc gives us nested-object checking, unions, optionality, excess-property
  detection, and "did you mean …?" suggestions with no work on our side. A hand-rolled JSON-Schema or
  reflection checker would re-implement a slice of that and drift.
- **The editor gets the same guarantee.** Because tsc is the authority at `check`/CI time, the Trail
  Runner editor's live JSON-Schema completion only has to be *good enough for autocomplete*, not
  authoritative — the batch gate catches whatever the editor schema misses.
- **It's cheap.** ~0.35s of `tsc` per trailmap-that-has-trails on top of the typecheck phase's pass;
  the YAML parse + codegen is negligible. ~1.7s added to `check --all` over the full 1088-trail corpus.

### What the YAML-native options would have bought (and cost)

- **JSON Schema (2)** — Pros: language-agnostic, reusable by the editor directly, no throwaway files.
  Cons: needs a per-target schema *generator* and a JVM validator dependency; JSON Schema can't
  express the self-referential selector grammar cleanly (the same recursion that defeats the Koog
  descriptor lowering — see below); error messages are worse than tsc's and would need their own
  remap. It duplicates the type model tsc already owns.
- **kaml strict decode (3)** — Pros: no new machinery — it's literally the parser we already use;
  "unknown key" / "missing required" fall out of strict mode. Cons: it validates *shape*, not the
  full target surface — decode succeeds for any registered tool regardless of whether that tool is
  available for the trail's target, so it can't answer "does this tool exist *for this app*." It also
  silently coerces scalars (a quoted `"5"` for an `Int`), which hides exactly the wrong-type bugs we
  want to catch. Good for a parse gate (which is what `TrailYamlValidationTest` is); insufficient for
  a type gate.
- **Kotlin reflection (4)** — Pros: no subprocess, no bun, pure JVM. Cons: we'd re-implement type
  checking (generics, nullability, nested `@Serializable` shapes) that tsc does for free, and it
  still can't lower the recursive `TrailblazeNodeSelector` grammar — the very reason those params are
  *stripped* from the Koog descriptor today. We'd inherit that limitation without inheriting tsc's
  strengths.

The deciding factor: the recorded args are a JSON object literal, which is a strict subset of TS
object-literal syntax, so codegen is a mechanical print — and the surface we'd validate against
already exists as `.d.ts`. Every other option meant *building and maintaining a second type model*.

## What we learned

- **Every "failure" so far was the typed surface being incomplete, never a broken trail.** Running it
  over one target's trail corpus surfaced 119 findings; on inspection all 119 were the surface not modeling a
  recordable tool, and 0 were actual trail defects. Fixing the surface (curating the missing tools in
  `built-in-tools.ts`) dropped it to **0 findings across 343 recorded tool calls**. The validator's
  accuracy is bounded entirely by surface *fidelity*, so the useful output today is "where is the
  typed surface lying about what trails can record."
- **The typed surface (`tool_sets:`) ≠ the recordable surface.** The selector-migration pipeline
  rewrites NL assertions into selector-resolved dispatch tools (`assertVisibleBySelector`, `tapOn`)
  that the deterministic executor dispatches *by name* at replay — they're never in a trailmap's
  `tool_sets:` (the LLM/author toolbox), so the generated `trailblaze-client.d.ts` omits them. Those
  legitimately-recorded tools have to be hand-curated in `built-in-tools.ts` (with hand-written
  selector typing, because the self-referential `TrailblazeNodeSelector` overflows the Koog descriptor
  lowering — same reason selector args are stripped there).
- **`built-in-tools.ts` is strictly the Kotlin-class-backed mirror.** `BuiltInToolsBindingDriftTest`
  enforces that every entry maps to a `@TrailblazeToolClass`. A YAML-defined recordable tool
  (`eraseText`) can't be curated there — it fails the drift guard — so it still reads as a finding
  until the emitter learns to surface YAML-defined tools.

## Current state

- `TrailTscValidator` (`:trailblaze-host`), wired into `CheckCommand` as a phase after typecheck.
- **On by default, strictly report-only** — it prints a YAML-keyed findings report and never changes
  the exit code. Opt out with `TRAILBLAZE_DISABLE_TRAIL_RECORDING_VALIDATION=1`.
- Covers only trails whose `target:` resolves to a **filesystem** trailmap with a generated surface
  (the workspace's filesystem trailmaps here). Classpath-bundled targets — the bulk of the corpus —
  have no writable `tools/` surface and are reported as skipped-no-surface.

## Open questions / future work

- **Cover classpath-bundled targets** — the majority of real trails. Needs the
  emitter to produce a surface for bundled trailmaps (to a side location, or by treating the source
  resources dir as filesystem during a source build).
- **Derive the validation surface from `isRecordable`** rather than the `tool_sets:`/scripted-author
  set, so a new recordable tool the migration pipeline emits is auto-covered instead of silently
  becoming a false positive. This is the durable fix for the curation-gap class.
- **Model selector args on the raw tools** (or have the emitter skip a tool when filtering drops a
  *required* arg), so selector-backed recordings validate their selector shape instead of reading as
  an unexpected-property error.
- Only once those close should this flip from **report-only to a hard-fail gate**.
