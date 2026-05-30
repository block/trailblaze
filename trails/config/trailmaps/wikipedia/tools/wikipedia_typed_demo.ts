// Sample scripted tool authored against the typed `trailblaze.tool<I, O>(handler)`
// (bare-handler) surface. Kept here as the smallest possible illustration of the
// typed authoring contract — TSDoc → IDE hover → handler types → result shape —
// without the noise of a real workflow. Open it in your IDE to hover over
// `<TypedDemoInput, TypedDemoOutput>` and see the typed surface the analyzer
// extracts.
//
// The trailmap loader auto-discovers every `.ts` with a `trailblaze.tool` export
// under `tools/`, so this file IS added to the candidate pool — but it's NOT
// listed in `trailmap.yaml`'s `target.tools:`, which is the gate for what the
// agent actually sees. To make this dispatchable from a trail, add
// `wikipedia_typed_demo` to that list. No YAML descriptor is required.

import { trailblaze } from "@trailblaze/scripting";

/**
 * Inputs for the typed-authoring demo tool. Each field's TSDoc comment becomes a JSON
 * Schema `description` once the AST-driven codegen lands.
 */
export interface TypedDemoInput {
  /** The message to format. */
  message: string;
  /** Optional prefix prepended to the formatted message. Defaults to none. */
  prefix?: string;
}

/**
 * Result of the typed-authoring demo tool. A structured object so the demo carries a
 * non-trivial result shape — future codegen will emit this as the `result` half of the
 * tool's [TrailblazeToolMap] entry, and other scripted tools that call into it via
 * `client.tools.wikipedia_typed_demo(...)` will see the typed shape directly.
 */
export interface TypedDemoOutput {
  /** The formatted message, with the optional prefix already applied. */
  formatted: string;
  /** Length of the original input message in code units. */
  inputLength: number;
}

/**
 * Typed-authoring demo: formats the input message with an optional prefix and returns a
 * small structured result. Intentionally trivial — exists to exercise the
 * `trailblaze.tool<I, O>(handler)` pattern end-to-end (TSDoc → IDE hover → handler
 * types → result shape).
 */
export const wikipedia_typed_demo = trailblaze.tool<TypedDemoInput, TypedDemoOutput>(
  async (input) => {
    const formatted = input.prefix ? `${input.prefix}${input.message}` : input.message;
    return {
      formatted,
      inputLength: input.message.length,
    };
  },
);
