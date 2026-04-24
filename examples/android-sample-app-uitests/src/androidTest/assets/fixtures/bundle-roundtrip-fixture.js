// Hand-crafted MCP-speaking JS fixture for OnDeviceBundleRoundTripTest — the android
// instrumentation counterpart to jvmTest's bundle-roundtrip-fixture.js.
//
// Loaded at runtime via `AndroidAssetBundleJsSource` so this test ALSO exercises the
// asset-path resolver that production `AndroidTrailblazeRule.mcpServers` callers will
// hit — not just the inline-string fast path. Shipped under `src/androidTest/assets/`
// so AGP bundles it into the test APK's assets/, where the instrumentation's
// AssetManager can open it.
//
// Does NOT use the real @modelcontextprotocol/sdk — speaks MCP wire protocol directly
// against `globalThis.__trailblazeInProcessTransport` so the Kotlin-side transport +
// launcher + registration-filter plumbing is exercised without bundling the SDK. Real
// SDK coverage lives at the author-bundle build layer (PR A5's §BYO bundler).
//
// Advertises two tools:
//   echoReverse    — no `_meta`; always registers; `tools/call` returns reversed text.
//   hostOnlyTool   — `_meta: { "trailblaze/requiresHost": true }`; skipped on-device.

(function() {
  const SERVER_INFO = { name: "ondevice-fixture-bundle", version: "1.0.0" };
  const tools = [
    {
      name: "echoReverse",
      description: "Returns the input string reversed.",
      inputSchema: {
        type: "object",
        properties: { text: { type: "string", description: "Text to reverse" } },
        required: ["text"]
      }
    },
    {
      name: "hostOnlyTool",
      description: "Requires the host agent. Must not register on-device.",
      inputSchema: { type: "object", properties: {}, required: [] },
      _meta: { "trailblaze/requiresHost": true }
    }
  ];

  const transport = globalThis.__trailblazeInProcessTransport;
  if (!transport) {
    throw new Error("Fixture bundle: __trailblazeInProcessTransport missing. Prelude must evaluate first.");
  }

  transport.onmessage = function(msg) {
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
      if (params.name === "echoReverse") {
        const input = (params.arguments && params.arguments.text) || "";
        const reversed = input.split("").reverse().join("");
        transport.send({
          jsonrpc: "2.0",
          id: msg.id,
          result: {
            content: [{ type: "text", text: reversed }],
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
  };
})();
