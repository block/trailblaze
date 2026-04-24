// Hand-crafted MCP-speaking JS fixture for OnDeviceBundleCallbackRoundTripTest.
//
// Same authoring shape as `bundle-roundtrip-fixture.js` — speaks MCP wire protocol directly
// against `globalThis.__trailblazeInProcessTransport` so the Kotlin transport plumbing stays
// exercised WITHOUT bundling the real @modelcontextprotocol/sdk. Difference: this fixture
// also exercises the on-device CALLBACK channel by having `outerCompose`'s handler call
// `globalThis.__trailblazeCallback(...)` (the same binding the TS SDK uses when
// `ctx.runtime === "ondevice"`) to dispatch a Kotlin-side tool (`reverseEcho`) and wrap
// the result.
//
// Author note: this fixture hand-rolls what the TS SDK's `TrailblazeClient.callTool` does
// because QuickJS can't parse TypeScript and the TS→JS bundler automation is still
// open. When that lands, a follow-up converts this to a real SDK-authored tool whose
// handler calls `ctx.client.callTool("reverseEcho", ...)` — the Kotlin-side transport being
// exercised here is identical either way.
//
// Why the callback target is a KOTLIN tool, not another bundle tool:
// Recursive bundle→bundle callbacks would re-acquire `InProcessMcpTransport.evalMutex`
// while the outer send still holds it and deadlock. The current consumer only needs
// bundle→Kotlin-tool composition, so that's what this fixture exercises; a follow-up will
// teach the in-process transport to suspend its mutex across a callback dispatch so
// bundle→bundle composition becomes safe.
//
// Advertises:
//   outerCompose    — takes { text: string }; dispatches the Kotlin-side `reverseEcho`
//                     tool via `__trailblazeCallback`, wraps the result as
//                     `{ composedFrom: "reverseEcho", original, reversed }` JSON.
//   callInvalidTool — callback dispatches a tool that isn't registered; used to assert
//                     the inner-failure path surfaces cleanly.
//   callBadSession  — forges a session_id that doesn't match the outer invocation; used
//                     to prove the Kotlin-side session-mismatch guard fires through the
//                     in-process binding, same as the HTTP endpoint.

