---
title: "Why Trailblaze beats code generators"
type: decision
date: 2026-05-11
---

# Why Trailblaze beats code generators

## Summary

Code generators — Playwright's `codegen`, Maestro Studio's export, similar tooling across vendors — are **single-layer products**: you author at the framework's gesture level, the tool records the gestures, the tool emits framework code. The recording *is* the program. Trailblaze is **two layers**: an **execution layer** that stays as barebones as possible over the same native frameworks (Playwright, Espresso, XCUITest, Maestro, Compose UI), and an **authoring layer** that exposes high-level LLM-callable composition above it (custom domain tools, trailheads, scripted tools, waypoint navigation, agent self-heal). The two-layer architecture strictly contains the single-layer one: we can emit framework code from the resolved-execution trace whenever we want — same artifact a codegen product produces — *and* we can do everything you can't do at the framework's gesture level (LLM composition, domain vocabulary, resilience to UI churn, cross-team tool reuse, self-healing replay). This devlog walks through why the architecture wins, using the working Playwright TypeScript emitter we built as the proof.

## What code generators do, and what they leave on the table

Single-layer codegen products sit at the underlying test framework. The value prop is "click around, get framework code." Maestro Studio emits Maestro YAML. Playwright `codegen` emits a `.spec.ts`. Vendor tooling across the space all follows the same shape: gesture-level recording, framework-level emit.

What single-layer codegen does well:

- Lowers the barrier to writing the first test (just click around).
- Produces native test code anyone fluent in the framework can read.
- Stays close to the framework's mental model.

What it can't do, by construction:

- **High-level LLM composition.** The "program" is gestures; there's no abstraction layer for an agent to reason about. Self-healing, branching on observed state, retry-with-different-strategy — none of this fits at the gesture layer.
- **Domain vocabulary.** No `createOrder(items)`; only `tap on X`, `type Y into Z`. Every test reads in framework-speak, not in product-speak.
- **Resilience to UI churn.** Each gesture is a literal coordinate or selector against a specific DOM. A button moves; every test that used it breaks. There's no abstraction to fix once.
- **Cross-team tool reuse.** No library to share. Each test is freestanding gestures. Teams reinvent login flows, navigation, common assertions in every spec.
- **Recording at the level of intent.** A trailhead (`dashboard_launchSignedIn`) reads as one named operation in our recordings and as fifteen gestures in a codegen recording. The first is debuggable and refactorable; the second is a wall of taps.

These aren't bugs in codegen products. They're the structural consequence of being one layer. You can't build a `createOrder(items)` *inside* a framework's gesture vocabulary — that operation lives above the framework, and a single-layer architecture has no "above."

## The two-layer thesis

- **Execution layer: thin shim, by discipline.** Every Trailblaze action eventually resolves to a native-driver call — `page.click(...)` on Playwright, `onView(...).perform(...)` on Espresso, an Orchestra command on Maestro, an AX call on iOS. Staying close to those primitives is what gives us the resolved-action trace, native-framework code export, friendly customer migration off the framework, and the honest pitch "we are a shim, not a vendor-replacement." How thin the shim can actually be is asymmetric across drivers — see the next subsection for the honest version — but the discipline applies everywhere: the test before every divergence is "does this add authoring-layer value, or are we reinventing what the framework already does?"
- **Authoring layer: LLM-callable composition, by design.** Custom domain tools (`createOrder(items)`), trailheads (`dashboard_launchSignedIn`), scripted tools, waypoint navigation, agent self-heal — none of these exist in the underlying frameworks; they exist because the LLM needs a higher-level, less-fragile vocabulary than gestures, and because teams want to express tests in their domain language rather than in the UI framework's. This is the framework's actual value-add over a raw Playwright or Espresso. Divergence here is the *point*, not a cost.

The combination wins because the layers complement. The execution-layer discipline gives us portability and honesty; the authoring-layer design gives us LLM-readiness and resilience to UI churn. A single-layer product can do one or the other. Only the combination does both — and the rest of this devlog walks through code generation as the case study where that becomes obvious.

