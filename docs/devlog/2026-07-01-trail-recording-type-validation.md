---
title: "Type-checking trail recordings by transpiling them to TypeScript"
type: decision
date: 2026-07-01
---

# Type-checking trail recordings by transpiling them to TypeScript

## Summary

Trail YAML is guarded by two complementary gates. A **parse gate**
(`TrailYamlValidationTest`, kaml `strictMode`) rejects unknown, stale, or mis-nested keys at decode —
malformed *structure* fails the build instead of being silently dropped. A **type gate**
(`trailblaze check`'s `TrailTscValidator` in `:trailblaze-host`) checks every recorded tool call's
*args* against the target's generated typed surface — not by writing a YAML validator, but by
transpiling each recorded call into a throwaway TypeScript statement and compiling it with the
bundled `tsc`. This entry records why the type gate validates against the TypeScript surface rather
than building a parallel type model, how the two gates divide coverage, and where validation lands
today (a default-fail gate with explicit, shrinking per-target exemptions).

## What the type gate does

`TrailYamlValidationTest` proves every trail *parses*. It does not prove the recorded tool calls are
*type-correct for their target*: that the tool exists, every arg has the right type, and no required
arg is missing. That gap only surfaced at replay time on a device.

The type gate closes it. For each trail, every `step.recording.tools[*]` entry —
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
   wrong-typed fields. (This is exactly what the parse gate does — right as a *parse* gate, wrong as
   a *type* gate; see below.)
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
  the YAML parse + codegen is negligible. ~1.7s added to `check --all` over the full trail corpus.

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
  want to catch. Good for a parse gate (which is what the parse gate uses it for); insufficient for a
  type gate.
- **Kotlin reflection (4)** — Pros: no subprocess, no bun, pure JVM. Cons: we'd re-implement type
  checking (generics, nullability, nested `@Serializable` shapes) that tsc does for free, and it
  still can't lower the recursive `TrailblazeNodeSelector` grammar — the very reason those params are
  *stripped* from the Koog descriptor today. We'd inherit that limitation without inheriting tsc's
  strengths.

The deciding factor: the recorded args are a JSON object literal, which is a strict subset of TS
object-literal syntax, so codegen is a mechanical print — and the surface we'd validate against
already exists as `.d.ts`. Every other option meant *building and maintaining a second type model*.

## The validation surface

The type gate is only as accurate as the surface it validates against. Two properties make that
surface complete: it covers every target, and it models every tool a trail can actually record.

### Every target is covered, bundled or not

A workspace filesystem trailmap gets its surface from the standard `emit` path. A **classpath-bundled**
target — an app-bundled trailmap that lives inside a JAR with no writable `tools/` dir — has no such
surface, so the compile phase materializes a **validation-only** one into a gitignored scratch dir,
`<trails>/.trailblaze/trail-validation/<id>/tools/{tsconfig.json,trailblaze-client.d.ts}`. The check
phase discovers those dirs and appends them to the trailmap list, keyed by dir name = trailmap id
exactly like a workspace trailmap.

- `PerTrailmapClientDtsEmitter.emitClasspathValidationSurfaces(...)` generates the `.d.ts` for each
  bundled target. **Class-backed tools are fully typed** — they resolve through
  `TrailblazeToolSetCatalog` via classpath reflection, needing no on-disk sources, and they're the
  bulk of what recorded trails call. **Scripted (`.ts`) tools carry their build-time-baked schema**
  (the analyzer that would upgrade them further needs the trailmap's `.ts` on disk, which a JAR
  doesn't expose).
- The source is the build-time-baked `targets/<id>.yaml` (`AppTargetYamlLoader.discoverConfigs`), NOT
  the runtime-resolved trailmap pool. This is load-bearing: a bundled target whose scripted
  `target.tools:` need analyzer enrichment is *dropped* from the resolved pool at CLI runtime — the
  analyzer can't walk `.ts` inside a JAR, so sibling-resolution throws
  `ClasspathScriptedToolUnavailableException` and the loader drops the target (runtime dispatch is
  served by the baked YAML instead). Keying off the resolved pool therefore missed the single biggest
  target. The baked configs exist for every bundled target and carry the fully hoisted tool list.
- `PerTrailmapTsconfigEmitter.emitClasspathValidationTsconfigs(...)` writes the companion tsconfig,
  its `paths` mapping pointing at the workspace SDK bundle from the scratch `tools/` dir.
- The **side-location** approach was chosen over resolving the classpath resource's on-disk `file:`
  URL: it works whether the CLI runs from the uber JAR or from exploded classes (`BLAZE_JAR=0`), and
  never writes into the source tree.

### The surface models the recordable set, not just the author toolbox

The typed surface derives from the `isRecordable` source of truth, not hand-curation + `tool_sets:`.
`PerTrailmapClientDtsEmitter.resolveKotlinToolDescriptorsForTrailmap` unions three sources into every
generated surface (both filesystem `emit` and the classpath validation surfaces above),
first-write-wins:

1. the trailmap's own `tool_sets:` tools (also the authoring surface);
2. **every recordable framework tool** (`TrailblazeSerializationInitializer.buildAllTools()` filtered
   to `@TrailblazeToolClass(isRecordable = true)`), regardless of `tool_sets:`. This is the durable
   fix: the selector-migration pipeline records tools the LLM never picks (`assertVisibleBySelector`,
   `tapOn`, …) that live in no `tool_sets:`, and hand-adding each to `built-in-tools.ts` inevitably
   drifted — it had `tapOn` / `assertVisibleBySelector` but *missed* `assertNotVisibleBySelector`.
   Sourcing from the recordable registry (the same set replay decodes against) can't miss one.
3. **YAML-defined (`tools:` mode) recordable tools** (`buildYamlDefinedTools()`) — e.g. `eraseText` /
   `pressBack`, which have no Kotlin class.

Selector args are re-injected. `KClass.selectorParamsForTs()` (in `TrailblazeKoogToolExt.kt`, the
counterpart to the `excludedParameterTypes` the descriptor build strips) recovers each selector-typed
param typed against the generated `selectors.ts` grammar (`nodeSelector: TrailblazeNodeSelector`; the
deprecated Maestro-shaped `selector` as loose `unknown`), and `WorkspaceClientDtsGenerator` emits the
matching `import type { TrailblazeNodeSelector } from "@trailblaze/scripting"` when referenced. So a
recorded `nodeSelector:` type-checks instead of reading as an unexpected property.

`built-in-tools.ts` is now a small residual hand file. The recordable tools it used to curate are
generated per-surface; it keeps only (a) non-recordable author utilities (`findMatches`,
`waitUntilNotVisible`, `exec`, `android_adbShell`, …) and (b) recordable tools the generator can't
model — `mobile_maestro` (un-lowerable commands list) and `mobile_listInstalledApps[Detailed]` (rich
result types worth preserving), listed in `PerTrailmapClientDtsEmitter.HAND_CURATED_RECORDABLE`.
`BuiltInToolsBindingDriftTest` asserts the hand file and the generated surface stay **disjoint** (a
duplicate key would be a TS2717 collision) and that every hand entry maps to a `@TrailblazeToolClass`,
so the split can't silently rot.

## The strict parse gate

`TrailYamlValidationTest` parses every `.trail.yaml` with kaml `strictMode`, so an unknown key — a
typo, a stale/removed field, a mis-nested selector — **fails the build** instead of vanishing at
decode. This is the "kaml strict decode" option the type gate rejected *as a type gate*, used exactly
where it's right: as a *parse* gate over structure and closed shapes. A sibling test asserts the
strict parser actually rejects an unknown key (and the lenient default silently accepts it), so
strictness can't regress to lenient behind a clean corpus without a test going red.

**Coverage boundary.** Strictness only bites on closed shapes the parser has a serializer for. A tool
whose name isn't on the test classpath — a workspace-local trailmap tool not loaded by
`:trailblaze-common:jvmTest` — decodes to `OtherTrailblazeTool`, which stores its args as a raw open
map, so an unknown *arg* on such a tool isn't caught here. That's precisely the gap the type gate
closes: it type-checks those args against each trailmap's generated `trailblaze-client.d.ts`.

## Current state

- `TrailTscValidator` (`:trailblaze-host`), wired into `CheckCommand` as a phase after typecheck.
- **On by default, fails the build (`EXIT_TYPE_ERROR`)** when a *non-exempt* target has type findings,
  or a *non-exempt* target can't be validated at all (no generated typed surface — this catches a new
  uncovered target slipping in). Opt out of the whole phase with
  `TRAILBLAZE_DISABLE_TRAIL_RECORDING_VALIDATION=1`. Infrastructure problems inside the phase
  (bun/tsc missing, an unexpected exception) stay non-fatal — only genuine findings flip the code.
- **Exemptions are explicit and shrink visibly.** Two reviewable sources:
  - **Per-target `trail_validation.exempt: "<reason>"`** in a `trailmap.yaml`
    ([`TrailValidationConfig`], mirroring `TrailConfig.skip`'s required-reason pattern). Honored for
    any manifest the validator can reach — filesystem *and* classpath-bundled. This is the durable,
    co-located mechanism a target uses once its manifest is reachable but it isn't clean yet.
  - **A central, explicitly-transitional target-name allow-list** in `CheckCommand`
    (`TRANSITIONAL_EXEMPT_TARGETS`) for targets that have **no trailmap manifest at all** to carry the
    field above — placeholder / package-id targets used by smoke and eval trails, and the no-`target:`
    case. (A classpath-bundled target *does* have a manifest, so it declares its own
    `trail_validation.exempt` there instead of appearing in this list.)
- **Where the exemptions stand.** The dashboard app validates cleanly (0
  findings) — its findings were only selector-arg false positives, fixed by the re-injected selector
  args. What remains is one real framework bug (the arg-boundary string coercion below), which blocks
  the Square and primary-mobile exemptions, plus the manifest-less placeholder targets in
  `TRANSITIONAL_EXEMPT_TARGETS`. Everything else the corpus references is at **0 fatal findings**. The
  gate is safe — a full `check --all` sweep produces no false-fails.

## What we learned

- **Every type-gate "failure" so far was the typed surface being incomplete, never a broken trail.**
  Running it over a target's trail corpus surfaced findings that, on inspection, were all the surface
  not modeling a recordable tool — none were actual trail defects. Fixing the surface eliminated them.
  The type gate's accuracy is bounded entirely by surface *fidelity*, so its useful output is "where
  is the typed surface lying about what trails can record."
- **The typed surface (`tool_sets:`) ≠ the recordable surface.** The selector-migration pipeline
  rewrites NL assertions into selector-resolved dispatch tools (`assertVisibleBySelector`, `tapOn`)
  that the deterministic executor dispatches *by name* at replay — they're never in a trailmap's
  `tool_sets:` (the LLM/author toolbox), so a naive surface omits them. Deriving the surface from
  `isRecordable` (above) is what makes those tools covered instead of false positives.

## Known gaps and future work

- **Arg-serialization string coercion (the one real remaining framework bug).** A quoted
  numeric-string arg (`text: "5"`, `password: '12345678'`) — or a quoted boolean (`'true'`) — is
  re-coerced to a JSON number/bool at decode, so an `inputText({ text: string })` recording reads as
  number-not-string. This is what a `check --all` sweep surfaced under Square's exemption (findings
  across `launchAppSignedIn` / `enterEmployeePasscode` / `setFeatureFlag`), and it's the same
  root cause behind the primary-mobile exemption. Root cause: kaml's `YamlScalar` discards quote
  style, so `YamlJsonBridge.scalarToJsonPrimitive` can't tell `'12345678'` (string) from `12345678`
  (number), and its numeric round-trip guard only saves non-canonical values like `"0000"`. Closing it
  needs quote-style recovery (a kaml upgrade that exposes scalar style, or a lower-level parse for
  raw-tool arg nodes) — forcing `coerceNumbers = false` at the call site would fix strings but break
  genuinely-numeric scripted-tool args at replay, so it's not a safe shortcut. This is the sole
  blocker keeping both remaining real exemptions open.
- **Surface strict-parse errors to users.** The parse gate's `strict` knob is enabled only on the
  corpus test; every runtime parser stays lenient, so a user hand-editing their own workspace trail
  still gets a silent drop. Next is `trailblaze check` and `trailblaze run` hard-failing on an unknown
  key with kaml's precise `line/column` diagnostic.

[`TrailValidationConfig`]: ../../trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/config/project/TrailblazeTrailmapManifest.kt