(function() {
  const SERVER_INFO = { name: "ondevice-callback-fixture-bundle", version: "1.0.0" };
  const CALLBACK_TARGET_TOOL = "reverseEcho";

  const tools = [
    {
      name: "outerCompose",
      description: "Composes reverseEcho via the on-device callback channel and wraps the result.",
      inputSchema: {
        type: "object",
        properties: { text: { type: "string", description: "Text to compose via reverseEcho" } },
        required: ["text"]
      }
    },
    {
      name: "callInvalidTool",
      description: "Exercises the inner-tool-failure branch by dispatching a tool that isn't registered.",
      inputSchema: { type: "object", properties: {}, required: [] }
    },
    {
      name: "callBadSession",
      description: "Exercises the session-mismatch branch by forging a session_id.",
      inputSchema: { type: "object", properties: {}, required: [] }
    }
  ];

  const transport = globalThis.__trailblazeInProcessTransport;
  if (!transport) {
    throw new Error("Fixture bundle: __trailblazeInProcessTransport missing. Prelude must evaluate first.");
  }

  function readTrailblazeMeta(msg) {
    // The Kotlin runtime stamps `_meta.trailblaze` on every tools/call it dispatches when a
    // JsScriptingCallbackContext is wired — sessionId + invocationId + runtime + device + memory. Throw
    // loudly if it's missing: the whole test premise is that this envelope is there.
    const meta = msg.params && msg.params._meta && msg.params._meta.trailblaze;
    if (!meta) {
      throw new Error(
        "outerCompose: no _meta.trailblaze envelope — Kotlin runtime should have stamped " +
        "runtime=ondevice + sessionId + invocationId. Aborting."
      );
    }
    return meta;
  }

  async function callBackSession(meta, toolName, argsObj, sessionOverride) {
    // Build the same JsScriptingCallbackRequest the TS SDK's `TrailblazeClient.callTool` would build.
    // Wire shape: { version, session_id, invocation_id, action }. `arguments_json` is a
    // STRING, not a nested object (see JsScriptingCallbackContract.kt D2 decision).
    const request = {
      version: 1,
      session_id: sessionOverride != null ? sessionOverride : meta.sessionId,
      invocation_id: meta.invocationId,
      action: {
        type: "call_tool",
        tool_name: toolName,
        arguments_json: JSON.stringify(argsObj)
      }
    };

    if (typeof globalThis.__trailblazeCallback !== "function") {
      throw new Error("outerCompose: globalThis.__trailblazeCallback not installed by Kotlin.");
    }

    const responseJson = await globalThis.__trailblazeCallback(JSON.stringify(request));
    const envelope = JSON.parse(responseJson);
    const result = envelope && envelope.result;
    if (!result) {
      throw new Error("outerCompose: callback envelope missing result: " + responseJson);
    }
    return result;
  }

  transport.onmessage = async function(msg) {
    try {
      if (msg.method === "initialize") {
        transport.send({
          jsonrpc: "2.0",
          id: msg.id,
          result: {
            protocolVersion: "2025-06-18",
            capabilities: { tools: {} },
            serverInfo: SERVER_INFO
          }
        });
        return;
      }
      if (msg.method === "notifications/initialized") {
        return;
      }
      if (msg.method === "tools/list") {
        transport.send({
          jsonrpc: "2.0",
          id: msg.id,
          result: { tools: tools }
        });
        return;
      }
      if (msg.method === "tools/call") {
        const params = msg.params;

        if (params.name === "outerCompose") {
          const meta = readTrailblazeMeta(msg);
          const input = (params.arguments && params.arguments.text) || "";
          // Validates the on-device `console` shim end-to-end: this line will appear in ATF
          // logcat tagged `[bundle] level=log msg=outerCompose received ...`, proving the
          // shim routes variadic args through the Kotlin binding. If the prelude fails to
          // install `globalThis.console`, this line throws ReferenceError and the test
          // fails loud.
          console.log("outerCompose received", { text: input, sessionId: meta.sessionId });
          const innerResult = await callBackSession(meta, CALLBACK_TARGET_TOOL, { text: input });
          if (innerResult.type !== "call_tool_result" || innerResult.success !== true) {
            // Surface the inner failure loudly — this path should never fire in the happy-
            // path test, and a regression in the Kotlin-side dispatcher that silently fails
            // is exactly what we need the test to catch.
            throw new Error(
              "outerCompose: reverseEcho callback failed: " + JSON.stringify(innerResult)
            );
          }
          const composed = {
            composedFrom: CALLBACK_TARGET_TOOL,
            original: input,
            reversed: innerResult.text_content
          };
          transport.send({
            jsonrpc: "2.0",
            id: msg.id,
            result: {
              content: [{ type: "text", text: JSON.stringify(composed) }],
              isError: false
            }
          });
          return;
        }

        if (params.name === "callInvalidTool") {
          const meta = readTrailblazeMeta(msg);
          const innerResult = await callBackSession(meta, "tool_that_does_not_exist", {});
          // Echo whatever the callback produced so the test can assert on its shape.
          transport.send({
            jsonrpc: "2.0",
            id: msg.id,
            result: {
              content: [{ type: "text", text: JSON.stringify(innerResult) }],
              isError: false
            }
          });
          return;
        }

        if (params.name === "callBadSession") {
          const meta = readTrailblazeMeta(msg);
          const innerResult = await callBackSession(
            meta,
            CALLBACK_TARGET_TOOL,
            { text: "x" },
            "not-the-real-session-id"
          );
          transport.send({
            jsonrpc: "2.0",
            id: msg.id,
            result: {
              content: [{ type: "text", text: JSON.stringify(innerResult) }],
              isError: false
            }
          });
          return;
        }

        transport.send({
          jsonrpc: "2.0",
          id: msg.id,
          error: { code: -32601, message: "Unknown tool: " + params.name }
        });
        return;
      }
      transport.send({
        jsonrpc: "2.0",
        id: msg.id,
        error: { code: -32601, message: "Unknown method: " + msg.method }
      });
    } catch (e) {
      // Top-level guard: an unhandled throw inside an async handler would reject the
      // transport's pending Promise, which the Kotlin-side Client would see as a protocol
      // error with no diagnostic. Mapping to `isError: true` with the message gives the
      // test a readable failure to assert against.
      transport.send({
        jsonrpc: "2.0",
        id: msg.id,
        result: {
          content: [{ type: "text", text: "fixture threw: " + (e && e.message ? e.message : String(e)) }],
          isError: true
        }
      });
    }
  };
})();