### How thin the shim can be, honestly, per driver

The execution-layer discipline is the same everywhere; what differs is how much we've had to add to make the discipline livable on each driver. The honest ranking:

- **Web (Playwright): genuinely thin.** Playwright is feature-complete for what we need — locator engine, auto-wait, assertions, tracing, codegen, the lot. We pass through to its primitives one-for-one. This is the only driver where the shim is so thin that the recorded trail and the resolved-action trace coincide one-to-one (which is why YAML-based code generation works today on web and only on web).
- **Android (Maestro): thicker, with a clean passthrough path.** Our shim adds things on top of raw Maestro — selector enrichment, retry / timing policies, cross-driver tool integration — so it isn't a one-for-one passthrough like Playwright. The convenient property is that Maestro's own input format is YAML, so our "maestro" tool can accept a Maestro YAML body and execute it directly. That gives users a direct way to drop into native Maestro from any trail; it doesn't reduce the shim's thickness, just makes the escape hatch cheap to expose. The same property makes Maestro-trace export nearly trivial — the resolved actions are already Maestro commands.
- **Android (Accessibility, Compose) and iOS (AXe, XCUITest, Maestro): thickest.** This is where most of our genuine IP lives. The native frameworks have gaps we have to fill — cross-driver view-hierarchy capture, simulator-vs-device performance differences, selector-resolution semantics that diverge from native, etc. The shim here is not thin; the discipline is to keep the divergences additive (we never replace a native primitive with a worse Trailblaze one) and to expose native primitives as passthrough tools wherever possible so users can always escape down to the framework.

The honest pitch is therefore: "Playwright is already perfect, so on web we're genuinely a shim. On mobile, the underlying frameworks have gaps and we've filled them with the minimum unique combination of solutions we could get away with — view-hierarchy stack, perf optimizations, cross-driver normalization — while exposing the native primitives as escape-hatch tools so the discipline still applies to user-facing code."

### Passthrough tools as the escape hatch

The "maestro" tool is the canonical example of a pattern worth generalizing: **wrap each driver's native input format as a Trailblaze tool**, so a user who wants a native primitive can always reach it from a trail without leaving the framework. The tool body is a passthrough — accept the native vocabulary, hand it to the driver, return the result.

This is what keeps the thicker-shim drivers from becoming a walled garden. If our Accessibility driver doesn't expose some Android-specific feature, the user shouldn't be told "wait for us to add a tool." They should be able to drop into a `maestro` tool (or a future `accessibility_raw` tool, `xcuitest_raw` tool, `playwright_call` tool) and use the native API directly. The framework's job is to make the common path nicer, not to lock out the long tail.

The pattern is also what makes the resolved-action trace stay coherent on the thicker drivers: even a wholly-custom scripted tool resolves to a sequence of *native* calls (because the only way to actually touch the device is through the native API), and the trace captures those, not the scripted-tool body that produced them.

## We built a code generator to test the question

To check the claim above rather than just argue it, we built a working Playwright TypeScript emitter from a recorded Trailblaze web trail. The artifact runs end-to-end (compiles with `tsc --strict`, recognized by `npx playwright test --list`), but the exercise turned out to be more useful than the output: it made the two-layer structure load-bearing and showed why "fill the codegen gap" is the wrong reading of what Trailblaze is. The sections below are what we learned by doing it.

## Why the Playwright export is almost trivially available

The recent web-side work that adopted the playwright-mcp request-tracking settle and added element-attached auto-wait did two things that quietly made code generation a near-free byproduct:

1. **It removed the recorder's blind `web_wait` filler.** Recordings that previously needed hand-authored seconds-based pauses now run reliably with zero waits — the framework's settle path handles it.
2. **It made every recorded action carry rich `nodeSelector` data.** `ariaRole` + `ariaNameRegex` for ARIA-resolvable elements, `cssSelector` as a fallback, `dataTestId` where present, plus `nthIndex` and `headingLevel`. These map one-to-one to Playwright's `getByRole` / `locator` / `getByTestId` primitives.

