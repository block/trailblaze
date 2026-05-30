// TYPE-LEVEL REGRESSION TEST — NOT A RUNTIME TEST.
//
// This file is never executed. `client` is `declare const` (type-only — no runtime binding),
// and every `client.tools.X(...)` line is prefixed with `void` so even if the file were
// imported it would be a no-op. The "test runner" is `tsc --noEmit`. A regression looks like
// either of:
//
//   - A happy-path line below stops compiling — someone renamed a tool, changed a required
//     arg, or dropped a field. tsc reports the line and fails the build.
//   - A `@ts-expect-error` line below starts compiling — someone loosened the type contract
//     (e.g. re-widened `client.tools` to accept arbitrary keys, or relaxed a strict enum to
//     `string`). tsc reports TS2578 "unused directive" on the line and fails the build.
//
// In other words: flipping safe and unsafe is symmetrical, and tsc enforces both directions.
//
// WHY IT LIVES UNDER playwright-native/.../playwrightSample/tools/:
//
//   This sample trailmap is also the canonical "unit-test fixture" for the per-trailmap TS authoring
//   surface — the same dir hosts the mobile sister file (`_type_smoke.ts`, added in PR #2481
//   / extended in PR #3181) and the scripted-tool .ts/.yaml pairs that exercise the
//   trailmap-bundler. Type-level smoke files live next to the surface they test (the consumer-
//   side `.d.ts` resolution), not in the trailblaze-host JVM test source set. The
//   complementary JVM-side coverage — `WorkspaceClientDtsGeneratorTest`,
//   `PerTrailmapClientDtsEmitterTest`, `WebToolSetCatalogTest` — tests the *generator* (Kotlin
//   in → JSON schema out). This file tests the orthogonal property: that the emitted .d.ts
//   actually type-checks when a TypeScript author consumes it through `client.tools.X(...)`.
//
// WHY THIS FILE EXISTS (beyond the mobile smoke):
//
//   After Phase B (PR #3175) and Phase D (PR #3181), web tools (`web_navigate`, `web_click`,
//   `web_verifyTextVisible`, etc.) no longer live in the SDK's hand-vendored
//   `built-in-tools.ts`. They flow into the per-trailmap `client.d.ts` automatically from the
//   daemon's tool registry. That auto-emit path had no curated regression test — if a daemon
//   tool descriptor's name/arg schema drifts, codegen silently emits a different shape, and
//   no alarm fires until a customer-facing trailmap hits a compile error. This file is that alarm.
//
// SCOPE — KEEP IT TIGHT:
//
//   This is the curated subset (tutorial flows + the canonical trailmap-composition pattern),
//   not every web tool. Adding every tool would create maintenance drag for marginal benefit
//   — the regression alarm fires off the well-known subset.
//
// CI WIRING — KNOWN GAP AT TIME OF WRITING:
//
//   The per-trailmap TS validation step in the PR CI pipeline discovers tool dirs by the
//   presence of a per-tools `package.json` (the pre-trailmap-layout signal). The current
//   post-Phase-B layout puts the SDK once at the workspace level (`<workspace>/.trailblaze/
//   sdk/`, materialized by `trailblaze check`) and leaves each trailmap's `tools/` dir with
//   only a `tsconfig.json`, so that step does NOT currently pick this dir up. The mobile
//   sister `_type_smoke.ts` (PR #3181) lives in the same gap.
//
//   Locally, `tsc --noEmit` in this dir against the daemon-emitted `client.d.ts` is the
//   load-bearing contract — that's what catches a regression today, run by a developer
//   or an IDE typecheck. Closing the dir-discovery gap so the CI step picks up this layout
//   (extend discovery to `tsconfig.json` presence + invoke `trailblaze check` to
//   materialize `.trailblaze/sdk/` before tsc) is tracked as a follow-up. Once that lands,
//   this file and the mobile sister become CI-backed regression alarms — until then,
//   they're authored as the contract and run on demand.

import type { TrailblazeClient } from "@trailblaze/scripting";

// Pure type-level fixture — never instantiated, never invoked. See sister file for kdoc.
declare const client: TrailblazeClient;

// === Happy path: typed surface — these MUST compile cleanly. ===

// Navigation: GOTO with a url, plus the BACK/FORWARD shape.
void client.tools.web_navigate({ action: "GOTO", url: "https://example.com" });
void client.tools.web_navigate({ action: "BACK" });
void client.tools.web_navigate({}); // every field is optional

// Interaction: click / type / scroll / select_option / press_key.
void client.tools.web_click({ ref: "css=#submit" });
void client.tools.web_type({ text: "hello", ref: "textbox \"Email\"" });
void client.tools.web_type({ text: "appended", ref: "e5", clearFirst: false });
void client.tools.web_scroll({ direction: "DOWN", amount: 300 });
void client.tools.web_selectOption({ values: ["support"], ref: "combobox \"Category\"" });
void client.tools.web_pressKey({ key: "Enter" });

