---
title: "Playwright tool shims should be code-generated from the Playwright Java SDK"
type: decision
date: 2026-05-26
---

# Playwright tool shims should be code-generated from the Playwright Java SDK

## Summary

Trailblaze's `web_*` tool surface (`web_evaluate`, `web_addInitScript`,
`web_setExtraHTTPHeaders`, `web_setGeolocation`, `web_setUserAgent`,
`web_setOfflineMode`, `web_cookie_*`, …) maps 1:1 onto Playwright Java's
`Page` / `BrowserContext` / `Locator` interfaces. Hand-authoring a Kotlin
`PlaywrightExecutableTool` shim per method (plus the matching `.tool.yaml`,
TypeScript binding in `built-in-tools.ts`, and `@TrailblazeToolClass`
metadata) is a maintenance dead-end — every Playwright version bump and
every new author migration pulls more hand-curated surface into the repo.

**Decision:** ship a single Gradle-time code generator that reflects on
the Playwright Java JAR against an allowlist, emits Kotlin shim source
files + TS bindings + `.tool.yaml` descriptors, and wires the generated
sources into `compileKotlin` and the SDK `.d.ts` bundle. After that
lands, adding a new `web_<Page-method>` becomes a one-line allowlist
addition, not a 5-file hand-edit.

## Why this came up

PR #3401 ("Playwright migration primitives") originally proposed three
hand-authored shims — `web_evaluate(fn, ...)` SDK overload + new Kotlin
tools `web_addInitScript` and `web_setExtraHeaders` — to unblock teams
migrating Playwright e2e suites. Cross-referencing the proposed surface
against `microsoft/playwright-mcp` (73 tools, all `browser_*`) and
`microsoft/playwright-cli` (~50 verbs) showed that **neither upstream
project exposes `addInitScript` or `setExtraHTTPHeaders` as discrete
tools**. Both push context-level setup into a config file or escape
through a `run_code_unsafe` / `run-code` hatch.

The mismatch isn't accidental — it's a different audience. Playwright
MCP / CLI surfaces are agent-driven (the LLM picks a tool at runtime).
Trailblaze's `web_*` is a *scripted-tool author's library*: a TypeScript
test-utility author calling `client.tools.web_X(...)` from inside a
`trailblaze.tool(...)` handler, same shape as someone writing a
Playwright test helper. That author legitimately needs every
`Page.<method>` Playwright exposes — `addInitScript`,
`setExtraHTTPHeaders`, `setGeolocation`, the whole catalog.

Enumerating that surface by hand doesn't scale. Generating it from
reflection does.

## The generator's shape

A new Gradle task `:trailblaze-playwright:generatePlaywrightToolShims`:

1. **Reads an allowlist** — `playwright-tool-shims.yaml` (or
   `@PlaywrightToolGen` annotations on a small Kotlin config). Each
   entry names a Playwright Java method (`page.evaluate`,
   `page.addInitScript`, `page.setExtraHTTPHeaders`,
   `context.cookies`, …) plus optional metadata overrides (custom
   `name`, custom `description`, custom `surfaceToLlm` /
   `isRecordable`).

2. **Reflects on Playwright Java** at build time. Pulls parameter
   names, parameter types, return type, and javadoc-derived
   description for each allowlisted method. Playwright Java's
   interfaces are reflectable enough — parameter names survive (the
   Playwright Java JAR ships with `-parameters` enabled), and most
   methods have a sibling `*Options` record-like type that's also
   reflectable.

3. **Emits Kotlin source** under
   `build/generated/playwright-tool-shims/src/main/kotlin/.../tools/`.
   One `data class Generated<Method>Tool : PlaywrightExecutableTool`
   per allowlisted method, with the standard
   `@Serializable @TrailblazeToolClass(name = "web_<method>",
   surfaceToLlm = false, isRecordable = false)` boilerplate and a body
   that's just `page.<method>(args.field1, args.field2, ...)`.

4. **Emits `.tool.yaml` descriptors** under
   `build/generated/playwright-tool-shims/resources/trails/config/tools/`,
   one per generated shim. These get bundled into the JAR's resources
   the same way the hand-authored ones do today.

5. **Emits TypeScript bindings.** Either appending generated entries
   to a `built-in-tools.generated.ts` (separate file from the curated
   `built-in-tools.ts` for ownership clarity) or — better — emitting
   straight into the per-trailmap codegen path so the entries flow
   through `WorkspaceClientDtsGenerator` like every other scripted
   tool does. Same shape, just generated upstream.

6. **Wires into compile + bundle.** The generated Kotlin sources are
   added to the `:trailblaze-playwright` source set; the generated
   `.tool.yaml` files join the JAR resources; the generated TS
   bindings get picked up by the existing
   `:trailblaze-models:bundleTrailblazeSdkDts` task.

Bumping the Playwright dep auto-regens the surface on the next build.

## Prerequisite: fix `Map<String, T>` lowering

