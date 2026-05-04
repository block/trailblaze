// Reference example for Tier-1 TypeScript tool authoring.
//
// This file is NOT registered in any pack — it exists purely as an IDE-supported
// authoring template demonstrating typed `args`, `ctx`, and `client`.
//
// To turn an export here (or in a sibling `.ts` file) into a real tool, two steps:
//
// 1. Create a YAML descriptor at `../packs/playwrightsample/tools/<your-tool>.yaml`:
//
//      script: ./examples/playwright-native/trailblaze-config/tools/<filename>.ts
//      name: <export-name-from-this-file>
//      description: ...
//      inputSchema:
//        <param>:
//          type: string
//          description: ...
//
// 2. Add the descriptor to the pack manifest at
//    `../packs/playwrightsample/pack.yaml` under `target.tools:` — the pack loader
//    only resolves scripted tools that are explicitly listed there. Without this
//    step the YAML is ignored and the tool never registers at runtime.
//
//      target:
//        tools:
//          - tools/playwrightSample_web_openFixtureAndVerifyText.yaml
//          - tools/playwrightSample_web_incrementCounterToValue.yaml
//          - ...
//          - tools/<your-tool>.yaml      # ← add this line
//
// The filename underscore prefix sorts the file to the top of the directory
// listing and signals "reference / not a real tool" to readers browsing the dir.

import type { TrailblazeContext, TrailblazeClient } from "@trailblaze/scripting";

/**
 * Demonstrates the Tier-1 handler signature, including typesafe `client.callTool`
 * (Tier-2 generated bindings have since landed).
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
 * Hover over `client.callTool` — autocomplete now shows both:
 *   1. Vendored built-ins from `@trailblaze/scripting` (e.g. `inputText`,
 *      `tapOnElementWithText`).
 *   2. This pack's scripted tools, generated from the pack manifest into
 *      `.trailblaze/tools.d.ts` by `./gradlew bundleTrailblazePack`.
 * Tool names not in either set fall through to the untyped
 * `(string, Record<string, unknown>)` overload — fully usable, just no autocomplete.
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

  // Composition example — `client.callTool` dispatches any registered Trailblaze
  // tool (built-in or another scripted tool from this pack). The result has
  // `success`, `textContent`, `errorMessage` fields. Throws on failure (no
  // success-flag branching needed in the happy path).
  //
  // The two calls below resolve through the strict overload — autocomplete shows
  // the parameter shapes, and a typo / wrong-keyed args object errors at compile
  // time. They're left commented out to keep this reference file inert (no live
  // device required to run `tsc`); uncomment to exercise against a session.
  //
  //   // Built-in tool, args typed from the SDK's vendored `built-in-tools.ts`.
  //   await client.callTool("inputText", { text: args.greeting });
  //
  //   // Scripted tool from this pack, args typed from the generated
  //   // `.trailblaze/tools.d.ts`. Try changing `relativePath` to `releativePath`
  //   // — `tsc` errors immediately.
  //   const verified = await client.callTool(
  //     "playwrightSample_web_openFixtureAndVerifyText",
  //     { relativePath: "fixtures/text-snippet.html", text: args.greeting },
  //   );
  //   console.error("[exampleHello] verified:", verified.textContent);

  // Suppress the unused-parameter lint for `client` in the inert reference.
  void client;

  return {
    content: [{ type: "text", text: summary }],
    isError: false,
  };
}
