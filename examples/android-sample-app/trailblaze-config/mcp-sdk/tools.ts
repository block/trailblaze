// MCP tools for the Trailblaze sample-app example — SDK authoring surface.
//
// Sister file to ../mcp/tools.ts. Both implement the **same two tools** —
// ../mcp exposes `generateTestUser`/`currentEpochMillis`; this file exposes
// `generateTestUserSdk`/`currentEpochMillisSdk` so the two sources register side-by-side in
// the session tool repo without colliding on names. Implementation is identical; the
// authoring surface is the only thing that differs:
//
//   - ../mcp/tools.ts        raw @modelcontextprotocol/sdk — four imports, manual server
//                            construction + connect(), no envelope access.
//   - ./tools.ts (this file) @trailblaze/scripting — one import, `trailblaze.tool()` for each,
//                            `await trailblaze.run()` at the bottom, envelope surfaces as
//                            a typed TrailblazeContext on every handler.
//
// Both coexist in the sample-app target YAML so CI exercises both paths and any drift between
// the two authoring surfaces is caught immediately.
//
// Tools advertised (all suffixed with `Sdk` to coexist with the raw-SDK file's same-shape tools):
//   generateTestUserSdk    Returns a fresh random {name, email} pair.
//   currentEpochMillisSdk  Returns the current Unix epoch in milliseconds as a string.
//   trailblazeContextSdk   Returns the injected TrailblazeContext envelope (device, sessionId,
//                          invocationId, memory) as a JSON string — proves the `ctx` read path
//                          works end-to-end from Kotlin envelope injection through the SDK's
//                          `fromMeta` back to the handler.
//   signUpNewUserSdk       Composition proof — dispatches `generateTestUserSdk` via a callback
//                          to prove the `client.callTool(...)` round-trip works end-to-end.
//                          NOT a full signup flow; the name is forward-looking (the pattern is
//                          what a real signup tool would extend with UI taps + assertVisible).
//                          Calls back into the daemon's `/scripting/callback` endpoint, parses
//                          the dispatched tool's text output, returns a summary string.
//
// Install: `bun install` (or `npm install`) in this directory. The @trailblaze/scripting
// dependency resolves to the SDK package at sdks/typescript/ via a file: link.

import { trailblaze } from "@trailblaze/scripting";

// Small pool so the generated names feel realistic without pulling in a full faker dep. The
// important property for a reference example is "every call returns different output" — not
// linguistic variety. Swap in `@faker-js/faker` if you want richer data in your own targets.
const FIRST_NAMES = [
  "Alex", "Sam", "Jordan", "Taylor", "Casey", "Morgan", "Riley", "Jamie", "Quinn", "Reese",
];
const LAST_NAMES = [
  "Carter", "Nguyen", "Patel", "Okafor", "Hernandez", "Kim", "Silva", "Müller", "Kowalski", "Tanaka",
];

function pick<T>(xs: readonly T[]): T {
  return xs[Math.floor(Math.random() * xs.length)]!;
}

trailblaze.tool(
  "generateTestUserSdk",
  {
    description:
      "Generates a random test user with {name, email}. Use whenever a trail needs a fresh identity " +
      "(signup, form entry, login flows). Each call returns a different user.",
    inputSchema: {},
  },
  async () => {
    const first = pick(FIRST_NAMES);
    const last = pick(LAST_NAMES);
    const name = `${first} ${last}`;
    // Lowercase + 4-digit salt so multiple calls in one session don't collide.
    const salt = Math.floor(Math.random() * 10000).toString().padStart(4, "0");
    const email = `${first}.${last}.${salt}@example.com`.toLowerCase();
    return {
      content: [{ type: "text", text: JSON.stringify({ name, email }) }],
      isError: false,
    };
  },
);

trailblaze.tool(
  "currentEpochMillisSdk",
  {
    description:
      "Returns the current Unix epoch time in milliseconds, as a string. Useful for date-picker " +
      "tests or stamping test artifacts with a timestamp.",
    inputSchema: {},
  },
  async () => ({
    content: [{ type: "text", text: String(Date.now()) }],
    isError: false,
  }),
);

// Envelope-read proof tool. Exists to exercise the `ctx` argument end-to-end in CI — the Kotlin
// side injects `_meta.trailblaze`, the SDK's `fromMeta` reader parses it, the handler sees it as
// a typed TrailblazeContext. If any link in that chain regresses, `SampleAppMcpSdkToolsTest`
// fails loud. When `ctx` is undefined (tool invoked outside a Trailblaze session), returns a
// sentinel so the test can distinguish "no envelope" from "envelope with empty fields".
trailblaze.tool(
  "trailblazeContextSdk",
  {
    description:
      "Returns the injected TrailblazeContext envelope as a JSON string. Testing utility — " +
      "proves the envelope reaches author-side handlers.",
    inputSchema: {},
  },
  async (_args, ctx) => {
    const payload = ctx ?? { status: "no-envelope" };
    return {
      content: [{ type: "text", text: JSON.stringify(payload) }],
      isError: false,
    };
  },
);

// Callback round-trip proof tool. Composes `generateTestUserSdk` via `client.callTool(...)`.
// The SDK auto-selects the transport from the envelope — HTTP to `/scripting/callback` when
// this tool runs as a host subprocess, the in-process `__trailblazeCallback` binding when it
// runs inside the Android on-device QuickJS bundle. Authoring code is identical in both
// runtimes. In the real authoring pitch this would be followed by
// `client.callTool("tapOnElementWithText", ...)` etc. to drive a signup flow; the coverage
// here keeps the dependency chain to just the one inner tool so the host-JVM test doesn't
// need a live device. Still end-to-end: Kotlin-side JsScriptingInvocationRegistry lookup →
// tool dispatch → envelope round-trip → TS parse.
trailblaze.tool(
  "signUpNewUserSdk",
  {
    description:
      "Composition proof: dispatches generateTestUserSdk via the callback channel and echoes " +
      "the composed result. Not a full signup flow — just the round-trip demo. Use this pattern " +
      "as a starting point for a real signup tool that chains generateTestUserSdk -> UI taps -> " +
      "assertVisible.",
    inputSchema: {},
  },
  async (_args, _ctx, client) => {
    // Dispatch the inner tool through the callback channel. `callTool` throws on any non-success
    // outcome (daemon error, tool failure, timeout, reentrance cap hit), so the author's happy
    // path is a plain sequence of awaits — no success-flag branching.
    const userResult = await client.callTool("generateTestUserSdk", {});
    // The inner tool returns a JSON-encoded {name, email}. Wrap JSON.parse in a try/catch so a
    // future rewrite that changes the inner tool's output shape (or a proxy returning empty text)
    // produces a tool-scoped error rather than an opaque SyntaxError from deep in the callback
    // unwind. Authors copying this pattern should keep the try/catch — the `textContent` contract
    // between composed tools is "whatever string the inner tool emits," not "always valid JSON."
    let user: { name: string; email: string };
    try {
      user = JSON.parse(userResult.textContent) as { name: string; email: string };
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      throw new Error(
        `signUpNewUserSdk: inner tool generateTestUserSdk returned non-JSON textContent (${message}): ${userResult.textContent}`,
      );
    }
    return {
      content: [
        {
          type: "text",
          text: JSON.stringify({ composedFrom: "generateTestUserSdk", name: user.name, email: user.email }),
        },
      ],
      isError: false,
    };
  },
);

await trailblaze.run({ name: "sample-app-mcp-tools-sdk", version: "0.1.0" });
