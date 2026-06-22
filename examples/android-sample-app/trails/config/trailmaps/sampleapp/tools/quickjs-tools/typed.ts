// On-device-compatible TS tools — authored against the one `@trailblaze/scripting` SDK and
// shaped to compile cleanly against QuickJS. No `node:fs`, no `child_process`, no `fetch` —
// anything that depends on a Node API would break the on-device target at evaluation time.
//
// What "compiled to JS" looks like at runtime: the build bundles this file with
// `@trailblaze/scripting` aliased to the slim in-process SDK profile, then synthesizes a small
// wrapper that registers each exported tool on `globalThis.__trailblazeTools[...]` — exactly the
// registry shape the hand-written pure-JS sibling (`pure.js`) populates. The runtime can't tell
// which path produced the registration.
//
// Authoring form: each tool is a named `export const <toolName> = trailblaze.tool<Input>(handler)`.
// The description lives in the TSDoc above the export; the input shape is the `<Input>` type
// param; the handler returns the bare value (a string) and throws on error.

import { trailblaze } from "@trailblaze/scripting";

/**
 * Uppercases the input string. Demo of a TypeScript-authored tool that's on-device
 * compatible — uses zero Node APIs, runs in QuickJS unchanged from how it runs in a
 * host-embedded engine.
 */
interface UppercaseInput {
  /** The string to uppercase. */
  text: string;
}

export const sampleApp_uppercase = trailblaze.tool<UppercaseInput>(
  async (input) => String(input.text ?? "").toUpperCase(),
);

/** Pretty-prints any JSON-serializable value the caller passes as `value`. */
interface JsonStringifyInput {
  /** Anything JSON-serializable. */
  value: unknown;
}

export const sampleApp_jsonStringify = trailblaze.tool<JsonStringifyInput>(
  async (input) => {
    // `JSON.stringify(undefined, null, 2)` returns the JS literal `undefined`, not a string —
    // returning that as the result produces a malformed envelope. Coerce to a sentinel so the
    // result always carries a string.
    const value = input.value;
    return value === undefined ? "undefined" : JSON.stringify(value, null, 2);
  },
);
