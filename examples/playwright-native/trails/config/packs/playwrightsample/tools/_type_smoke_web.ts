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
// WHY IT LIVES UNDER playwright-native/.../playwrightsample/tools/:
//
//   This sample pack is also the canonical "unit-test fixture" for the per-pack TS authoring
//   surface — the same dir hosts the mobile sister file (`_type_smoke.ts`, added in PR #2481
//   / extended in PR #3181) and the scripted-tool .ts/.yaml pairs that exercise the
//   pack-bundler. Type-level smoke files live next to the surface they test (the consumer-
//   side `.d.ts` resolution), not in the trailblaze-host JVM test source set. The
//   complementary JVM-side coverage — `WorkspaceClientDtsGeneratorTest`,
//   `PerPackClientDtsEmitterTest`, `WebToolSetCatalogTest` — tests the *generator* (Kotlin
//   in → JSON schema out). This file tests the orthogonal property: that the emitted .d.ts
//   actually type-checks when a TypeScript author consumes it through `client.tools.X(...)`.
//
// WHY THIS FILE EXISTS (beyond the mobile smoke):
//
//   After Phase B (PR #3175) and Phase D (PR #3181), web tools (`web_navigate`, `web_click`,
//   `web_verify_text_visible`, etc.) no longer live in the SDK's hand-vendored
//   `built-in-tools.ts`. They flow into the per-pack `client.d.ts` automatically from the
//   daemon's tool registry. That auto-emit path had no curated regression test — if a daemon
//   tool descriptor's name/arg schema drifts, codegen silently emits a different shape, and
//   no alarm fires until a customer-facing pack hits a compile error. This file is that alarm.
//
// SCOPE — KEEP IT TIGHT:
//
//   This is the curated subset (tutorial flows + the canonical pack-composition pattern),
//   not every web tool. Adding every tool would create maintenance drag for marginal benefit
//   — the regression alarm fires off the well-known subset.
//
// CI WIRING — KNOWN GAP AT TIME OF WRITING:
//
//   The per-pack TS validation step in the PR CI pipeline discovers tool dirs by the
//   presence of a per-tools `package.json` (the pre-pack-layout signal). The current
//   post-Phase-B layout puts the SDK once at the workspace level (`<workspace>/.trailblaze/
//   sdk/`, materialized by `trailblaze check`) and leaves each pack's `tools/` dir with
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
void client.tools.web_select_option({ values: ["support"], ref: "combobox \"Category\"" });
void client.tools.web_press_key({ key: "Enter" });

// Verification: element / text / value (including the ATTRIBUTE arm).
void client.tools.web_verify_element_visible({ ref: "button \"Submit\"" });
void client.tools.web_verify_text_visible({ text: "Submitted!" });
void client.tools.web_verify_value({ expected: "user@example.com", ref: "e5" });
void client.tools.web_verify_value({
  expected: "https://example.com/icon.png",
  ref: "e7",
  type: "ATTRIBUTE",
  attribute: "src",
});

// Side-channel inspection: evaluate / currentUrl.
void client.tools.web_evaluate({ script: "(() => window.location.href)()" });
void client.tools.web_currentUrl({}); // takes no args — Record<string, never>

// === Regression coverage: each `@ts-expect-error` MUST stay errored. ===

// @ts-expect-error wrong-keyed args on web_navigate (typo in `url`)
void client.tools.web_navigate({ action: "GOTO", ulr: "typo" });

// @ts-expect-error value outside the enum union on web_navigate.action
void client.tools.web_navigate({ action: "FETCH" });

// @ts-expect-error missing required `text` on web_type
void client.tools.web_type({ ref: "e1" });

// @ts-expect-error missing required `text` on web_verify_text_visible
void client.tools.web_verify_text_visible({});

// @ts-expect-error missing required `expected` on web_verify_value
void client.tools.web_verify_value({ ref: "e1" });

// @ts-expect-error value outside the enum union on web_verify_value.type
void client.tools.web_verify_value({ expected: "x", type: "HTML" });

// @ts-expect-error value outside the enum union on web_scroll.direction
void client.tools.web_scroll({ direction: "BACKWARD" });

// @ts-expect-error missing required `script` on web_evaluate
void client.tools.web_evaluate({});

// @ts-expect-error missing required `values` on web_select_option
void client.tools.web_select_option({ ref: "e1" });

// @ts-expect-error missing required `key` on web_press_key
void client.tools.web_press_key({});

// @ts-expect-error extra arg on web_currentUrl (declared as Record<string, never>)
void client.tools.web_currentUrl({ foo: "bar" });

// @ts-expect-error tool name not in TrailblazeToolMap — guards against a future loosening that
// re-widens `client.tools` to accept arbitrary keys.
void client.tools.web_nonexistent_tool({});

// Marks this file as a TS module — required when using `@ts-expect-error` directives that
// reference imported symbols. See sister file for kdoc.
export {};