The emit becomes a `when (tool)` dispatch with one branch per `web_*` tool type:

- `web_navigate { url }` → `await page.goto(url)`
- `web_click` / `web_type` / `web_hover` / `web_select_option` → resolved locator + the corresponding Playwright action
- `web_verify_*` → `await expect(...).toBeVisible() / toContainText / toHaveValue / toHaveAttribute`
- `web_wait` → a follow-up marker comment, **not** `page.waitForTimeout`, because the framework now waits semantically

There is no clever code in the emitter because there is no semantic gap between the recorded trail and what a human would write reading the Playwright docs. The trail YAML *is* the program; the emitter just changes its dialect.

The deeper reading: we can do this on web because we've already decided to be a thin shim. Every additional bit of value we'd add to the web driver would either (a) compose Playwright primitives — in which case the recording still decomposes cleanly and the export still works, or (b) reach outside Playwright — at which point it stops being a thin shim and the export starts losing fidelity. Today (a) is the only case in the recorded YAML, so the export is faithful.

## Why mobile code generation is a different problem entirely

On Android and iOS, the recorded trail is at a fundamentally higher semantic level than the underlying frameworks expose. A recording can contain:

- **Trailheads** — bootstrap sequences like "launch the app signed in," which expand to dozens of taps under the hood and may include OAuth flows, deep-links, account provisioning, and locale setup.
- **Scripted tools** — TS/JS bodies that compose multiple primitive actions, call internal services, and assert on state the underlying framework doesn't model.
- **Waypoint navigation** — `goTo(waypoint_id)` resolved at runtime through a shortcut graph that knows about the app's information architecture in a way Espresso and XCUITest do not.
- **Custom domain tools** — `createOrder(items)`, `assertReceiptShows(line)`, things the LLM composes against a vocabulary the team has built.

None of these have a one-to-one analogue in Espresso, XCUITest, Maestro YAML, or any other native-framework export target. A "code generator" for mobile would have to either inline-expand every custom tool to raw taps at record time — losing the abstraction the framework is built on — or emit gestures-with-coordinates as the lowest common denominator — which is the worst version of every test framework.

The recorded trail is more expressive than its execution. That's the whole point of the framework. Export-from-the-recorded-trail-to-a-native-framework collapses that expressiveness — *but the execution itself is a separate, lower-level artifact we also have access to, and that one renders to native-framework code cleanly.* See the "plot twist" section below.

## Custom tools are the superpower, not the tax

The recurring temptation is to read "we can't emit native-framework code from our recordings" as a gap. It is not a gap; it is the cost of a deliberate bet. The bet is:

- Let teams **build a tool library** the LLM composes against, expressed in the team's domain vocabulary rather than the underlying UI framework's.
- Make a recorded trail **the agent's decisions**, not the framework's gestures. (This is also the direction the waypoint + shortcut work is heading — record at the `(shortcut, params)` level, not at the tap level.)
- Treat the underlying framework (Espresso, Maestro, Playwright, Compose UI Testing) as an interchangeable executor, not as the surface the test author writes against.

Vendor codegen *from the authored source* is the opposite bet. It says "the framework's gestures are the program; recording is a typing aid." Both bets are coherent; they produce different products. Trailblaze made the first bet for the source artifact.

The Playwright export we built is feasible *because*, on the web driver specifically, the second bet (thin-shim-over-Playwright) is *also* true at the authored-source level today — `web_*` tools are Playwright primitives in a thin wrapper, so the recording happens to also be a Playwright program. That coexistence at the source level is fine on web and doesn't generalize to mobile. **What does generalize is rendering from the execution trace** — captured below the abstraction layer, after the abstractions have resolved.

## Plot twist: emit from the resolved-action trace, not the authored trail

Every Trailblaze tool eventually bottoms out into a call to a native driver. Web tools resolve to `page.click(...)`, `page.fill(...)`, `page.locator(...).getByRole(...)` against the Playwright `Page`. Android tools resolve to either Maestro Orchestra commands or Accessibility-RPC requests against the on-device runtime. iOS tools resolve to AXe calls or XCUITest gestures. The driver layer is the chokepoint: every action, regardless of how high-level the authoring abstraction was, passes through it as a concrete sequence of (method, args) calls.