// Verification: element / text / value (including the ATTRIBUTE arm).
void client.tools.web_verifyElementVisible({ ref: "button \"Submit\"" });
void client.tools.web_verifyTextVisible({ text: "Submitted!" });
void client.tools.web_verifyValue({ expected: "user@example.com", ref: "e5" });
void client.tools.web_verifyValue({
  expected: "https://example.com/icon.png",
  ref: "e7",
  type: "ATTRIBUTE",
  attribute: "src",
});

// Side-channel inspection: evaluate / currentUrl.
void client.tools.web_evaluate({ script: "(() => window.location.href)()" });
void client.tools.web_currentUrl({}); // takes no args — Record<string, never>

// Function-overload surface — Playwright-style `page.evaluate(fn, args)` ergonomics.
// The proxy stringifies the arrow + JSON-encodes args before dispatch (see WebEvaluateMethod
// in client.ts for the wire-format contract). Three call shapes covered:
//   1) Bare arrow: `web_evaluate(() => ...)` — no args, return type inferred from the arrow.
//   2) Arrow + typed args: `web_evaluate(fn, ...args)` — TArgs inferred from the arrow's
//      parameter list, so a mismatched arg type fails compilation at the call site.
//   3) Bare script string: `web_evaluate("(() => ...)()")` — compatibility surface for
//      authors who want full control over the script body.
//
// The arrow bodies reference `globalThis` rather than `window` because the per-trailmap
// `tsconfig.json` ships with `lib: ["ES2022"]` (no DOM types) — the framework's authoring
// surface intentionally stays platform-neutral. At runtime, the arrow gets serialized and
// re-evaluated in the page context where `window` IS available, but at typecheck time the
// trailmap-local TS environment doesn't know about it. `globalThis` resolves on both sides and
// keeps the type-smoke regression contract intact without leaking a DOM-lib dependency.
void client.tools.web_evaluate(() => (globalThis as { location?: { href: string } }).location?.href ?? "");
void client.tools.web_evaluate((path: string, multiplier: number) => path.repeat(multiplier), "/wiki/Main_Page", 2);
void client.tools.web_evaluate("(() => window.document.title)()");

// === Regression coverage: each `@ts-expect-error` MUST stay errored. ===

// @ts-expect-error wrong-keyed args on web_navigate (typo in `url`)
void client.tools.web_navigate({ action: "GOTO", ulr: "typo" });

// @ts-expect-error value outside the enum union on web_navigate.action
void client.tools.web_navigate({ action: "FETCH" });

// @ts-expect-error missing required `text` on web_type
void client.tools.web_type({ ref: "e1" });

// @ts-expect-error missing required `text` on web_verifyTextVisible
void client.tools.web_verifyTextVisible({});

// @ts-expect-error missing required `expected` on web_verifyValue
void client.tools.web_verifyValue({ ref: "e1" });

// @ts-expect-error value outside the enum union on web_verifyValue.type
void client.tools.web_verifyValue({ expected: "x", type: "HTML" });

// @ts-expect-error value outside the enum union on web_scroll.direction
void client.tools.web_scroll({ direction: "BACKWARD" });

// `web_evaluate` is a framework-internal utility registered via `web_framework.yaml`
// (sibling to `android_framework.yaml` for `android_adbShell` and friends). It carries
// `surfaceToLlm = false` + `isRecordable = false` at the class level, so the LLM never
// sees it and trail recordings never capture it as a step — but per-trailmap codegen DOES
// emit a `TrailblazeToolMap` entry for it so scripted-tool composition can reach it
// through `client.tools.web_evaluate(...)`. Same shape of access pattern as
// `client.tools.android_adbShell(...)` in the android trailmap-scripted tools.

// @ts-expect-error missing required `script` on web_evaluate
void client.tools.web_evaluate({});

// Function-overload regression coverage — locks the call-time arg-typing contract on the
// arrow's parameter list so a future refactor that widens TArgs to `unknown[]` (or drops
// the rest-parameter forwarding) re-introduces the failures below.

// @ts-expect-error arrow takes `string`, caller passes a number — TArgs inference rejects
void client.tools.web_evaluate((x: string) => x.length, 42);

// @ts-expect-error arrow takes one arg, caller passes none — missing-required-positional
void client.tools.web_evaluate((x: string) => x.length);

// @ts-expect-error invalid first-arg shape — neither function, string, nor `{ script }`
void client.tools.web_evaluate(42);

// @ts-expect-error missing required `values` on web_selectOption
void client.tools.web_selectOption({ ref: "e1" });

// @ts-expect-error missing required `key` on web_pressKey
void client.tools.web_pressKey({});

// @ts-expect-error extra arg on web_currentUrl (declared as Record<string, never>)
void client.tools.web_currentUrl({ foo: "bar" });

// @ts-expect-error tool name not in TrailblazeToolMap — guards against a future loosening that
// re-widens `client.tools` to accept arbitrary keys.
void client.tools.web_nonexistent_tool({});

// Marks this file as a TS module — required when using `@ts-expect-error` directives that
// reference imported symbols. See sister file for kdoc.
export {};