`TrailblazeKoogToolExt.asToolType` (in `:trailblaze-models`) throws
`IllegalArgumentException` for `Map<String, ...>` parameter types —
which immediately blocks shims for `page.setExtraHTTPHeaders(Map)`,
`context.setExtraHTTPHeaders`, and several others. The fix:

- Add a `Map::class` branch to `asToolType` that resolves to either a
  new `ToolParameterType.Map(valueType)` variant **or** an
  `Object`-typed parameter that the TS emitter renders as
  `Record<string, T>`.
- The TS-side emitter (`PerPackClientDtsEmitter`, the SDK's
  `built-in-tools.ts` template) needs the corresponding render path.

This is a small, contained fix that unblocks the generator
end-to-end. PR #3401 ran into the same issue and worked around it by
hand-declaring `web_setExtraHeaders` in `built-in-tools.ts`; that
workaround was reverted alongside dropping the tool from the PR
itself.

## Three things that stay hand-authored

1. **Listener / callback methods.** `page.route(pattern, handler)`,
   `page.onConsoleMessage(listener)`,
   `page.waitForResponse(predicate)` — these pass callbacks that
   can't be serialized over the wire. They stay hand-written, or use
   a different model (e.g. queue-based with pattern strings instead
   of predicates). `web_route` will be hand-authored when we add it.

2. **The single existing hand-written shim — `web_evaluate`.** Already
   ships; the function-overload SDK ergonomic in #3401 is on top of
   the existing hand-written shim. Once the generator is wired,
   `web_evaluate` graduates to a generator entry like every other
   `Page.*` method and the hand-written shim is deleted.

3. **Surface metadata overrides.** Defaults are `surfaceToLlm = false,
   isRecordable = false` plus javadoc-as-description. Per-method
   overrides via the allowlist entry handle the cases where richer
   author copy is worth writing.

## Test plan for the generator

- **Golden-file tests** — input allowlist YAML, expected generated
  Kotlin / `.tool.yaml` / TS output. Lives in
  `:trailblaze-playwright` test source set.
- **Self-test via the generated shims themselves** —
  `:trailblaze-playwright:check` already type-checks generated Kotlin
  via standard Gradle wiring; the generated `.tool.yaml` descriptors
  get loaded by `TrailblazeSerializationInitializer` at test
  bootstrap; the TS bindings get type-checked by
  `pr_validate_ts_tooling.sh` against the example packs. So a
  malformed shim fails an existing CI step; no new test
  infrastructure required.
- **Playwright-version-bump regression** — when the Playwright Java
  dep bumps in `libs.versions.toml`, the generator regenerates;
  `dependencyGuardBaseline` + the byte-diff on
  `dist/trailblaze-sdk-bundle.js` and `dist/index.d.ts` catches any
  inadvertent surface change.

## File pointers (for the implementer)

- `TrailblazeKoogToolExt.asToolType` —
  `trailblaze-models/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/toolcalls/TrailblazeKoogToolExt.kt`,
  the `Unsupported type $classifier` throw at line ~76. Map support
  lands here.
- `PlaywrightExecutableTool` — interface the generated shims
  implement. Lives in
  `trailblaze-playwright/src/main/java/xyz/block/trailblaze/playwright/tools/`.
- Existing reference shim — `PlaywrightNativeEvaluateTool.kt` shows
  the boilerplate the generator should emit.
- Reference codegen patterns — `:trailblaze-models:generateSelectorsTs`
  (TS bindings from Kotlin sealed classes), the per-pack
  `WorkspaceClientDtsGenerator` in `:trailblaze-host`,
  `PerPackClientDtsEmitter`. Same pattern.
- `built-in-tools.ts` —
  `sdks/typescript/src/built-in-tools.ts`, the curated
  bindings file the generator's TS output joins (or replaces, for
  generator-owned entries).
- Toolset registration — `web_framework.yaml`
  (`trailblaze-playwright/src/main/resources/trails/config/toolsets/`)
  is where generated `web_*` tool names get added to the
  `always_enabled: true` toolset.

## Scope: what the codegen PR delivers

Minimum scope for the first codegen PR:

1. `Map<String, T>` lowering in `asToolType`.
2. The Gradle task scaffolding, allowlist YAML schema, kotlinpoet
   integration.
3. Generator output for ~5 Playwright methods covering the migration
   targets we hit first: `addInitScript`, `setExtraHTTPHeaders`,
   `setGeolocation`, `setOfflineMode`, `setViewportSize`.
4. End-to-end test that the generated shims compile, register via the
   normal toolset path, and dispatch through
   `client.tools.web_<method>(...)`.

After that lands, expanding coverage to the full `Page` /
`BrowserContext` surface is allowlist-additions only — no further
infrastructure work.

## Status

Plan committed. Tracking issue / chip to follow. PR #3401 reduced
scope to just the `web_evaluate(fn, ...)` SDK overload on the
existing hand-written shim; the codegen PR picks up here.
