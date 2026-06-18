---
title: "Can the Kotlin Compiler Emit Our TypeScript Bindings Natively?"
type: decision
date: 2026-06-17
---

# Can the Kotlin Compiler Emit Our TypeScript Bindings Natively? — wasmJs `.d.ts` Spike

**Short answer: no, not on `wasmJs`.** The hand-rolled `SelectorTsCodegen`
stays. The Kotlin/Wasm compiler's TypeScript-declaration generator produces a
*JavaScript-interop FFI surface* (functions exported from a loaded `.wasm`
binary), which is a fundamentally different artifact from the *JSON
data-interchange schema* our TypeScript SDK consumers need — and it can't
express our model types anyway.

## Background

The ["Kotlin Canonical, TypeScript Derived"](2026-05-22-kotlin-canonical-typescript-derived.md)
decision established that every typed surface crossing the Kotlin/TypeScript
boundary is generated from the Kotlin model. For the selector grammar
(`TrailblazeNodeSelector` + the `DriverNodeMatch.*` sealed-interface branches +
`MatchDescriptor` + `TrailblazeNode.Bounds`) that generator is **hand-rolled**:
`SelectorTsCodegen` (in `build-logic`) parses the canonical Kotlin source files
and emits `sdks/typescript/src/generated/selectors.ts`, byte-diffed in CI by
`verifySelectorsTs`.

That devlog's open questions explicitly listed "generator-library choice"
(hand-rolled vs `kxs-ts-gen` vs JSON-Schema bridge) as undecided. The repo's
move to Kotlin 2.4.0 raised a *new* candidate worth a spike: **could the Kotlin
compiler itself emit the bindings natively**, letting us delete the bespoke
generator entirely?

The hypothesis was that Kotlin 2.4 had newly enabled TypeScript-declaration
(`.d.ts`) generation for the `wasmJs` target (Trailblaze uses `wasmJs`, not
`js()`). This devlog records what the spike actually found.

## Correcting the premise

Two facts checked against the installed 2.4.0 and the Kotlin docs before
writing any code:

1. **TypeScript-declaration generation for `wasmJs` is not new in 2.4.** It has
   existed (Experimental) since **Kotlin 2.0.0**: mark declarations with
   `@JsExport`, add `generateTypeScriptDefinitions()` to the `wasmJs {}` block,
   and the compiler emits a declaration file next to the compiled module. So
   this was available well before the 2.4 upgrade; the upgrade didn't unlock it.

2. **What 2.4 *did* add** in this area is **value-class export to
   JavaScript/TypeScript** — and that landed for the **Kotlin/JS** target, not
   `wasmJs`. It does not change the `wasmJs` export surface that matters here.

The feature being "older than assumed" is not itself a blocker. The blocker is
*what the `wasmJs` export surface can express*, which the prototype measured
directly.

## The mechanism, verified