That gives us a second artifact, one execution boundary below the trail YAML:

- **Authored trail** (YAML, source language): `dashboard_launchSignedIn` (one custom tool call).
- **Resolved-action trace** (execution log, target language): `page.goto('…/login')`, `page.getByRole('textbox', {name:'Email'}).fill('…')`, `page.getByRole('button', {name:'Continue'}).click()`, …, `await expect(page.getByRole('tab', {name:'Business hub'})).toBeVisible()` — twelve to fifteen concrete Playwright calls that one trailhead expanded into at runtime.

The trace is **what actually ran on the underlying framework**. It carries no Trailblaze concepts — no `nodeSelector`, no `trailhead`, no `waypoint`, no `web_*` tool names — only the framework's own vocabulary. That means:

- It renders to native-framework code mechanically. A Playwright trace → a `.spec.ts`. An Espresso trace → a Kotlin Espresso test. An XCUITest trace → a Swift XCUITest case. A Maestro trace → a Maestro YAML flow. Same idea every time: capture (driver-method, args) tuples, format them in the target language's idiom.
- It is **complete by construction**. A trail using thirty custom tools and four trailheads renders just as cleanly as a trail using only primitive `web_*` calls, because the export consumes the resolved actions, not the authoring layer. The custom-tool surface stays first-class for authoring; it's simply not present in the target-language rendering, which is fine because the target language can't represent it anyway.
- It is **lossy of intent on purpose**. The exported code does not know that fifteen of its lines came from one `dashboard_launchSignedIn` call. That's the point: the customer who wants to run a Playwright test doesn't want to also import our trailhead library. They want fifteen idiomatic Playwright lines.

This is the same shape as a compiler: the authored language (Trailblaze YAML) compiles to a target language (native-framework code) via execution; the source has expressive features the target doesn't, and the target is what the next consumer wants. We were not previously thinking of "running a trail" as "compiling it"; with the resolved-action-trace framing, it is.

### What it would take to ship trace-based export

The Playwright TS emitter we already built consumes the trail YAML, not the trace. It works today because on web the YAML and the trace happen to line up — there are no custom tools that resolve to multiple `web_*` calls (trailheads are inlined at record time, and scripted-tool composition all bottoms out into the same `web_*` set). On mobile that alignment doesn't hold, and the YAML-based emitter would not work; a trace-based emitter would.

Sketch of the implementation surface, not a design doc:

- Each driver wrapper (`PlaywrightExecutableTool`, the various Android driver clients, the iOS AXe/XCUITest path) is the natural capture point. Each one already resolves a high-level tool to a concrete driver call; we add a tap that records `(driver-method, serialized-args)` per call.
- Per driver, an emitter formats the captured tuples as native-framework code. The Playwright one is straightforward (we have most of it already, just re-pointed at the trace instead of the YAML). Espresso and XCUITest are nontrivial because their matcher vocabularies are richer, but they're bounded — they reduce to "what selector and what action."
- The captured trace lives alongside `recording.trail.yaml` in the session log dir, named something like `recording.resolved-actions.json`. A CLI flag (`--export-playwright`, `--export-espresso`, `--export-xcuitest`, etc.) writes the corresponding rendered file.
- Importantly: this is a **separate output product** from the trail YAML, and replay continues to use the trail YAML. The trace is for export-to-vendor only.

### Why this resolves the original tension

The original tension was that adding native-framework export *from the authored YAML* would push every custom tool to express itself in vendor vocabulary, which would erode the LLM-composable custom-tool surface that the framework is built on. Trace-based export sidesteps the whole argument: custom tools stay opaque blobs at the source layer, and the export consumes what they resolved to, not what they meant. Both bets ship simultaneously — LLM-callable custom tools as the authoring surface; native-framework code as the rendered output of an execution.

