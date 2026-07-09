// Persistent type-level regression coverage for the typesafe `client.tools.*` surface.
// Lives in playwright-native (not in the SDK) because TypeScript's
// `declare module "@trailblaze/scripting"` augmentations are visible from a consumer that
// imports the package by name — the SDK source itself can't see its own augmentations
// through file-relative imports.
//
// HOW THIS FILE WORKS:
//
//   - The file is checked in. The runway CI step (`pr_validate_ts_tooling.sh`) runs
//     `tsc --noEmit` here on every PR. Any regression in the strict-typing contract
//     produces a `tsc` error and fails the build.
//
//   - Lines preceded by `// @ts-expect-error` MUST produce a compile error today; if the
//     strictness regresses (the line starts compiling cleanly), TypeScript flags the
//     directive as "unused" and tsc fails. That's the regression signal — flipping safe
//     and unsafe is symmetrical.
//
//   - Lines without the directive must compile cleanly. They cover the happy path:
//     correct args for both built-in and trailmap-scripted tools via `client.tools.X(...)`.
//
// The file is never imported, never invoked, never registered. It exists only to be
// type-checked. The single export keeps it a TypeScript module rather than a global
// script (so `import` statements at the top resolve correctly).

import type { TrailblazeClient } from "@trailblaze/scripting";

// Pure type-level fixture — never instantiated, never invoked. `declare` produces a binding
// at the type-checker level only, so this file generates no runtime references and would
// not crash even if every `void client.tools.X(...)` line below were live.
declare const client: TrailblazeClient;

// === Happy path: typed surface — these MUST compile cleanly. ===

// Built-in tool from the SDK's vendored `built-in-tools.ts`.
void client.tools.inputText({ text: "hi" });

// Built-in tool with optional args.
void client.tools.tapOnPoint({ x: 10, y: 20, longPress: true });

// Trailmap-scripted tool generated into `.trailblaze/tools.d.ts`.
void client.tools.playwrightSample_web_openFixtureAndVerifyText({
  relativePath: "fixtures/text-snippet.html",
  text: "expected on page",
});

// === Regression coverage: each `@ts-expect-error` MUST stay errored. ===
//
// If the strict-typing contract loosens (e.g. someone reintroduces a `callTool` overload
// on the exported `TrailblazeClient` type, or removes `TrailblazeToolMethods`'s mapped-type
// derivation), the directive becomes "unused" and tsc fails the build with TS2578.

// @ts-expect-error wrong-keyed args via tools.* namespace
void client.tools.inputText({ tex: "wrong key" });

// @ts-expect-error missing required arg via tools.* namespace
void client.tools.inputText({});

// @ts-expect-error value outside the enum union via tools.* namespace ("NOPE" is not a PressKeyCode;
// the real union — BACK | ENTER | HOME | BACKSPACE | TAB | ESCAPE — is now generated from the Kotlin
// enum, so pick a value that can't drift into it rather than one the enum happens to omit today)
void client.tools.pressKey({ keyCode: "NOPE" });

// @ts-expect-error wrong-keyed args on a trailmap-scripted tool via tools.* namespace (single-line: `@ts-expect-error` only covers the next line)
void client.tools.playwrightSample_web_openFixtureAndVerifyText({ releativePath: "typo", text: "y" });

// @ts-expect-error tool name not in TrailblazeToolMap — `client.tools.X` only exposes augmented entries
void client.tools.dynamicallyDispatchedTool({ foo: "bar" });

// === Phase D: the low-level `callTool` dispatcher is hidden from the public type. ===
//
// Pre-Phase-D the exported client carried a `callTool<K extends string>(name, args)`
// signature whose untyped-fallback branch let an author write
// `client.callTool("dynamicallyDispatchedTool", { foo: "bar" })` to bypass the typed
// surface. That escape hatch is gone — `TrailblazeClient = Omit<TrailblazeClientImpl,
// "callTool">` removes the property from the public type entirely. The runtime still
// carries the method as the internal dispatch primitive the `tools` Proxy delegates to,
// but it's no longer reachable through the exported type.
//
// These regressions guard against a refactor that re-exposes `callTool` or widens the
// `tools` namespace's mapped type to accept arbitrary keys.

// @ts-expect-error callTool is no longer on the public client surface, even for a known tool
void client.callTool("inputText", { text: "hi" });

// @ts-expect-error callTool with an unknown tool name (was the untyped-fallback escape hatch)
void client.callTool("dynamicallyDispatchedTool", { foo: "bar" });

// @ts-expect-error callTool with a known tool name and a wrong-keyed arg
void client.callTool("inputText", { tex: "wrong key" });

// Marks this file as a TS module — required when using `@ts-expect-error` directives that
// reference imported symbols. Without it, TS treats the file as a global script and
// imports get hoisted in unexpected ways.
export {};
