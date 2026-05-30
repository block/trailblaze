// On-device-compatible TS tool — uses the tiny `@trailblaze/tools` SDK and is shaped to
// compile cleanly against QuickJS. No `node:fs`, no `child_process`, no `fetch` — anything
// that depends on a Node API would break the on-device target at evaluation time.
//
// What "compiled to JS" looks like at runtime: esbuild bundles this file with
// `@trailblaze/tools` aliased to the SDK shim source (or inlined directly), produces a single
// `.bundle.js` that calls `globalThis.__trailblazeTools[...] = { spec, handler }` — exactly the
// same registry shape the pure-JS file populates by hand. The runtime can't tell which path
// produced the registration.

import { trailblaze } from "@trailblaze/tools";

trailblaze.tool(
  "sampleApp_uppercase",
  {
    description:
      "Uppercases the input string. Demo of a TypeScript-authored tool that's on-device " +
      "compatible — uses zero Node APIs, runs in QuickJS unchanged from how it runs in a " +
      "host-embedded engine.",
    inputSchema: {
      text: { type: "string", description: "The string to uppercase." },
    },
  },
  async (args) => {
    const text = String((args as { text?: unknown }).text ?? "");
    return {
      content: [{ type: "text", text: text.toUpperCase() }],
      isError: false,
    };
  },
);

trailblaze.tool(
  "sampleApp_jsonStringify",
  {
    description: "Pretty-prints any JSON-serializable value the caller passes as `value`.",
    inputSchema: {
      value: { description: "Anything JSON-serializable." },
    },
  },
  async (args) => {
    // `JSON.stringify(undefined, null, 2)` returns the JS literal `undefined`, not a string —
    // returning that as `text` produces a malformed `TrailblazeToolResult`. Coerce to a
    // sentinel so the result envelope always carries a string.
    const value = (args as { value?: unknown }).value;
    const text = value === undefined ? "undefined" : JSON.stringify(value, null, 2);
    return {
      content: [{ type: "text", text }],
      isError: false,
    };
  },
);