That is also the reason it's worth writing down: it changes the answer to "do you have code generation like \<vendor\>?" from "on web yes, on mobile no, intentionally" to "yes everywhere, with the understanding that the export is the runtime trace, not the authored trail — and you can have either or both."

## What we ship

Three pieces follow from this, in order of how decided each is:

1. **Ship the Playwright export as a scoped migration aid (now).** Land `--export-playwright` on the web driver as a `recording.spec.ts` artifact alongside the existing `recording.trail.yaml`. Document it as "for users migrating Trailblaze-recorded web trails into hand-written Playwright tests" — a one-way door, not a round-trip. This is the YAML-based emit we already built. It works today because on web the YAML and the resolved-action trace coincide; the trace-based generalization (below) doesn't have to land first.
2. **Do not add a `toPlaywrightTs()`-style hook on `TrailblazeTool`.** That hook would push every custom tool to justify itself in someone else's vocabulary at the *source* layer, which is the inversion we want to avoid. The trace-based path (next) makes the hook redundant — custom tools don't need to know how to render themselves because we render what they did, not what they were.
3. **Plan toward trace-based export as the general answer (next).** Add a resolved-action capture tap at each driver wrapper; persist the trace alongside `recording.trail.yaml`; ship per-driver renderers (Playwright TS, then Espresso Kotlin and XCUITest Swift as demand warrants). This is the path that makes "native-framework export" coherent on mobile without compromising the authoring abstraction. Not yet built; flagged here so when the question comes up we have an architectural answer rather than restating the tradeoff from scratch.
4. **Generalize the passthrough-tool pattern.** The "maestro" tool already lets a trail drop into native Maestro YAML. Equivalents should exist per driver — `playwright_call`, `accessibility_raw`, `xcuitest_raw` — so that any divergence we've added on top of a driver is bypassable. This keeps the thicker-shim drivers honest and makes the "escape hatch always exists" claim load-bearing rather than aspirational. Cheap to add per driver; worth doing as the drivers' tool surfaces are reviewed.

## Adjacent reading

- The web-side auto-wait + settle work that made the Playwright export feasible (zero blind `web_wait` filler, rich post-action `nodeSelector` enrichment).
- The custom-tool architecture and scripted-tools execution model — context for what "the abstraction the framework is built on" actually means.
- The waypoint + shortcut graph vision — where mobile recording is heading: semantic, agent-decision-level, not gesture-level. Note that the resolved-action trace sits *below* this layer, capturing whatever a `goTo(waypoint_id)` expanded to at the driver wire; the two are complementary, not competing.
- The "`TrailblazeTool` is a function call" framing — relevant because it makes explicit that every tool call has a resolution point (the driver dispatch) where a tap can capture the rendered form.

## The framing to use externally

When a customer or reviewer asks "do you have code generation like \<vendor\>?", the answer has two halves:

- **What ships today:** "On web, yes — `--export-playwright` emits a runnable Playwright spec from any recorded trail. It works because the web driver is a thin shim over Playwright, and the recorded trail and the underlying Playwright actions coincide one-to-one on that driver."
- **What the architecture lets us do everywhere:** "Code generation isn't a property of the authoring layer; it's a property of the execution trace. Every action a Trailblaze trail performs resolves to a native-driver call. We can capture those resolved calls and render them as Playwright TypeScript, Espresso Kotlin, XCUITest Swift, or any other native dialect *without* requiring the authoring surface (trailheads, scripted tools, waypoint navigation, custom domain tools) to be expressible in that dialect. Authoring is the source language; export is the target. They intentionally don't have the same expressiveness — that's the whole point."

That second half is what makes the LLM-callable custom-tool surface and the native-framework-export surface coexist. Single-layer codegen products can only do *one* of these — they author at the framework level and export at the same level. Trailblaze authors above the framework level and exports below it. Same execution-layer artifact as codegen, plus an authoring layer they structurally can't have.

If both halves feel right when said out loud, the architecture and the positioning are coherent. If only the first feels right, we're sliding toward being a single-layer codegen product *at the authoring layer*, which gives away the win — make that decision explicitly rather than by drift.
