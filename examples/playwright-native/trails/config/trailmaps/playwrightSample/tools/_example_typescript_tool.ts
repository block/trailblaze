// Reference example for Tier-1 TypeScript tool authoring.
//
// This file exists purely as an IDE-supported authoring template demonstrating
// typed `args`, `ctx`, and `client`. It exports plain functions (not
// `trailblaze.tool(...)` bindings), so the trailmap loader's auto-discovery
// pass skips it — its underscore-prefixed filename also signals "reference /
// not a real tool" to readers browsing the directory.
//
// To turn an export here (or in a new sibling `.ts` file) into a real tool:
//
// 1. Author the export as a `trailblaze.tool<I>(spec, handler)` binding:
//
//      export const myTool = trailblaze.tool<MyArgs>(
//        { supportedPlatforms: ["web"] },
//        async (input, ctx) => "ok",
//      );
//
// 2. Add the export name to the trailmap manifest at `../trailmap.yaml` under
//    `target.tools:`:
//
//      target:
//        tools:
//          - playwrightSample_web_openFixtureAndVerifyText
//          - ...
//          - myTool      # ← add this line
//
// No sibling YAML descriptor is required — the loader auto-discovers any
// `.ts` with a `trailblaze.tool` export and the analyzer fills in name +
// input schema + description from the call site and TSDoc. A YAML is only
// needed to disambiguate a multi-export file or pin a descriptor field
// explicitly.

import type { TrailblazeContext, TrailblazeClient } from "@trailblaze/scripting";

/**
 * Demonstrates the author-facing handler signature against the typesafe
 * `client.tools.<name>(args)` namespace.
 *
 * Hover over `args` in your IDE — the `{ greeting: string }` shape comes from this
 * file's annotation, NOT from a YAML inputSchema. The inputSchema in the YAML
 * descriptor is the runtime contract; matching the TS type to it is an author
 * convention. (Auto-generating the `args` type from the YAML inputSchema is a
 * separate follow-up.)
 *
 * Hover over `ctx` — autocomplete shows `device`, `sessionId`, `invocationId`,
 * `memory`, `baseUrl`, `runtime`. All typed via `TrailblazeContext` from the SDK.
 *
 * Hover over `client.tools.` — autocomplete shows both:
 *   1. Vendored built-ins from `@trailblaze/scripting` (e.g. `inputText`,
 *      `tapOnElementWithText`).
 *   2. This trailmap's scripted tools plus the Kotlin tools resolved through its
 *      `tool_sets:`, written to `.trailblaze/client.d.ts` by `trailblaze
 *      compile`. (`./gradlew bundleTrailblazeTrailmap` writes the sibling
 *      `.trailblaze/tools.d.ts`, which covers the trailmap's scripted tools only;
 *      both files declaration-merge into the same `TrailblazeToolMap`.)
 * Tool names outside that union are a compile error — the low-level
 * `client.callTool` dispatcher is intentionally hidden from the public type to
 * keep the namespace the single typed entry point.
 */
export async function exampleHello(
  args: { greeting: string },
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<{
  content: Array<{ type: "text"; text: string }>;
  isError: boolean;
}> {
  if (!ctx) {
    return {
      content: [{ type: "text", text: "exampleHello: no Trailblaze session context" }],
      isError: true,
    };
  }

  // Validate `args` at runtime even though TypeScript narrows it for us at compile time. The
  // TS signature describes the *expected* input shape, but the actual `args` arrives from the
  // wire (LLM, callTool dispatcher, ad-hoc CLI invocation) where nothing enforces the YAML
  // inputSchema beyond what the SDK's input parser does. Authors copying this template should
  // keep this validation pattern — it turns malformed input into a clear isError result rather
  // than a TypeError ("Cannot read properties of undefined") on the first property access.
  //
  // Defense-in-depth ordering: null/undefined args first, then per-field checks. Don't combine
  // into one expression — JS short-circuits but the error message gets less specific.
  if (args === null || typeof args !== "object") {
    return {
      content: [{ type: "text", text: "exampleHello: missing or non-object `args`" }],
      isError: true,
    };
  }
  // Read `greeting` directly — it's already typed `unknown` because args came in as a
  // record from the wire. The `typeof` check below does the actual narrowing; no cast is
  // needed (and an intermediate cast would just look like it's narrowing without doing so).
  const greeting = (args as Record<string, unknown>).greeting;
  if (typeof greeting !== "string" || greeting.trim().length === 0) {
    return {
      content: [
        { type: "text", text: "exampleHello: missing or blank `greeting` arg (expected non-empty string)" },
      ],
      isError: true,
    };
  }

  const dimensions = `${ctx.device.widthPixels}×${ctx.device.heightPixels}`;
  const summary =
    `${greeting} from session ${ctx.sessionId} on ` +
    `${ctx.device.platform} (${dimensions}, ${ctx.device.driverType})`;

  // Composition example — `client.tools.<name>(args)` dispatches any registered
  // Trailblaze tool (built-in or another scripted tool from this trailmap). The
  // result has `success`, `textContent`, `errorMessage` fields. Throws on
  // failure (no success-flag branching needed in the happy path).
  //
  // The two calls below resolve through the typed namespace — autocomplete
  // shows the parameter shapes, and a typo / wrong-keyed args object errors at
  // compile time. They're left commented out to keep this reference file
  // inert (no live device required to run `tsc`); uncomment to exercise
  // against a session.
  //
  //   // Built-in tool, args typed from the SDK's vendored `built-in-tools.ts`.
  //   await client.tools.inputText({ text: args.greeting });
  //
  //   // Scripted tool from this trailmap, args typed from the generated
  //   // `.trailblaze/tools.d.ts`. Try changing `relativePath` to `releativePath`
  //   // — `tsc` errors immediately.
  //   const verified = await client.tools.playwrightSample_web_openFixtureAndVerifyText({
  //     relativePath: "fixtures/text-snippet.html",
  //     text: args.greeting,
  //   });
  //   console.error("[exampleHello] verified:", verified.textContent);

  // Suppress the unused-parameter lint for `client` in the inert reference.
  void client;

  return {
    content: [{ type: "text", text: summary }],
    isError: false,
  };
}
