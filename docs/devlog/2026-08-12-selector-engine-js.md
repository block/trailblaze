---
title: "The Selector Engine, Compiled to JavaScript"
type: decision
date: 2026-08-12
---

# The Selector Engine, Compiled to JavaScript

## Summary

The interactive HTML report's UI Inspector needs to show ranked selector suggestions — with
match counts, tap-point resolution, and hit-test verification — for any node of a captured
hierarchy. The hard requirement: those suggestions must come from **the same Kotlin logic the
daemon records with**, not a TypeScript re-implementation. The new
`:trailblaze-selector-engine-js` module compiles `TrailblazeNodeSelectorGenerator` +
`TrailblazeNodeSelectorResolver` (and their closure) to browser JavaScript with Kotlin/JS,
bundles them with `bun` into a single ~312 KB (~83 KB gzipped) IIFE, and locks behavior to the
JVM path with byte-level cross-platform parity fixtures. 319-case golden corpus + the 45-case
matcher-parity fixture: zero divergence.

## Why compile Kotlin instead of porting

The repo already has one hand-ported algorithm: the selector *matcher* in the TypeScript SDK,
locked to the Kotlin resolver by `matcher-parity-fixtures.json`. Every semantics change there
costs a synchronized edit in two languages plus a fixture update. The selector *generator* is an
order of magnitude bigger (strategy cascades for six driver dialects, a minimizer, quality
ranking, hit-testing) and changes more often — a port would drift, and a stale suggestion in the
inspector is worse than none, because authors paste it into trails. Compiling the one
implementation is the only way "what the inspector suggests" and "what the recorder writes" stay
provably identical.

## Module shape: source-include, not a js target on `:trailblaze-models`

`:trailblaze-selector-engine-js` declares only a `js` target and points its commonMain at
`:trailblaze-models`' commonMain source directory, filtered to an explicit include-list (the
selector-engine closure: generator family, resolver, minimizer, quality, `TrailblazeNode` model,
analyzer, escape utils — pure Kotlin + kotlinx-serialization). Same source of truth on disk,
second compilation to JS.

The alternative — adding `js()` to `:trailblaze-models` itself — **does resolve** (Koog, kaml,
Ktor, coroutines all publish js variants; the probe got all the way to compilation, blocked only
on a handful of missing jsMain actuals). It was rejected on cost, not feasibility: it compiles
the entire LLM/tool/yaml model surface to JS on every contributor build (~75 s vs ~3 s for the
thin module) and adds a target to a published Maven artifact. The include-list is the cheaper
liability — a new file in the closure fails this module's compile loudly, and the fix is a
one-line addition.

Bundling is `bun build --minify` over the Kotlin compiler's DCE'd production ESM output
(`bundleSelectorEngine`). Deliberately no `browser()`/`nodejs()` environment and no webpack: the
Kotlin/JS webpack pipeline pulls in the npm/Node toolchain, and either environment wires
`kotlinNpmInstall`/`kotlinStorePackageLock` into `check` for test tasks with no sources. The
production-compile task graph contains no npm/node/webpack tasks at all, so the whole chain
needs only `bun`.

## The real platform gap: ECMAScript regexes

Kotlin/JS delegates `kotlin.text.Regex` to the native `RegExp` (Kotlin/Wasm implements its own
engine, which is why the Wasm inspector never hit this). Three consequences for selectors:

1. `\Q...\E` quote sections — what `escapeForSelector` writes into recorded selectors — don't
   exist in ECMAScript. In JS, `\Q` is an identity escape for a literal `Q`, so such patterns
   silently never match.
2. `RegexOption.DOT_MATCHES_ALL` doesn't exist on the JS target, though `RegExp` itself supports
   dotAll via the `s` flag.
3. Leading inline flags (`(?i)`) are a syntax error.

Fixed with one chokepoint: the resolver's compile-and-full-match now goes through
`internal expect fun selectorPatternRegexMatches(pattern, text, maestroDialect)`. The
JVM/Android and Wasm actuals are the previous logic verbatim; the JS actual (in this module's
jsMain) is a 1:1 Kotlin port of the TS matcher's proven translation (`resolver.ts`):
quote-section rewriting, leading-inline-flag stripping, a lookaround-based full-match wrap that
stays absolute under the `m` flag, and the `toRegexSafe` degrade.

