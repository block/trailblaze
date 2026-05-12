// Persistent type-level regression coverage for the Tier-2 typesafe `client.tools.*`
// surface and the conditional `client.callTool` signature. Lives in playwright-native (not
// in the SDK) because TypeScript's `declare module "@trailblaze/scripting"` augmentations
// are visible from a consumer that imports the package by name — the SDK source itself
// can't see its own augmentations through file-relative imports.
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
//     correct args for both built-in and pack-scripted tools, fallback overload for
//     unknown names.
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

// Pack-scripted tool generated into `.trailblaze/tools.d.ts`.
void client.tools.playwrightSample_web_openFixtureAndVerifyText({
  relativePath: "fixtures/text-snippet.html",
  text: "expected on page",
});

// Same calls via the lower-level `callTool` conditional surface.
void client.callTool("inputText", { text: "hi" });
void client.callTool("playwrightSample_web_openFixtureAndVerifyText", {
  relativePath: "fixtures/text-snippet.html",
  text: "expected on page",
});

// Unknown tool name through the fallback overload — accepts `Record<string, unknown>`,
// no autocomplete on args. Compiles by design.
void client.callTool("dynamicallyDispatchedTool", { foo: "bar" });

// === Regression coverage: each `@ts-expect-error` MUST stay errored. ===
//
// If the strict-typing contract loosens (e.g. someone collapses the conditional to a
// permissive overload, or removes `TrailblazeToolMethods`'s mapped-type derivation), the
// directive becomes "unused" and tsc fails the build with TS2578.

// @ts-expect-error wrong-keyed args via callTool
void client.callTool("inputText", { tex: "wrong key" });

// @ts-expect-error missing required arg via callTool
void client.callTool("inputText", {});

// @ts-expect-error value outside the enum union via callTool
void client.callTool("pressKey", { keyCode: "TAB" });

// @ts-expect-error wrong-keyed args via tools.* namespace
void client.tools.inputText({ tex: "wrong key" });

// @ts-expect-error missing required arg via tools.* namespace
void client.tools.inputText({});

// @ts-expect-error value outside the enum union via tools.* namespace
void client.tools.pressKey({ keyCode: "TAB" });

// @ts-expect-error wrong-keyed args on a pack-scripted tool via tools.* namespace (single-line: `@ts-expect-error` only covers the next line)
void client.tools.playwrightSample_web_openFixtureAndVerifyText({ releativePath: "typo", text: "y" });

// @ts-expect-error tool name not in TrailblazeToolMap — `client.tools.X` only exposes augmented entries
void client.tools.dynamicallyDispatchedTool({ foo: "bar" });

// Marks this file as a TS module — required when using `@ts-expect-error` directives that
// reference imported symbols. Without it, TS treats the file as a global script and
// imports get hoisted in unexpected ways.
export {};
