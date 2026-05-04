// Plain-JS fixture for OnDeviceBundleClientCallToolTest — authored against the
// `@trailblaze/scripting` SDK that the on-device bundle runtime now ships as part of its
// prelude. Drop-in replacement for the hand-rolled MCP-over-__trailblazeInProcessTransport
// fixture this file used to contain. That earlier file spoke MCP wire protocol directly
// because there was no author-side SDK on-device; the prelude now pre-installs
// `globalThis.trailblaze` + `globalThis.fromMeta` via `BundleRuntimePrelude.SDK_BUNDLE_SOURCE`,
// so author code is just `trailblaze.tool(...)` + `await trailblaze.run()` in a single file.
// No npm install, no esbuild step at author time, no MCP boilerplate.
//
// This file also serves as the acceptance signal for the TS→JS bundler automation: if the
// SDK bundle regresses — console shim stripped, callback binding shadowed, Server.connect
// refusing the in-process transport — the 5 tests in OnDeviceBundleClientCallToolTest fail
// loud rather than passing against the old hand-rolled code.
//
// Advertises:
//   outerCompose    — { text: string } → composes the Kotlin-side `reverseEcho` tool via the
//                     SDK's `client.callTool(...)` and wraps the reversed text in
//                     { composedFrom, original, reversed } JSON. The one tool that's purely
//                     SDK-authored — the happy path for on-device `client.callTool`.
//   callInvalidTool — exercises the dispatcher's unknown-tool branch. The SDK's callTool
//                     throws on an error envelope, so this tool drops to the raw
//                     `__trailblazeCallback` binding to read the wire-shape result the test
//                     asserts on. Not a regression in the SDK authoring story — it's
//                     testing a transport branch the SDK deliberately doesn't expose.
//   callBadSession  — same raw-binding approach; forges a session_id the Kotlin dispatcher
//                     rejects, exercising the session-mismatch branch.
//
// Why the callback target is a Kotlin tool, not another bundle tool:
// Recursive bundle→bundle callbacks would re-acquire `InProcessMcpTransport.evalMutex` while
// the outer send still holds it and deadlock. The current consumer only needs bundle→Kotlin
// composition, so that's what this fixture exercises. Fixing the deadlock (teach the
// transport to suspend across callback dispatch) is a separate issue.

const CALLBACK_TARGET_TOOL = "reverseEcho";

// Why `inputSchema: {}` instead of an omitted key or a JSON Schema: MCP's `registerTool`
// wants a zod schema or raw zod shape there, not a JSON Schema object. An empty raw shape
// (`{}`) is the no-args path — valid without needing zod as a global. Omitting the key is
// NOT equivalent: the MCP SDK falls back to a different handler signature (extra-only, no
// args) when `inputSchema` is undefined, which breaks the `(args, ctx, client)` contract
// the TS SDK wraps. Follow-up issue can stamp `globalThis.z` in the SDK bundle footer so
// plain-JS authors can pin their argument shapes.
trailblaze.tool(
  "outerCompose",
  {
    description: "Composes reverseEcho via the on-device callback channel and wraps the result.",
    inputSchema: {},
  },
  async (args, ctx, client) => {
    const text = (args && typeof args.text === "string") ? args.text : "";
    // Validates the on-device `console` shim end-to-end: this line will appear in ATF logcat
    // tagged `[bundle] level=log msg=outerCompose received ...`, proving the shim routes
    // variadic args through the Kotlin binding. If the prelude fails to install
    // `globalThis.console` or the SDK bundle shadows it, this line throws ReferenceError and
    // the happy-path test fails loud.
    console.log("outerCompose received", { text: text, sessionId: ctx && ctx.sessionId });
    const inner = await client.callTool(CALLBACK_TARGET_TOOL, { text: text });
    return {
      content: [
        {
          type: "text",
          text: JSON.stringify({
            composedFrom: CALLBACK_TARGET_TOOL,
            original: text,
            reversed: inner.textContent,
          }),
        },
      ],
      isError: false,
    };
  },
);

// Below: `callInvalidTool` and `callBadSession` need to SEE the raw dispatcher envelope (for
// the test's assertions on `type` / `success` / `error_message`), so they bypass the SDK's
// `client.callTool` — which throws on error envelopes — and dispatch through the
// `__trailblazeCallback` binding directly. Same wire shape the SDK would build; just without
// the SDK's throw-on-failure unwrap. Not the vibe-up story — it's test scaffolding.

async function rawCallback(meta, toolName, argsObj, sessionOverride) {
  if (!meta) {
    throw new Error(
      "rawCallback: no _meta.trailblaze envelope — Kotlin runtime should have stamped " +
      "runtime=ondevice + sessionId + invocationId. Aborting.",
    );
  }
  if (typeof globalThis.__trailblazeCallback !== "function") {
    throw new Error("rawCallback: globalThis.__trailblazeCallback not installed by Kotlin.");
  }
  const request = {
    version: 1,
    session_id: sessionOverride != null ? sessionOverride : meta.sessionId,
    invocation_id: meta.invocationId,
    action: {
      type: "call_tool",
      tool_name: toolName,
      arguments_json: JSON.stringify(argsObj),
    },
  };
  const responseJson = await globalThis.__trailblazeCallback(JSON.stringify(request));
  const envelope = JSON.parse(responseJson);
  const result = envelope && envelope.result;
  if (!result) {
    throw new Error("rawCallback: envelope missing result: " + responseJson);
  }
  return result;
}

trailblaze.tool(
  "callInvalidTool",
  {
    description: "Exercises the inner-tool-failure branch by dispatching a tool that isn't registered.",
    inputSchema: {},
  },
  async (_args, ctx) => {
    const inner = await rawCallback(ctx, "tool_that_does_not_exist", {});
    return {
      content: [{ type: "text", text: JSON.stringify(inner) }],
      isError: false,
    };
  },
);

trailblaze.tool(
  "callBadSession",
  {
    description: "Exercises the session-mismatch branch by forging a session_id.",
    inputSchema: {},
  },
  async (_args, ctx) => {
    const inner = await rawCallback(ctx, CALLBACK_TARGET_TOOL, { text: "x" }, "not-the-real-session-id");
    return {
      content: [{ type: "text", text: JSON.stringify(inner) }],
      isError: false,
    };
  },
);

await trailblaze.run({ name: "ondevice-callback-fixture-bundle", version: "1.0.0" });