Generation had the mirror-image problem: `Regex.escape` emits `\Q...\E` on the JVM but escapes
per-character on JS, so generated selector *text* differed by platform. `quoteAsRegexLiteral`
in `SelectorEscape.kt` now emits the `\Q...\E` form (with `Pattern.quote`'s embedded-`\E`
splitting) from common code, and `stableTextAnchorRegex` + `SelectorTemplating` route through
it. JVM output is byte-identical to before; JS output is now byte-identical to the JVM.

## Parity locks (both wired into `check`)

- **Matcher-parity fixture, third consumer.** `engine-parity.test.ts` (run under `bun test` by
  `verifySelectorEngineParity`) drives all 45 `matcher-parity-fixtures.json` cases through the
  compiled resolver via real selector resolution — the same contract `MatcherParityFixturesTest`
  (Kotlin/JVM) and `matcher-parity.test.ts` (TS matcher) consume.
- **Golden corpus.** `SelectorEngineParityGoldenTest` (`:trailblaze-models` jvmTest) computes
  full selector analyses + tap resolutions for every node of two committed hierarchies (176
  analyses + 143 tap resolutions) with the real daemon classes and asserts against the committed
  `parity/expected-analysis.txt`; the bun suite recomputes the corpus with the compiled bundle
  and byte-compares against the same file. Regen (intentional engine changes):
  `./gradlew :trailblaze-models:jvmTest --tests "*SelectorEngineParityGoldenTest*"
  -Dtrailblaze.updateSelectorEngineGolden=true`.

## The typed boundary is the generated bindings

`@JsExport` can't cross data classes, so the raw exports are JSON-string functions
(`computeSelectorAnalysis`, `resolveTapTarget`, `resolveSelector`). The typed surface on the TS
side comes from the existing Kotlin-canonical codegen: the analysis DTOs live in
`trailblaze-models/.../api/TrailblazeSelectorAnalysis.kt`, which is now a fourth source-of-truth
input to `generateSelectorsTs`, emitting `TrailblazeSelectorOption` / `TrailblazeSelectorAnalysis`
/ `TrailblazeSelectorTapResolution` / `TrailblazeSelectorResolution` into the committed
`selectors.ts` (byte-diffed by `verifySelectorsTs`). Consumers import the typed wrapper
(`src/typescript/selector-engine.ts` → `loadSelectorEngine()`), never raw JSON strings. The
DTOs' serialization contract — `encodeDefaults = true`, `explicitNulls = false` — is what makes
the generated required/optional field split truthful on the wire.

All the glue (analysis assembly, hit-test verification, the JSON boundary itself) lives in
`TrailblazeSelectorAnalyzer` in `:trailblaze-models` commonMain — the jsMain shim is pure
forwarding, so the JVM golden test and the JS bundle run literally the same boundary code.

## Numbers

| Metric | Value |
|---|---|
| bun IIFE bundle (minified, DCE'd) | 312,439 B raw / ~83 KB gzip |
| Module compile | ~3 s incremental, ~6 s clean (warm toolchain) |
| Parity | 95/95 bun tests: 90 fixture cases + byte-identical 352-line golden corpus |
| Runtime | ~66 ms per analysis call under bun, including full tree re-parse per call |

Reports run ~250 KB–2 MB today, and heavy payloads already ride a gzip+base64 side-channel that
inflates lazily — the engine fits that pattern (~110 KB as base64-of-gzip, evaluated on first
inspector open).

## Report integration (follow-up, deliberately not in this change)

The report packaging embeds the bundle in the existing side-channel; the inspector calls
`loadSelectorEngine()` on first open and passes hierarchies through untouched (`TrailblazeNode`
trees carry `nodeId`s the engine keys on). Fallbacks are structural: legacy
`ViewHierarchyTreeNode` hierarchies get no suggestions (the engine's model doesn't apply), and a
report built without the payload gets `null` from `loadSelectorEngine()` and hides the UI.

## Open questions

- Per-call tree re-parse is the dominant runtime cost; a parse-once session handle
  (`createTreeSession(treeJson) → handle`) is the obvious optimization if inspector interaction
  latency ever matters.
- The engine inherits the TS matcher's known translation gaps (non-leading inline flags,
  possessive quantifiers, Java-only `\p{...}` property names) — all degrade to the
  literal-equality fallback, and the shared fixture is where a real-world case would get
  pinned. Standard `\p{L}`-style property escapes and stray-`\E` rejection are translated
  (fixture-locked in all three implementations).