The DSL (confirmed against the [Kotlin/Wasm interop
docs](https://kotlinlang.org/docs/wasm-js-interop.html) and the installed
2.4.0):

```kotlin
kotlin {
  wasmJs {
    browser()
    binaries.executable()            // required — the .d.ts is emitted for an executable binary
    generateTypeScriptDefinitions()  // turns on .d.ts emission
  }
}
```

With that enabled, `compileProductionExecutableKotlinWasmJs` emits
`build/compileSync/wasmJs/main/productionExecutable/kotlin/<module>.d.mts`
(note: `.d.mts`, an ESM declaration file) and even adds a
`...ValidateGeneratedByCompilerTypeScript` task that type-checks the output.

## The prototype

All three stages ran in `trailblaze-models` behind the existing
`-Ptrailblaze.wasm=true` flag (the `wasmJs` target is off by default, so none of
this touched normal JVM/Android builds or CI). The scaffolding was reverted
after capturing the evidence below; everything here is reproducible by
re-adding the two lines above plus the snippets shown.

### Stage 1 — annotate the model shapes with `@JsExport` → **rejected at compile time**

The first attempt annotated data classes mirroring the real selector shapes
(an all-primitive `Bounds`-like class, a nullable-field match class, a
self-referential selector class):

```kotlin
@JsExport
class BoundsExport(val left: Int, val top: Int, val right: Int, val bottom: Int)
```

The compiler rejected every one:

```
e: This annotation is not applicable to target 'class'. Applicable targets: function
```

**On `wasmJs`, `@JsExport` is `@Target(FUNCTION)` only.** Data classes, sealed
interfaces — none of the selector model — can even be *annotated* for export.
(This is the key divergence from the Kotlin/JS target, where `@JsExport` does
apply to classes and emits TypeScript classes.)

### Stage 2 — export top-level functions → **succeeds, and shows the emitted shape**

Functions are the only legal target, so the next stage exported three:

```kotlin
@JsExport fun addBounds(left: Int, top: Int, right: Int, bottom: Int): Int = ...
@JsExport fun normalizeRegex(pattern: String?): String? = ...
@JsExport fun describeMatch(hasWeb: Boolean, index: Int): String = ...
```

This compiled and produced a complete `.d.mts`:

```ts
type Nullable<T> = T | null | undefined
declare function KtSingleton<T>(): T & (abstract new() => any);
export declare function addBounds(left: number, top: number, right: number, bottom: number): number;
export declare function normalizeRegex(pattern: Nullable<string>): Nullable<string>;
export declare function describeMatch(hasWeb: boolean, index: number): string;
```

Primitives map cleanly (`Int`/`Long`→`number`/`bigint`, `Boolean`→`boolean`,
`String`→`string`); nullability becomes `Nullable<T> = T | null | undefined`.
The companion `.mjs` wires these to `WebAssembly.instantiate(...)` — i.e. the
declaration describes **functions you call after loading and instantiating a
`.wasm` module**.

### Stage 3 — can an exported function at least *carry* a model type? → **rejected**

The only conceivable way to surface the model would be factory functions
returning the types. That's rejected too — for a plain Kotlin class and for the
real `@Serializable` model type alike:

```
e: Type 'PlainBounds' cannot be used as return type of JS interop function.
   Only external, primitive, string, and function types are supported in Kotlin/Wasm JS interop.
e: Type 'TrailblazeNodeSelector' cannot be used as return type of JS interop function.
   Only external, primitive, string, and function types are supported in Kotlin/Wasm JS interop.
```

So there is **no path** — not annotations on the types, not factory functions —
to get the selector model types into a `wasmJs`-generated `.d.ts`. The export
surface is restricted to `external | primitive | string | function` types. A
web search confirmed there is no 2.4 experimental flag that lifts this for data
classes (the only nearby flag, `-Xenable-suspend-function-exporting` from 2.3,
is unrelated).

## Comparison: native `wasmJs` output vs hand-rolled `SelectorTsCodegen`

| Capability the SDK needs | Hand-rolled `SelectorTsCodegen` | Native `wasmJs` `generateTypeScriptDefinitions()` |
|---|---|---|
| Emit the selector model as TS **types** (`TrailblazeNodeSelector`, 6 `DriverNodeMatch*`, `MatchDescriptor`, `Bounds`) | ✅ 10 interfaces | ❌ classes can't be exported; types can't appear in any signature |
| Sealed-interface → discriminated-union-by-presence shape matching the YAML wire format | ✅ | ❌ |
| `List<T>` fields (`containsDescendants`) | ✅ `T[]` | ❌ collections unsupported in interop |
| Nullable "don't-care" fields as optional (`field?: T \| null`) | ✅ | ⚠️ only on primitive/string function params, as `Nullable<T>` |
| `@SerialName` wire-name remapping | ✅ (honored, with fail-loud on non-identifier keys) | ❌ no concept of it |
| `@Transient` exclusion (e.g. the `driverMatch` getter) | ✅ | ❌ |
| KDoc → TSDoc (the rich `## Structure` / `@see` docs ride along) | ✅ verbatim | ❌ no doc-comment emission |
| `selectors` factory namespace + **runtime** implementation | ✅ (emits runnable TS) | ❌ (it emits an FFI binding, not authoring sugar) |
| Output is a **data-interchange schema** authors write as plain JSON object literals | ✅ | ❌ output is a **JS↔Wasm FFI** over a loaded binary |
| Consumable by `sdks/typescript/` with no `.wasm` runtime load | ✅ | ❌ requires instantiating the Wasm module |

## The fundamental mismatch

Even setting aside every restriction above, native generation targets the wrong
*kind* of artifact:

- **`SelectorTsCodegen` produces a data-interchange schema.** A trailmap author
  writes a plain object literal —
  `{ androidAccessibility: { textRegex: "Submit" } }` — which is serialized to
  YAML/JSON and deserialized by kotlinx.serialization on the daemon. No Kotlin
  runtime is ever loaded in the author's TypeScript environment. The TS types
  exist purely to type-check the *shape of the JSON*.

- **`@JsExport` + `generateTypeScriptDefinitions()` produce a foreign-function
  interface.** The declaration describes symbols exported from a compiled
  `.wasm` module that the consumer loads and calls. It is the contract for
  *invoking Kotlin code from JavaScript*, not for *describing a JSON payload*.

Our SDK consumers never load a Wasm module — they emit data. So the native
generator answers a question we are not asking. This holds even on the more
capable **Kotlin/JS** target: while `js()` *can* export classes, `@JsExport` on
a `@Serializable` data class there emits a TS **class** (constructor + getters,
bound to a JS runtime), ignores `@SerialName`/`@Transient`, and still describes
an FFI object rather than the optional-field JSON-literal interface the wire
format needs. Adopting it would also mean adding a `js()` target the project
doesn't have. Neither target produces the artifact `selectors.ts` is.

## Recommendation

**Keep the hand-rolled `SelectorTsCodegen`. Do not adopt native compiler
generation — it can neither replace nor augment it.**

Rationale, in priority order:

1. **It cannot express the model.** `wasmJs` `@JsExport` is function-only and
   admits only `external | primitive | string | function` types. The selector
   model (data classes, a sealed interface, a `List` field) is categorically
   outside that surface — verified by direct compile errors.

2. **Wrong artifact kind.** Native generation emits a JS↔Wasm FFI binding, not a
   JSON data-interchange schema. Our consumers author plain object literals;
   they never instantiate a Wasm module.

3. **The hand-rolled generator already delivers everything we need** that the
   compiler does not: discriminated-union-by-presence shape, `List<T>` →
   `T[]`, `@SerialName` remapping, `@Transient` exclusion, KDoc → TSDoc, and the
   ergonomic `selectors` factory namespace with a real runtime implementation.

This also resolves the original devlog's "generator-library choice" open
question by elimination of one candidate: the compiler-native route is off the
table for this target. Hand-rolled remains the chosen path; `kxs-ts-gen` and the
JSON-Schema bridge remain the only alternatives worth revisiting if the
hand-rolled parser's maintenance cost ever grows.

## When to revisit

- Kotlin lifts the `wasmJs` `@JsExport` restriction to cover data classes /
  sealed hierarchies **and** the emitted shape becomes a plain interface
  describing data (not an FFI object). Both would have to be true. Track the
  Experimental status of `generateTypeScriptDefinitions()` — it is explicitly
  documented as "may be dropped or changed at any time."
- The model surface grows large enough that the hand-rolled source-text parser
  in `SelectorTsCodegen` becomes a maintenance burden — at which point
  `kxs-ts-gen` (reflection over kotlinx.serialization) or a JSON-Schema bridge,
  both of which *do* target the data-schema artifact, are the candidates, not
  the compiler.

## What changed

**Positive:**

- The "could the compiler do this for us?" question is now answered with
  empirical evidence, so the next engineer doesn't re-run the spike.
- The original cross-language-codegen decision is reaffirmed on firmer ground:
  hand-rolled isn't a stopgap pending a compiler feature — the compiler feature
  targets a different artifact and isn't coming for this use case soon.

**Negative / unchanged:**

- No code change. `SelectorTsCodegen` keeps its source-text-parsing approach and
  its documented narrow-parser caveats.
- The `wasmJs` target stays `browser()`-only (no `binaries.executable()` / no
  `generateTypeScriptDefinitions()`); the spike scaffolding was reverted.
